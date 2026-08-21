import Foundation
import CocoaMQTT
import Security
import React
import os.log

@objc(MqttModule)
class MqttModule: RCTEventEmitter {
    /// Prefix marker to distinguish intentional Base64-encoded binary messages
    /// from plain text that happens to be valid Base64 (e.g., JSON strings).
    /// Must match the prefix in JavaScript layer (MqttManager.ts).
    private static let BINARY_MARKER = "B64:"

    /// Detects whether a message is binary based on topic pattern and payload inspection.
    ///
    /// CRITICAL: UTF-8 heuristic alone is insufficient for protobuf detection because small
    /// protobufs with ASCII serial numbers and low field tags can be valid UTF-8, causing
    /// misclassification that leads to parse failures in downstream handlers.
    ///
    /// Detection strategy:
    /// 1. Topic-based (deterministic): Known binary topics are always treated as binary
    /// 2. Content-based (fallback): UTF-8 validity check for unknown topics
    ///
    /// NOTE: This is intentionally asymmetric with the publish path.
    /// - PUBLISH (JS → Native): Uses B64: prefix marker
    /// - RECEIVE (Native → JS): Uses topic patterns + UTF-8 heuristic
    ///
    /// This allows us to handle messages from ANY publisher, not just our app.
    /// External publishers won't use our B64: convention.
    ///
    /// - Parameters:
    ///   - topic: The MQTT topic (used for pattern matching)
    ///   - data: The message payload bytes
    /// - Returns: true if message should be treated as binary, false for text
    internal func isBinaryData(topic: String, data: Data) -> Bool {
        // DETERMINISTIC: Topic-based detection for known binary message patterns
        // These topics carry protobuf or firmware data and must always be binary

        // Protobuf topics (device lists, RMA swap, hardware assembly, etc.)
        // `/network/` must precede the `/config` text rule below: network config/state
        // responses are protobuf, but their topics contain `/config`.
        if topic.contains("/proto/") ||
           topic.contains("/device") ||
           topic.contains("/rma") ||
           topic.contains("/assembly") ||
           topic.contains("/installed") ||
           topic.contains("/network/") {
            return true
        }

        // Firmware update topics
        if topic.contains("/firmware") ||
           topic.contains("/ota") ||
           topic.contains("/upload") {
            return true
        }

        // Text topics (JSON status, configuration, commands)
        if topic.contains("/status") ||
           topic.contains("/config") ||
           topic.contains("/command") ||
           topic.contains("/json") {
            return false
        }

        // FALLBACK: UTF-8 heuristic for unknown topics
        // Known limitation: ASCII-range protobufs (rare edge case) can pass UTF-8 validation
        // and be misclassified as text. The topic-based detection above handles known high-risk
        // patterns (device lists, firmware, etc.). For unknown topics, UTF-8 validity is a
        // reasonable heuristic that allows new text topics to work without package updates.
        // If you have binary topics that are misclassified, add them to the patterns above.
        //
        // NOTE: This entire detection mechanism will be eliminated in the upcoming JSI/Expo Module
        // rewrite (IA-5754), which will pass Uint8Array directly without needing content inspection.
        return String(data: data, encoding: .utf8) == nil
    }

    private var mqttClient: CocoaMQTT?
    private var expectedBrokerCN: String?
    private var trustedRootCerts: [SecCertificate] = []
    private var connectSuccessCallback: RCTResponseSenderBlock?
    private var connectErrorCallback: RCTResponseSenderBlock?
    private var brokerUrl: String = ""
    private var clientIdentifier: String = ""
    private var connectionStartTime: Date?
    private var isAutoReconnectEnabled: Bool = false

    private let logger = OSLog(subsystem: "com.neurio.generachome", category: "MqttModule")

    /// Wrapper class to ensure React Native callbacks are invoked exactly once.
    /// Prevents crashes from React Native bridge's single-fire invariant violation.
    private class CallbackGuard {
        private var callback: RCTResponseSenderBlock?
        private var hasFired = false
        private let lock = NSLock()

        init(_ callback: RCTResponseSenderBlock?) {
            self.callback = callback
        }

        func invoke(_ args: [Any]) {
            lock.lock()
            defer { lock.unlock() }

            guard !hasFired, let callback = callback else {
                if hasFired {
                    os_log("Suppressed duplicate callback invocation", type: .default)
                }
                return
            }

            hasFired = true
            self.callback = nil
            callback(args)
        }
    }
    
    override init() {
        super.init()
        
        // Clean up any lingering state from previous app instances
        cleanupConnection()
        
        os_log("=====================================", log: logger, type: .info)
        os_log("MqttModule initialized", log: logger, type: .info)
        os_log("iOS Version: %{public}@", log: logger, type: .info, UIDevice.current.systemVersion)
        os_log("Device Model: %{public}@", log: logger, type: .info, UIDevice.current.model)
        os_log("=====================================", log: logger, type: .info)
    }
    
    deinit {
        os_log("MqttModule deinitializing - cleaning up", log: logger, type: .info)
        cleanupConnection()
    }
    
    override func supportedEvents() -> [String]! {
        return ["MqttConnected", "MqttDisconnected", "MqttMessage", "MqttDeliveryComplete"]
    }
    
    override static func requiresMainQueueSetup() -> Bool {
        return false
    }
    
    // Centralized cleanup method
    private func cleanupConnection() {
        os_log("Cleaning up connection state...", log: logger, type: .info)
        
        if let client = mqttClient {
            client.autoReconnect = false
            client.disconnect()
            os_log("  - Disconnected existing client", log: logger, type: .info)
        }
        
        mqttClient = nil
        connectSuccessCallback = nil
        connectErrorCallback = nil
        expectedBrokerCN = nil
        trustedRootCerts = []
        brokerUrl = ""
        clientIdentifier = ""
        connectionStartTime = nil
        
        os_log("✓ Cleanup complete", log: logger, type: .info)
    }
    
    // `errorCallback` is part of the bridge contract declared in MqttModule.m and implemented on
    // Android, so it stays in the signature even though nothing here can fail. Dropping it changes
    // the exported selector to `cleanup:`, which no longer matches the declaration, and React Native
    // then drops the whole method from the JS module.
    @objc
    func cleanup(_ successCallback: @escaping RCTResponseSenderBlock,
                 errorCallback: @escaping RCTResponseSenderBlock) {
        os_log("", log: logger, type: .info)
        os_log("───────────────────────────────────────────────────────", log: logger, type: .info)
        os_log("EXPLICIT CLEANUP REQUESTED", log: logger, type: .info)
        os_log("───────────────────────────────────────────────────────", log: logger, type: .info)

        cleanupConnection()

        os_log("", log: logger, type: .info)
        successCallback(["Cleanup successful"])
    }
    
    @objc
    func connect(
        _ broker: String,
        clientId: String,
        certificates: NSDictionary,
        sniHostname: String?,
        brokerIp: String?,
        brokerCommonName: String?,
        isAdminUser: Bool,
        successCallback: @escaping RCTResponseSenderBlock,
        errorCallback: @escaping RCTResponseSenderBlock
    ) {
        // Use a shared settled flag to ensure exactly one callback fires (success OR error, not both)
        // This provides mutual exclusion matching Android's single AtomicBoolean behavior
        var settled = false
        let settledLock = NSLock()

        let successGuard = CallbackGuard { args in
            settledLock.lock()
            let wasSettled = settled
            if !wasSettled {
                settled = true
            }
            settledLock.unlock()

            if !wasSettled {
                successCallback(args)
            } else {
                os_log("Suppressed success callback - connect already settled", type: .default)
            }
        }

        let errorGuard = CallbackGuard { args in
            settledLock.lock()
            let wasSettled = settled
            if !wasSettled {
                settled = true
            }
            settledLock.unlock()

            if !wasSettled {
                errorCallback(args)
            } else {
                os_log("Suppressed error callback - connect already settled", type: .default)
            }
        }

        // Ensure clean slate before new connection
        if mqttClient != nil {
            os_log("Found existing client, cleaning up before new connection...", log: logger, type: .info)
            cleanupConnection()
        }
        
        connectionStartTime = Date()
        
        os_log("", log: logger, type: .info)
        os_log("═══════════════════════════════════════════════════════", log: logger, type: .info)
        os_log("MQTT CONNECTION ATTEMPT STARTED", log: logger, type: .info)
        os_log("═══════════════════════════════════════════════════════", log: logger, type: .info)
        os_log("Timestamp: %{public}@", log: logger, type: .info, ISO8601DateFormatter().string(from: Date()))
        os_log("Broker URL: %{public}@", log: logger, type: .info, broker)
        os_log("Client ID: %{public}@", log: logger, type: .info, clientId)
        os_log("Admin user: %{public}@", log: logger, type: .info, String(isAdminUser))
        os_log("SNI Hostname: %{public}@", log: logger, type: .info, isAdminUser ? "N/A (admin)" : (sniHostname ?? "nil"))
        os_log("Broker IP: %{public}@", log: logger, type: .info, brokerIp ?? "nil")
        os_log("Expected Broker CN: %{public}@", log: logger, type: .info, isAdminUser ? "N/A (admin)" : (brokerCommonName ?? "nil"))
        os_log("", log: logger, type: .info)
        
        // Admin users have access to multiple brokers — sniHostname and brokerCommonName
        // are per-inverter fields and are ignored when isAdminUser = true
        let effectiveSniHostname = isAdminUser ? nil : sniHostname
        let effectiveBrokerCN = isAdminUser ? nil : brokerCommonName
        
        do {
            os_log("STEP 1: Validating parameters...", log: logger, type: .info)
            
            let clientCertPem = certificates["clientCert"] as? String
            let privateKeyAlias = certificates["privateKeyAlias"] as? String
            let rootCaPem = certificates["rootCa"] as? String
            let useHardwareKey = certificates["useHardwareKey"] as? Bool ?? false
            
            os_log("  - clientCert present: %{public}@", log: logger, type: .info, String(clientCertPem != nil))
            os_log("  - privateKeyAlias: %{public}@", log: logger, type: .info, privateKeyAlias ?? "nil")
            os_log("  - rootCa present: %{public}@", log: logger, type: .info, String(rootCaPem != nil))
            os_log("  - useHardwareKey: %{public}@", log: logger, type: .info, String(useHardwareKey))
            
            guard let rootCa = rootCaPem, 
                  let clientCert = clientCertPem, 
                  let keyAlias = privateKeyAlias else {
                let error = "Missing required parameters (clientCert, privateKeyAlias, or rootCa)"
                os_log("ERROR: %{public}@", log: logger, type: .error, error)
                errorGuard.invoke([error])
                return
            }
            
            os_log("✓ All required parameters present", log: logger, type: .info)
            os_log("", log: logger, type: .info)
            
            if let cn = effectiveBrokerCN, !cn.isEmpty {
                os_log("✓ Broker CN validation enabled: %{public}@", log: logger, type: .info, cn)
            } else {
                os_log("Broker CN validation skipped (admin user)", log: logger, type: .info)
            }
            os_log("", log: logger, type: .info)
            
            os_log("STEP 2: Parsing broker URL...", log: logger, type: .info)
            
            guard let url = URL(string: broker) else {
                throw NSError(domain: "MqttModule", code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "Invalid broker URL"])
            }
            
            let host = brokerIp ?? url.host ?? ""
            let port = UInt16(url.port ?? 8883)
            let useTLS = url.scheme == "ssl" || url.scheme == "mqtts"
            
            os_log("  - Scheme: %{public}@", log: logger, type: .info, url.scheme ?? "nil")
            os_log("  - Host: %{public}@", log: logger, type: .info, host)
            os_log("  - Port: %d", log: logger, type: .info, port)
            os_log("  - Use TLS: %{public}@", log: logger, type: .info, String(useTLS))
            os_log("✓ URL parsed successfully", log: logger, type: .info)
            os_log("", log: logger, type: .info)
            
            os_log("STEP 3: Creating CocoaMQTT client...", log: logger, type: .info)
            
            let client = CocoaMQTT(clientID: clientId, host: host, port: port)
            os_log("  - Client instance created", log: logger, type: .info)
            
            client.username = ""
            client.password = ""
            client.keepAlive = 60
            client.cleanSession = true
            client.autoReconnect = isAutoReconnectEnabled
            
            os_log("  - keepAlive: 60 seconds", log: logger, type: .info)
            os_log("  - cleanSession: true", log: logger, type: .info)
            os_log("  - autoReconnect: false", log: logger, type: .info)
            os_log("✓ Client configured", log: logger, type: .info)
            os_log("", log: logger, type: .info)
            
            if useTLS {
                os_log("STEP 4: Configuring TLS/SSL...", log: logger, type: .info)
                
                os_log("  4a: Validating CA certificates...", log: logger, type: .info)
                let caCerts = try parseCertificatesFromPEM(rootCa)
                os_log("    - Found %d CA certificate(s)", log: logger, type: .info, caCerts.count)
                
                guard !caCerts.isEmpty else {
                    throw NSError(domain: "MqttModule", code: -1,
                                userInfo: [NSLocalizedDescriptionKey: "No CA certificates found"])
                }
                
                for (index, cert) in caCerts.enumerated() {
                    if let summary = SecCertificateCopySubjectSummary(cert) as String? {
                        os_log("    - CA %d: %{public}@", log: logger, type: .info, index + 1, summary)
                    }
                }
                
                self.expectedBrokerCN = effectiveBrokerCN  // nil for admin — CN validation skipped
                self.trustedRootCerts = caCerts  // anchors for server chain validation (always required)
                os_log("  ✓ CA certificates validated", log: logger, type: .info)
                os_log("", log: logger, type: .info)
                
                os_log("  4b: Creating SSL settings...", log: logger, type: .info)
                os_log("    - Private key alias: %{public}@", log: logger, type: .info, keyAlias)
                os_log("    - Hardware key: %{public}@", log: logger, type: .info, String(useHardwareKey))
                
                let sslSettings = try self.createSSLSettings(
                    privateKeyAlias: keyAlias,
                    clientCertPem: clientCert,
                    rootCaPem: rootCa,
                    sniHostname: effectiveSniHostname,  // nil for admin
                    useHardwareKey: useHardwareKey
                )
                
                os_log("  ✓ SSL settings created", log: logger, type: .info)
                os_log("    - Settings keys: %{public}@", log: logger, type: .info, sslSettings.keys.joined(separator: ", "))
                os_log("", log: logger, type: .info)
                
                client.enableSSL = true
                client.allowUntrustCACertificate = true
                client.sslSettings = sslSettings
                client.delegate = self
                
                os_log("  ✓ SSL enabled on client", log: logger, type: .info)
                os_log("    - enableSSL: true", log: logger, type: .info)
                os_log("    - allowUntrustCACertificate: true", log: logger, type: .info)
                os_log("    - delegate set", log: logger, type: .info)
                os_log("    - SNI hostname: %{public}@", log: logger, type: .info, sniHostname ?? "none")
                os_log("", log: logger, type: .info)
            }
            
            os_log("STEP 5: Storing callbacks and state...", log: logger, type: .info)
            self.connectSuccessCallback = { args in successGuard.invoke(args ?? []) }
            self.connectErrorCallback = { args in errorGuard.invoke(args ?? []) }
            self.brokerUrl = broker
            self.clientIdentifier = clientId
            self.mqttClient = client
            os_log("✓ State stored", log: logger, type: .info)
            os_log("", log: logger, type: .info)
            
            os_log("STEP 6: Initiating connection...", log: logger, type: .info)
            os_log("  - Calling client.connect()...", log: logger, type: .info)
            
            let result = client.connect()
            
            os_log("  - client.connect() returned: %{public}@", log: logger, type: .info, String(result))
            
            if result {
                os_log("✓ Connection initiated successfully", log: logger, type: .info)
                os_log("  - Waiting for delegate callbacks...", log: logger, type: .info)
            } else {
                os_log("✗ Connection initiation FAILED", log: logger, type: .error)
                errorGuard.invoke(["Failed to start connection - client.connect() returned false"])
            }
            
            os_log("═══════════════════════════════════════════════════════", log: logger, type: .info)
            os_log("", log: logger, type: .info)
            
        } catch {
            os_log("", log: logger, type: .error)
            os_log("═══════════════════════════════════════════════════════", log: logger, type: .error)
            os_log("FATAL ERROR DURING CONNECTION SETUP", log: logger, type: .error)
            os_log("═══════════════════════════════════════════════════════", log: logger, type: .error)
            os_log("Error: %{public}@", log: logger, type: .error, error.localizedDescription)
            os_log("Error domain: %{public}@", log: logger, type: .error, (error as NSError).domain)
            os_log("Error code: %d", log: logger, type: .error, (error as NSError).code)
            os_log("", log: logger, type: .error)
            errorGuard.invoke([error.localizedDescription])
        }
    }
    
    @objc
    func disconnect(_ successCallback: @escaping RCTResponseSenderBlock,
                   errorCallback: @escaping RCTResponseSenderBlock) {
        os_log("", log: logger, type: .info)
        os_log("───────────────────────────────────────────────────────", log: logger, type: .info)
        os_log("DISCONNECT REQUESTED", log: logger, type: .info)
        os_log("───────────────────────────────────────────────────────", log: logger, type: .info)
        
        guard let client = mqttClient else {
            os_log("No active MQTT client to disconnect", log: logger, type: .info)
            successCallback(["No active connection"])
            return
        }
        
        os_log("Current connection state: %{public}@", log: logger, type: .info, String(describing: client.connState))
        os_log("Disabling auto-reconnect...", log: logger, type: .info)
        client.autoReconnect = false
        
        os_log("Calling disconnect()...", log: logger, type: .info)
        let successGuard = CallbackGuard(successCallback)

        client.disconnect()

        cleanupConnection()

        os_log("✓ Disconnected and cleaned up", log: logger, type: .info)
        os_log("", log: logger, type: .info)

        successGuard.invoke(["Disconnected successfully"])
    }
    
    @objc
    func subscribe(_ topic: String, qos: NSInteger,
                  successCallback: @escaping RCTResponseSenderBlock,
                  errorCallback: @escaping RCTResponseSenderBlock) {
        let successGuard = CallbackGuard(successCallback)
        let errorGuard = CallbackGuard(errorCallback)

        os_log("SUBSCRIBE: topic=%{public}@, qos=%d", log: logger, type: .info, topic, qos)

        guard let client = mqttClient, client.connState == .connected else {
            os_log("✗ Subscribe failed: Client not connected", log: logger, type: .error)
            errorGuard.invoke(["Client not connected"])
            return
        }

        let mqttQos = CocoaMQTTQoS(rawValue: UInt8(qos)) ?? .qos1
        client.subscribe(topic, qos: mqttQos)
        os_log("✓ Subscribe request sent", log: logger, type: .info)
        successGuard.invoke(["Subscribed to \(topic)"])
    }
    
    @objc
    func unsubscribe(_ topic: String,
                    successCallback: @escaping RCTResponseSenderBlock,
                    errorCallback: @escaping RCTResponseSenderBlock) {
        let successGuard = CallbackGuard(successCallback)
        let errorGuard = CallbackGuard(errorCallback)

        os_log("UNSUBSCRIBE: topic=%{public}@", log: logger, type: .info, topic)

        guard let client = mqttClient, client.connState == .connected else {
            os_log("✗ Unsubscribe failed: Client not connected", log: logger, type: .error)
            errorGuard.invoke(["Client not connected"])
            return
        }

        client.unsubscribe(topic)
        os_log("✓ Unsubscribe request sent", log: logger, type: .info)
        successGuard.invoke(["Unsubscribed from \(topic)"])
    }
    
    @objc
    func publish(_ topic: String, message: String, qos: NSInteger, retained: Bool,
                successCallback: @escaping RCTResponseSenderBlock,
                errorCallback: @escaping RCTResponseSenderBlock) {
        let successGuard = CallbackGuard(successCallback)
        let errorGuard = CallbackGuard(errorCallback)

        os_log("PUBLISH: topic=%{public}@, qos=%d, retained=%{public}@", log: logger, type: .info, topic, qos, String(retained))

        guard let client = mqttClient, client.connState == .connected else {
            os_log("✗ Publish failed: Client not connected", log: logger, type: .error)
            errorGuard.invoke(["Client not connected"])
            return
        }
        
        let mqttQos = CocoaMQTTQoS(rawValue: UInt8(qos)) ?? .qos1

        // Check if message is marked as Base64-encoded binary
        // This prevents accidental Base64 decoding of JSON strings that happen to be valid Base64
        if message.hasPrefix(MqttModule.BINARY_MARKER) {
            // Remove marker and decode Base64
            let base64String = String(message.dropFirst(MqttModule.BINARY_MARKER.count))
            if let binaryData = Data(base64Encoded: base64String) {
                let payload = [UInt8](binaryData)
                let mqttMessage = CocoaMQTTMessage(topic: topic, payload: payload, qos: mqttQos, retained: retained)
                client.publish(mqttMessage)
                os_log("✓ Published marked Base64 binary data (%d bytes)", log: logger, type: .info, payload.count)
            } else {
                os_log("✗ Failed to decode Base64 message", log: logger, type: .error)
                errorGuard.invoke(["Failed to decode Base64 message"])
                return
            }
        } else {
            // Plain text (UTF-8) - common case for JSON strings
            if let stringData = message.data(using: .utf8) {
                let payload = [UInt8](stringData)
                let mqttMessage = CocoaMQTTMessage(topic: topic, payload: payload, qos: mqttQos, retained: retained)
                client.publish(mqttMessage)
                os_log("✓ Published UTF-8 text data (%d bytes)", log: logger, type: .info, payload.count)
            } else {
                os_log("✗ Failed to encode message as UTF-8", log: logger, type: .error)
                errorGuard.invoke(["Failed to encode message as UTF-8"])
                return
            }
        }

        successGuard.invoke(["Published to \(topic)"])
    }
    
    @objc
    func isConnected(_ callback: @escaping RCTResponseSenderBlock) {
        let connected = mqttClient?.connState == .connected
        os_log("isConnected check: %{public}@", log: logger, type: .info, String(connected))
        callback([connected])
    }
    
    // ============================================================================
    // SSL CONFIGURATION
    // ============================================================================
    
    private func createSSLSettings(
        privateKeyAlias: String,
        clientCertPem: String,
        rootCaPem: String,
        sniHostname: String?,
        useHardwareKey: Bool
    ) throws -> [String: NSObject] {
        
        os_log("      → createSSLSettings() called", log: logger, type: .info)
        os_log("        - privateKeyAlias: %{public}@", log: logger, type: .info, privateKeyAlias)
        os_log("        - useHardwareKey: %{public}@", log: logger, type: .info, String(useHardwareKey))
        os_log("        - sniHostname: %{public}@", log: logger, type: .info, sniHostname ?? "nil")
        
        let (identity, intermediates) = try createIdentity(
            privateKeyAlias: privateKeyAlias,
            clientCertPem: clientCertPem,
            useHardwareKey: useHardwareKey
        )
        
        os_log("        ✓ Identity created", log: logger, type: .info)
        os_log("        - Intermediate certificates: %d", log: logger, type: .info, intermediates.count)
        for (index, cert) in intermediates.enumerated() {
            if let summary = SecCertificateCopySubjectSummary(cert) as String? {
                os_log("          - Intermediate %d: %{public}@", log: logger, type: .info, index + 1, summary)
            }
        }
        
        // kCFStreamSSLCertificates expects: [identity, intermediate1, intermediate2, ...]
        // The identity contains the leaf. Intermediates must follow so the server
        // can walk the chain up to the root it already trusts.
        var certChain: [Any] = [identity]
        certChain.append(contentsOf: intermediates)
        
        var settings: [String: NSObject] = [:]
        settings[kCFStreamSSLCertificates as String] = certChain as NSArray
        
        if let sniHost = sniHostname, !sniHost.isEmpty {
            settings[kCFStreamSSLPeerName as String] = sniHost as NSString
            os_log("        ✓ SNI hostname set: %{public}@", log: logger, type: .info, sniHost)
        }
        
        os_log("        ✓ SSL settings dictionary complete", log: logger, type: .info)
        
        return settings
    }
    
    private func createIdentity(privateKeyAlias: String, clientCertPem: String, useHardwareKey: Bool) throws -> (SecIdentity, [SecCertificate]) {
        os_log("        → createIdentity() called", log: logger, type: .info)
        os_log("          - Loading private key from keychain...", log: logger, type: .info)
        
        guard let privateKey = try loadPrivateKeyFromKeychain(alias: privateKeyAlias) else {
            os_log("          ✗ Private key not found", log: logger, type: .error)
            throw NSError(domain: "MqttModule", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Private key not found: \(privateKeyAlias)"])
        }
        
        os_log("          ✓ Private key loaded", log: logger, type: .info)
        os_log("          - Parsing client certificate PEM...", log: logger, type: .info)
        
        let certificates = try parseCertificatesFromPEM(clientCertPem)
        guard let certificate = certificates.first else {
            os_log("          ✗ No certificates found in PEM", log: logger, type: .error)
            throw NSError(domain: "MqttModule", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Failed to parse client certificate"])
        }
        
        // Everything after the leaf — these are the intermediates that need to
        // travel with the identity so the server can build the full chain.
        let intermediates = Array(certificates.dropFirst())
        os_log("          - Leaf certificate: 1", log: logger, type: .info)
        os_log("          - Intermediate certificates: %d", log: logger, type: .info, intermediates.count)
        
        os_log("          ✓ Client certificate parsed", log: logger, type: .info)
        
        if let summary = SecCertificateCopySubjectSummary(certificate) as String? {
            os_log("          - Client cert subject: %{public}@", log: logger, type: .info, summary)
        }
        
        let certLabel = "MQTT_CLIENT_CERT_\(privateKeyAlias)"
        os_log("          - Certificate label: %{public}@", log: logger, type: .info, certLabel)
        
        os_log("          - Deleting any existing certificate...", log: logger, type: .info)
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: certLabel
        ]
        let deleteStatus = SecItemDelete(deleteQuery as CFDictionary)
        os_log("          - Delete status: %d", log: logger, type: .info, deleteStatus)
        
        os_log("          - Adding certificate to keychain...", log: logger, type: .info)
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: certificate,
            kSecAttrLabel as String: certLabel
        ]
        
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        os_log("          - Add status: %d", log: logger, type: .info, addStatus)
        
        guard addStatus == errSecSuccess || addStatus == errSecDuplicateItem else {
            os_log("          ✗ Failed to add certificate", log: logger, type: .error)
            throw NSError(domain: "MqttModule", code: Int(addStatus),
                        userInfo: [NSLocalizedDescriptionKey: "Failed to add certificate: \(addStatus)"])
        }
        
        os_log("          ✓ Certificate added to keychain", log: logger, type: .info)
        os_log("          - Creating identity...", log: logger, type: .info)
        
        let identityQuery: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecAttrLabel as String: certLabel,
            kSecReturnRef as String: true
        ]
        
        var identityRef: CFTypeRef?
        let identityStatus = SecItemCopyMatching(identityQuery as CFDictionary, &identityRef)
        os_log("          - Identity query status: %d", log: logger, type: .info, identityStatus)
        
        guard identityStatus == errSecSuccess else {
            os_log("          ✗ Failed to create identity", log: logger, type: .error)
            throw NSError(domain: "MqttModule", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Failed to create identity: \(identityStatus)"])
        }
        
        os_log("          ✓ Identity created successfully", log: logger, type: .info)
        return (identityRef as! SecIdentity, intermediates)
    }
    
    private func loadPrivateKeyFromKeychain(alias: String) throws -> SecKey? {
        os_log("            → loadPrivateKeyFromKeychain()", log: logger, type: .info)
        os_log("              - Alias: %{public}@", log: logger, type: .info, alias)
        
        guard let tag = alias.data(using: .utf8) else {
            os_log("              ✗ Invalid alias (not UTF-8)", log: logger, type: .error)
            throw NSError(domain: "MqttModule", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Invalid alias"])
        }
        
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrApplicationTag as String: tag,
            kSecReturnRef as String: true
        ]
        
        os_log("              - Querying keychain...", log: logger, type: .info)
        
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        
        os_log("              - Query status: %d", log: logger, type: .info, status)
        
        guard status == errSecSuccess else {
            os_log("              ✗ Key not found (status=%d)", log: logger, type: .error, status)
            return nil
        }
        
        os_log("              ✓ Private key found", log: logger, type: .info)
        return (item as! SecKey)
    }
    
    private func parseCertificatesFromPEM(_ pem: String) throws -> [SecCertificate] {
        os_log("            → parseCertificatesFromPEM()", log: logger, type: .info)
        
        var certificates: [SecCertificate] = []
        
        let components = pem.components(separatedBy: "-----BEGIN CERTIFICATE-----")
        os_log("              - Found %d potential certificate blocks", log: logger, type: .info, components.count - 1)
        
        for (index, component) in components.enumerated() {
            guard component.contains("-----END CERTIFICATE-----") else {
                continue
            }
            
            guard let endRange = component.range(of: "-----END CERTIFICATE-----") else {
                continue
            }
            
            let base64 = String(component[..<endRange.lowerBound])
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "\n", with: "")
                .replacingOccurrences(of: "\r", with: "")
            
            guard let certData = Data(base64Encoded: base64),
                  let cert = SecCertificateCreateWithData(nil, certData as CFData) else {
                os_log("              ✗ Failed to parse certificate block %d", log: logger, type: .error, index)
                continue
            }
            
            certificates.append(cert)
            
            if let summary = SecCertificateCopySubjectSummary(cert) as String? {
                os_log("              ✓ Parsed certificate: %{public}@", log: logger, type: .info, summary)
            }
        }
        
        os_log("            ✓ Total certificates parsed: %d", log: logger, type: .info, certificates.count)
        
        return certificates
    }
    
}

// ============================================================================
// COCOAMQTT DELEGATE
// ============================================================================

extension MqttModule: CocoaMQTTDelegate {
    
    func mqtt(_ mqtt: CocoaMQTT, didStateChangeTo state: CocoaMQTTConnState) {
        let stateString: String
        switch state {
        case .connecting:
            stateString = "connecting"
        case .connected:
            stateString = "connected"
        case .disconnected:
            stateString = "disconnected"
        @unknown default:
            stateString = "unknown(\(state.rawValue))"
        }
        
        var elapsed = ""
        if let startTime = connectionStartTime {
            let duration = Date().timeIntervalSince(startTime)
            elapsed = String(format: " [+%.3fs]", duration)
        }
        
        os_log("", log: logger, type: .info)
        os_log("╔═══════════════════════════════════════════════════════╗", log: logger, type: .info)
        os_log("║ DELEGATE: didStateChangeTo                           ║", log: logger, type: .info)
        os_log("╠═══════════════════════════════════════════════════════╣", log: logger, type: .info)
        os_log("║ State: %{public}@%{public}@", log: logger, type: .info, stateString, elapsed)
        os_log("╚═══════════════════════════════════════════════════════╝", log: logger, type: .info)
        os_log("", log: logger, type: .info)
    }
    
    func mqtt(_ mqtt: CocoaMQTT, didReceive trust: SecTrust, completionHandler: @escaping (Bool) -> Void) {
        var elapsed = ""
        if let startTime = connectionStartTime {
            let duration = Date().timeIntervalSince(startTime)
            elapsed = String(format: " [+%.3fs]", duration)
        }

        os_log("", log: logger, type: .info)
        os_log("╔═══════════════════════════════════════════════════════╗", log: logger, type: .info)
        os_log("║ DELEGATE: didReceive trust (TLS HANDSHAKE)           ║", log: logger, type: .info)
        os_log("╠═══════════════════════════════════════════════════════╣", log: logger, type: .info)
        os_log("║ Time: %{public}@", log: logger, type: .info, elapsed)
        os_log("╚═══════════════════════════════════════════════════════╝", log: logger, type: .info)
        os_log("", log: logger, type: .info)

        let trusted = evaluateServerTrust(trust, expectedCN: expectedBrokerCN, anchors: trustedRootCerts)

        os_log("", log: logger, type: .info)
        os_log("╔═══════════════════════════════════════════════════════╗", log: logger, type: trusted ? .info : .error)
        os_log("║ TLS VALIDATION: %{public}@", log: logger, type: trusted ? .info : .error, trusted ? "SUCCESS ✓" : "FAILED ✗")
        os_log("╚═══════════════════════════════════════════════════════╝", log: logger, type: trusted ? .info : .error)
        os_log("", log: logger, type: .info)
        completionHandler(trusted)
    }

    /// Validates a server's TLS trust object. The logic lives in `TrustValidator` so it can be
    /// unit-tested without React Native or CocoaMQTT; see `ios/TrustValidation/TrustValidator.swift`.
    internal func evaluateServerTrust(_ trust: SecTrust, expectedCN: String?, anchors: [SecCertificate]) -> Bool {
        return TrustValidator.evaluate(trust: trust, expectedCN: expectedCN, anchors: anchors, log: logger)
    }

    func mqtt(_ mqtt: CocoaMQTT, didConnectAck ack: CocoaMQTTConnAck) {
        var elapsed = ""
        if let startTime = connectionStartTime {
            let duration = Date().timeIntervalSince(startTime)
            elapsed = String(format: " [+%.3fs]", duration)
        }
        
        os_log("", log: logger, type: .info)
        os_log("╔═══════════════════════════════════════════════════════╗", log: logger, type: .info)
        os_log("║ DELEGATE: didConnectAck                               ║", log: logger, type: .info)
        os_log("╠═══════════════════════════════════════════════════════╣", log: logger, type: .info)
        os_log("║ ACK: %{public}@%{public}@", log: logger, type: .info, String(describing: ack), elapsed)
        os_log("╚═══════════════════════════════════════════════════════╝", log: logger, type: .info)
        os_log("", log: logger, type: .info)
        
        if ack == .accept {
            os_log("✓✓✓ MQTT CONNECTION SUCCESSFUL ✓✓✓", log: logger, type: .info)
            os_log("", log: logger, type: .info)
            self.sendEvent(withName: "MqttConnected", body: "Connected")

            // Nil out callbacks before invoking to ensure atomicity
            let successCallback = connectSuccessCallback
            connectSuccessCallback = nil
            connectErrorCallback = nil
            successCallback?(["Connected to \(brokerUrl)"])
        } else {
            let error = "Connection rejected: \(ack)"
            os_log("✗✗✗ MQTT CONNECTION REJECTED ✗✗✗", log: logger, type: .error)
            os_log("Reason: %{public}@", log: logger, type: .error, error)
            os_log("", log: logger, type: .error)

            // Nil out callbacks before invoking to ensure atomicity
            let errorCallback = connectErrorCallback
            connectSuccessCallback = nil
            connectErrorCallback = nil
            errorCallback?([error])
        }
    }
    
    func mqtt(_ mqtt: CocoaMQTT, didPublishMessage message: CocoaMQTTMessage, id: UInt16) {
        os_log("DELEGATE: didPublishMessage (id=%d, topic=%{public}@)", log: logger, type: .info, id, message.topic)
        self.sendEvent(withName: "MqttDeliveryComplete", body: [
            "topic": message.topic,
            "messageId": id
        ])
    }
    
    func mqtt(_ mqtt: CocoaMQTT, didPublishAck id: UInt16) {
        os_log("DELEGATE: didPublishAck (id=%d)", log: logger, type: .info, id)
    }
    
    func mqtt(_ mqtt: CocoaMQTT, didReceiveMessage message: CocoaMQTTMessage, id: UInt16) {
        os_log("DELEGATE: didReceiveMessage (id=%d, topic=%{public}@, size=%d bytes)", log: logger, type: .info, id, message.topic, message.payload.count)

        let payloadData = Data(message.payload)
        let isBinary = self.isBinaryData(topic: message.topic, data: payloadData)

        var eventBody: [String: Any] = [
            "topic": message.topic,
            "qos": message.qos.rawValue
        ]

        if isBinary {
            // Binary data: Base64 encode for transport over bridge
            let payloadBase64 = payloadData.base64EncodedString()
            eventBody["message"] = payloadBase64
            eventBody["isBinary"] = true
            os_log("Received binary message on topic %{public}@ (%d bytes)", log: logger, type: .debug, message.topic, payloadData.count)
        } else {
            // Text data: Send as UTF-8 string (with lossy conversion matching Android behavior)
            // PLATFORM CONSISTENCY: Both iOS and Android now substitute U+FFFD for invalid UTF-8 bytes
            // rather than falling back to binary encoding, ensuring identical output across platforms.
            let messageStr = String(data: payloadData, encoding: .utf8) ??
                            String(decoding: payloadData, as: UTF8.self)  // Lossy: replaces invalid bytes with U+FFFD
            eventBody["message"] = messageStr
            eventBody["isBinary"] = false
            os_log("Received text message on topic %{public}@ (%d bytes)", log: logger, type: .debug, message.topic, payloadData.count)
        }

        self.sendEvent(withName: "MqttMessage", body: eventBody)
    }
    
    func mqtt(_ mqtt: CocoaMQTT, didSubscribeTopics success: NSDictionary, failed: [String]) {
        os_log("DELEGATE: didSubscribeTopics", log: logger, type: .info)
        os_log("  - Success: %{public}@", log: logger, type: .info, String(describing: success))
        os_log("  - Failed: %{public}@", log: logger, type: .info, String(describing: failed))
    }
    
    func mqtt(_ mqtt: CocoaMQTT, didUnsubscribeTopics topics: [String]) {
        os_log("DELEGATE: didUnsubscribeTopics: %{public}@", log: logger, type: .info, topics.joined(separator: ", "))
    }
    
    func mqttDidPing(_ mqtt: CocoaMQTT) {
        os_log("DELEGATE: mqttDidPing", log: logger, type: .debug)
    }
    
    func mqttDidReceivePong(_ mqtt: CocoaMQTT) {
        os_log("DELEGATE: mqttDidReceivePong", log: logger, type: .debug)
    }
    
    func mqttDidDisconnect(_ mqtt: CocoaMQTT, withError err: Error?) {
        var elapsed = ""
        if let startTime = connectionStartTime {
            let duration = Date().timeIntervalSince(startTime)
            elapsed = String(format: " [+%.3fs]", duration)
        }
        
        let errorMsg = err?.localizedDescription ?? "Clean disconnect"
        
        os_log("", log: logger, type: .info)
        os_log("╔═══════════════════════════════════════════════════════╗", log: logger, type: .info)
        os_log("║ DELEGATE: mqttDidDisconnect                           ║", log: logger, type: .info)
        os_log("╠═══════════════════════════════════════════════════════╣", log: logger, type: .info)
        os_log("║ Reason: %{public}@%{public}@", log: logger, type: .info, errorMsg, elapsed)
        
        if let error = err {
            os_log("║ Domain: %{public}@", log: logger, type: .info, (error as NSError).domain)
            os_log("║ Code: %d", log: logger, type: .info, (error as NSError).code)
        }
        
        os_log("╚═══════════════════════════════════════════════════════╝", log: logger, type: .info)
        os_log("", log: logger, type: .info)
        
        self.sendEvent(withName: "MqttDisconnected", body: errorMsg)
        
        if let errorCallback = connectErrorCallback {
            os_log("Connection never established, calling error callback", log: logger, type: .error)
            errorCallback(["Connection failed: \(errorMsg)"])
            connectErrorCallback = nil
            connectSuccessCallback = nil
        }
    }
}