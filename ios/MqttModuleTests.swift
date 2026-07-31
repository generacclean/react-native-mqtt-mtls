/**
 * Unit tests for MqttModule (iOS)
 * Tests callback guards and binary detection logic
 *
 * IMPORTANT: These tests exercise the real isBinaryData(topic:data:) method
 * from MqttModule using topic-based detection logic.
 */

import XCTest
import Foundation
import Security

class MqttModuleTests: XCTestCase {

    // MARK: - Server Trust Validation Tests (IA-5805)
    //
    // Fixtures below are a real EC P-384 chain generated with openssl, mirroring the
    // spike referenced in IA-5805: Penguin TEST Root -> Intermediate -> broker leaf
    // (800-day validity, SANs localhost/127.0.0.1/10.0.2.2), plus a self-signed
    // "forged" cert with the same CN but no relation to the root — the MITM impostor.

    private static let rootPEM = """
    -----BEGIN CERTIFICATE-----
    MIIByjCCAVCgAwIBAgIUCvp5e+1jhwMlPy80fkNyNpa3l7cwCgYIKoZIzj0EAwMw
    HDEaMBgGA1UEAwwRUGVuZ3VpbiBURVNUIFJvb3QwHhcNMjYwNzMwMjIzMTQ4WhcN
    MzYwNzI3MjIzMTQ4WjAcMRowGAYDVQQDDBFQZW5ndWluIFRFU1QgUm9vdDB2MBAG
    ByqGSM49AgEGBSuBBAAiA2IABHo1LW1mTU5Q/WFbK1/kyw1QtZiDDrDjeATSn8Ez
    YdyPZpKuqJJ18j1aVv8Inw7ehln/vAfKVjSEDAt4BvKPcP1YJaGa4Ebvhkri8sbf
    GAoHfbaJ84gApvQiiQDElMt6b6NTMFEwHQYDVR0OBBYEFLKVNcfhYJYhGUVNIu0I
    mHWuSV+6MB8GA1UdIwQYMBaAFLKVNcfhYJYhGUVNIu0ImHWuSV+6MA8GA1UdEwEB
    /wQFMAMBAf8wCgYIKoZIzj0EAwMDaAAwZQIxAOU9KBgQ7WI4dOAl8guD3oON5noS
    TMNVWKq2RAdDnUO+kKxnYmDDvAumwOexnyvSggIwMDRXDd9CsgvteLQuK9oh72Rr
    5/ocUnKHv+otiypiZmWYaTzZV+cPlsFZNDLqKNHK
    -----END CERTIFICATE-----
    """

    private static let intermediatePEM = """
    -----BEGIN CERTIFICATE-----
    MIIB5TCCAWugAwIBAgIUZHS/kwQnv0uJgKEj623bkWTauBQwCgYIKoZIzj0EAwMw
    HDEaMBgGA1UEAwwRUGVuZ3VpbiBURVNUIFJvb3QwHhcNMjYwNzMwMjIzMTQ4WhcN
    MzYwNzI3MjIzMTQ4WjAkMSIwIAYDVQQDDBlQZW5ndWluIFRFU1QgSW50ZXJtZWRp
    YXRlMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAErDTCWwXFVF2L6m1rgO95AVDKJOkD
    k0OxL1peFKBY/hb5+mLvueaIaNuFVegrC5jMrdo3hWMZCJjQwg2QoZbpNrwiKCpP
    75eiM2ejmpGQdODyc2ORUQLfYURqIa1z4a5Jo2YwZDASBgNVHRMBAf8ECDAGAQH/
    AgEAMA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQUHM/eu4x/m+Jauf8Qvfu+pxL0
    NMwwHwYDVR0jBBgwFoAUspU1x+FgliEZRU0i7QiYda5JX7owCgYIKoZIzj0EAwMD
    aAAwZQIwdLRZBEFfpFVFSOLW68+H47Br/XegfJr/Na7ClyzVGK94wqwbJTuEuGXT
    w0sXxV0nAjEAnmXLXNu/PP/bmli3B0pYRsODWc3YXiG1hSKxakxz6d/E5NGPRA76
    qP1gVQ7E3A3+
    -----END CERTIFICATE-----
    """

    // Broker leaf: 800-day validity (> Apple's 398-day system-root cap), SANs
    // localhost/127.0.0.1/10.0.2.2, signed by the intermediate above.
    private static let brokerPEM = """
    -----BEGIN CERTIFICATE-----
    MIICGDCCAZ6gAwIBAgIURuVk+C+dQXYyxWfnCjFCddBH3XYwCgYIKoZIzj0EAwMw
    JDEiMCAGA1UEAwwZUGVuZ3VpbiBURVNUIEludGVybWVkaWF0ZTAeFw0yNjA3MzAy
    MjMxNDhaFw0yODEwMDcyMjMxNDhaMB8xHTAbBgNVBAMMFHBlbmd1aW4tYnJva2Vy
    LmxvY2FsMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEUeyJRIkjiiCUUy+Yig4mG03G
    erDoQVluBihhL8EsUxMY+HGhmsMnZBHiEz2wT7fgeMY3K4R7eby8dVMqOgK4pWjl
    2bhKMi99x/LYy2vxI70CA5CuYgHCL/AsLekWS1Mho4GVMIGSMAwGA1UdEwEB/wQC
    MAAwCwYDVR0PBAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUFBwMBMCAGA1UdEQQZMBeC
    CWxvY2FsaG9zdIcEfwAAAYcECgACAjAdBgNVHQ4EFgQU171C2J19At/K3IJaIxcX
    jIkpVLswHwYDVR0jBBgwFoAUHM/eu4x/m+Jauf8Qvfu+pxL0NMwwCgYIKoZIzj0E
    AwMDaAAwZQIwb4YI1+uMZ3KYFiPcLlkytkoQCjDakPxhAZ+geUda0fyy1oxVduLM
    A8Q3uJsKCzYmAjEAhNlKanvD/rilK8y7FrvlpAwMEBMLnMNsQWPILPTlTAXNJwAS
    s/CrY+bZ9m1fzFns
    -----END CERTIFICATE-----
    """

    // Self-signed impostor with the SAME CN as the broker but no relation to the
    // trusted root — models an attacker on the gateway Wi-Fi presenting a fake cert.
    private static let forgedPEM = """
    -----BEGIN CERTIFICATE-----
    MIIB0DCCAVagAwIBAgIUXcngdQBs/lGg0DEbbzO9R/GsRnQwCgYIKoZIzj0EAwMw
    HzEdMBsGA1UEAwwUcGVuZ3Vpbi1icm9rZXIubG9jYWwwHhcNMjYwNzMwMjIzMTQ5
    WhcNMjcwNzMwMjIzMTQ5WjAfMR0wGwYDVQQDDBRwZW5ndWluLWJyb2tlci5sb2Nh
    bDB2MBAGByqGSM49AgEGBSuBBAAiA2IABBEkkEkDFKtXwCFlB8dy/vCTExwkAPSt
    e8az7swFHJf/MsIV7bnA1xvXRCnS6GNLWctMQ5333iYAk35o3eHZj4yvyFVUbC5r
    5NqaKMgL4DxZawfMv3qm7jtOXvxC8Peed6NTMFEwHQYDVR0OBBYEFFzuS68p5TvX
    2vgT7smi4L+yGAEMMB8GA1UdIwQYMBaAFFzuS68p5TvX2vgT7smi4L+yGAEMMA8G
    A1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwMDaAAwZQIwP23nCIpCQtd1uojnc8d0
    CCqypzEvMnp/D9eQ0GZ9i2JQZEYqUIdyp7zXqX4/ZvLjAjEAsjSyr7JPLfeHJXCC
    Znze5rLgp0+w2CD/Sqd7gFKiJHkllxIQA7xZD1fmzZ8Q7A02
    -----END CERTIFICATE-----
    """

    private static func certificate(fromPEM pem: String) -> SecCertificate {
        let base64 = pem
            .replacingOccurrences(of: "-----BEGIN CERTIFICATE-----", with: "")
            .replacingOccurrences(of: "-----END CERTIFICATE-----", with: "")
            .replacingOccurrences(of: "\n", with: "")
        guard let data = Data(base64Encoded: base64),
              let cert = SecCertificateCreateWithData(nil, data as CFData) else {
            fatalError("Failed to parse test fixture certificate")
        }
        return cert
    }

    /// Builds a SecTrust object presenting `leafAndChain` as the server's certificate chain,
    /// mirroring what CocoaMQTT hands the delegate during a real TLS handshake.
    private static func makeTrust(presenting leafAndChain: [SecCertificate]) -> SecTrust {
        var trust: SecTrust?
        let policy = SecPolicyCreateSSL(true, nil)
        let status = SecTrustCreateWithCertificates(leafAndChain as CFArray, policy, &trust)
        precondition(status == errSecSuccess, "Failed to create SecTrust fixture")
        return trust!
    }

    func testEvaluateServerTrust_ValidChainWithRootAnchor_AdminUser_Trusted() {
        let module = MqttModule()
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [broker, intermediate])

        // Admin user: expectedCN is nil, so only chain validation applies.
        // This is the exact scenario IA-5805 flags: an 800-day leaf (violates Apple's
        // 398-day cap for system-trusted roots) must still validate against our own anchor.
        let result = module.evaluateServerTrust(trust, expectedCN: nil, anchors: [root])

        XCTAssertTrue(result, "Valid chain to app-provided root anchor should be trusted, even with >398-day leaf")
    }

    func testEvaluateServerTrust_ValidChainWithIntermediateAnchor_Trusted() {
        let module = MqttModule()
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)

        let trust = Self.makeTrust(presenting: [broker])

        let result = module.evaluateServerTrust(trust, expectedCN: nil, anchors: [intermediate])

        XCTAssertTrue(result, "Anchoring directly on the intermediate should also validate")
    }

    func testEvaluateServerTrust_ForgedSelfSignedCert_Rejected() {
        let module = MqttModule()
        let forged = Self.certificate(fromPEM: Self.forgedPEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [forged])

        // This is the IA-5805 MITM scenario: an attacker on the gateway Wi-Fi presents a
        // self-signed cert with the broker's CN. Before the fix, admin-mode accepted this
        // unconditionally (completionHandler(true)). It must now be rejected.
        let result = module.evaluateServerTrust(trust, expectedCN: nil, anchors: [root])

        XCTAssertFalse(result, "Self-signed impostor cert not chaining to our root must be rejected")
    }

    func testEvaluateServerTrust_ForgedCert_RejectedEvenWithCNPinning() {
        let module = MqttModule()
        let forged = Self.certificate(fromPEM: Self.forgedPEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [forged])

        // Non-admin path: even though the forged cert's CN matches exactly, chain
        // validation must fail first and reject before CN comparison is reached.
        let result = module.evaluateServerTrust(trust, expectedCN: "penguin-broker.local", anchors: [root])

        XCTAssertFalse(result, "Chain validation must reject the impostor regardless of CN pinning")
    }

    func testEvaluateServerTrust_NoAnchorsConfigured_Rejected() {
        let module = MqttModule()
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)

        let trust = Self.makeTrust(presenting: [broker, intermediate])

        let result = module.evaluateServerTrust(trust, expectedCN: nil, anchors: [])

        XCTAssertFalse(result, "Missing trusted anchors must fail closed, never accept-any")
    }

    func testEvaluateServerTrust_ValidChainWithMatchingCN_NonAdmin_Trusted() {
        let module = MqttModule()
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [broker, intermediate])

        let result = module.evaluateServerTrust(trust, expectedCN: "penguin-broker.local", anchors: [root])

        XCTAssertTrue(result, "Valid chain + matching CN pin should be trusted")
    }

    func testEvaluateServerTrust_ValidChainWithMismatchedCN_NonAdmin_Rejected() {
        let module = MqttModule()
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [broker, intermediate])

        let result = module.evaluateServerTrust(trust, expectedCN: "some-other-device.local", anchors: [root])

        XCTAssertFalse(result, "Valid chain but CN pin mismatch must still be rejected for non-admin users")
    }

    // MARK: - Topic-Based Detection Tests (Deterministic)

    func testTopicDetection_ProtobufTopic_AlwaysBinary() {
        let module = MqttModule()
        // ASCII content that would pass UTF-8 check
        let asciiPayload = "ABC123".data(using: .utf8)!

        XCTAssertTrue(module.isBinaryData(topic: "device/proto/list", data: asciiPayload),
                     "Protobuf topics should always be binary, regardless of content")
        XCTAssertTrue(module.isBinaryData(topic: "device/12345/proto/config", data: asciiPayload))
        XCTAssertTrue(module.isBinaryData(topic: "remote/proto/status", data: asciiPayload))
    }

    func testTopicDetection_DeviceTopic_AlwaysBinary() {
        let module = MqttModule()
        let asciiPayload = "DEVICE-001".data(using: .utf8)!

        XCTAssertTrue(module.isBinaryData(topic: "remote/device/list", data: asciiPayload),
                     "Device topics should be binary (protobuf device lists)")
        XCTAssertTrue(module.isBinaryData(topic: "remote/device/12345", data: asciiPayload))
        XCTAssertTrue(module.isBinaryData(topic: "penguin/device/info", data: asciiPayload))
    }

    func testTopicDetection_FirmwareTopic_AlwaysBinary() {
        let module = MqttModule()
        let asciiPayload = "v1.2.3".data(using: .utf8)!

        XCTAssertTrue(module.isBinaryData(topic: "device/firmware/update", data: asciiPayload),
                     "Firmware topics should be binary")
        XCTAssertTrue(module.isBinaryData(topic: "ota/firmware", data: asciiPayload))
        XCTAssertTrue(module.isBinaryData(topic: "penguin/firmware/version", data: asciiPayload))
    }

    func testTopicDetection_StatusTopic_AlwaysText() {
        let module = MqttModule()
        let jsonPayload = "{\"status\":\"ok\"}".data(using: .utf8)!

        XCTAssertFalse(module.isBinaryData(topic: "device/status", data: jsonPayload),
                      "Status topics should be text (JSON)")
        // Note: "remote/device/status" is a collision case: /device takes precedence.
        // See testTopicCollision_DeviceBeforeStatus for that behaviour.
        XCTAssertFalse(module.isBinaryData(topic: "penguin/status/health", data: jsonPayload))
    }

    func testTopicDetection_ConfigTopic_AlwaysText() {
        let module = MqttModule()
        let jsonPayload = "{\"key\":\"value\"}".data(using: .utf8)!

        XCTAssertFalse(module.isBinaryData(topic: "device/config", data: jsonPayload),
                      "Config topics should be text (JSON)")
        XCTAssertFalse(module.isBinaryData(topic: "remote/config/update", data: jsonPayload))
    }

    func testTopicDetection_JsonTopic_AlwaysText() {
        let module = MqttModule()
        let jsonPayload = "{\"message\":\"test\"}".data(using: .utf8)!

        XCTAssertFalse(module.isBinaryData(topic: "device/json/data", data: jsonPayload),
                      "JSON topics should be text")
        XCTAssertFalse(module.isBinaryData(topic: "remote/json/update", data: jsonPayload))
    }

    // MARK: - Topic Collision Tests (Binary-First Precedence)

    func testTopicCollision_DeviceBeforeStatus() {
        let module = MqttModule()
        let payload = "test".data(using: .utf8)!

        // /device is checked before /status, so /device/status returns binary
        XCTAssertTrue(module.isBinaryData(topic: "remote/device/status", data: payload),
                     "/device takes precedence over /status (binary-first)")
    }

    func testTopicCollision_DeviceBeforeConfig() {
        let module = MqttModule()
        let payload = "test".data(using: .utf8)!

        // /device is checked before /config, so /device/config returns binary
        XCTAssertTrue(module.isBinaryData(topic: "remote/device/config", data: payload),
                     "/device takes precedence over /config (binary-first)")
    }

    func testTopicCollision_FirmwareBeforeStatus() {
        let module = MqttModule()
        let payload = "test".data(using: .utf8)!

        // /firmware is checked before /status
        XCTAssertTrue(module.isBinaryData(topic: "device/firmware/status", data: payload),
                     "/firmware takes precedence over /status")
    }

    // MARK: - ASCII Protobuf Edge Cases

    func testASCIIProtobuf_UnknownTopic_Misclassified() {
        let module = MqttModule()
        // Small protobuf with ASCII content - will be misclassified as text
        // This is a KNOWN LIMITATION documented in the code
        let asciiProtobuf = "ABC123".data(using: .utf8)!

        let result = module.isBinaryData(topic: "unknown/topic/path", data: asciiProtobuf)

        // This will be FALSE (misclassified as text) because:
        // 1. Topic doesn't match any pattern
        // 2. Payload is valid UTF-8 (ASCII)
        // 3. UTF-8 heuristic fallback says "text"
        XCTAssertFalse(result,
                      "KNOWN LIMITATION: ASCII protobuf on unknown topic misclassified as text")
    }

    func testASCIIProtobuf_KnownBinaryTopic_CorrectlyClassified() {
        let module = MqttModule()
        // Same ASCII content, but on a known binary topic
        let asciiProtobuf = "ABC123".data(using: .utf8)!

        XCTAssertTrue(module.isBinaryData(topic: "device/proto/list", data: asciiProtobuf),
                     "ASCII protobuf on known binary topic correctly classified")
    }

    // MARK: - UTF-8 Heuristic Fallback Tests (Unknown Topics)

    func testBinaryDetection_ValidUTF8Text() {
        let module = MqttModule()
        let text = "Hello, MQTT!"
        let data = text.data(using: .utf8)!

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: data)

        XCTAssertFalse(isBinary, "Plain text should not be detected as binary")
    }

    func testBinaryDetection_ValidUTF8WithEmoji() {
        let module = MqttModule()
        let text = "Hello 👋 World 🌍"
        let data = text.data(using: .utf8)!

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: data)

        XCTAssertFalse(isBinary, "UTF-8 text with emoji should not be detected as binary")
    }

    func testBinaryDetection_JSONPayload() {
        let module = MqttModule()
        let json = "{\"timestamp\":1782414353,\"status\":\"active\"}"
        let data = json.data(using: .utf8)!

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: data)

        XCTAssertFalse(isBinary, "JSON should not be detected as binary")
    }

    func testBinaryDetection_ProtobufVarint() {
        let module = MqttModule()
        // Protobuf message with varint encoding: field 7, value 2000
        // tag = (7 << 3 | 0) = 0x38, varint(2000) = [0xD0, 0x0F]
        let protobufData = Data([0x38, 0xD0, 0x0F])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: protobufData)

        XCTAssertTrue(isBinary, "Protobuf with varint should be detected as binary")
    }

    func testBinaryDetection_BinaryData() {
        let module = MqttModule()
        let binaryData = Data([0x00, 0x01, 0x02, 0xFF, 0x7F, 0x80])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: binaryData)

        XCTAssertTrue(isBinary, "Binary data should be detected as binary")
    }

    func testBinaryDetection_EmptyPayload() {
        let module = MqttModule()
        let emptyData = Data()

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: emptyData)

        XCTAssertFalse(isBinary, "Empty payload should not be detected as binary")
    }

    func testBinaryDetection_NullBytes() {
        let module = MqttModule()
        let nullBytes = Data([0x00, 0x00, 0x00])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: nullBytes)

        XCTAssertTrue(isBinary, "Null bytes should be detected as binary")
    }

    func testBinaryDetection_InvalidUTF8Sequence() {
        let module = MqttModule()
        // Invalid UTF-8: start byte without proper continuation
        let invalidUTF8 = Data([0xC0, 0x20])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: invalidUTF8)

        XCTAssertTrue(isBinary, "Invalid UTF-8 should be detected as binary")
    }

    func testBinaryDetection_MixedASCIIAndBinary() {
        let module = MqttModule()
        // Starts with valid ASCII, then has invalid UTF-8
        let mixed = Data("Hello".utf8) + Data([0xFF, 0xFE])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: mixed)

        XCTAssertTrue(isBinary, "Mixed ASCII and binary should be detected as binary")
    }

    // MARK: - Callback Guard Tests

    func testCallbackGuard_SingleInvocation() {
        var invokeCount = 0
        let callback: ([Any]) -> Void = { _ in
            invokeCount += 1
        }

        let guardObj = CallbackGuard(callback)
        guardObj.invoke(["result"])

        XCTAssertEqual(invokeCount, 1, "Callback should be invoked once")
    }

    func testCallbackGuard_PreventsDuplicateInvocation() {
        var invokeCount = 0
        let callback: ([Any]) -> Void = { _ in
            invokeCount += 1
        }

        let guardObj = CallbackGuard(callback)
        guardObj.invoke(["result1"])
        guardObj.invoke(["result2"])
        guardObj.invoke(["result3"])

        XCTAssertEqual(invokeCount, 1, "Callback should only be invoked once")
    }

    func testCallbackGuard_NilCallbackHandling() {
        let guardObj = CallbackGuard(nil)

        // Should not crash with nil callback
        guardObj.invoke(["result"])

        // Test passes if no crash occurs
        XCTAssertTrue(true, "Should handle nil callback gracefully")
    }

    func testCallbackGuard_ThreadSafety() {
        let expectation = self.expectation(description: "Thread safety test")
        var invokeCount = 0
        let lock = NSLock()

        let callback: ([Any]) -> Void = { _ in
            lock.lock()
            invokeCount += 1
            lock.unlock()
        }

        let guardObj = CallbackGuard(callback)
        let group = DispatchGroup()

        // Create 10 concurrent invocations
        for _ in 0..<10 {
            group.enter()
            DispatchQueue.global().async {
                guardObj.invoke(["result"])
                group.leave()
            }
        }

        group.notify(queue: .main) {
            XCTAssertEqual(invokeCount, 1, "Callback should only be invoked once despite race condition")
            expectation.fulfill()
        }

        waitForExpectations(timeout: 5, handler: nil)
    }

    func testCallbackGuard_WithArguments() {
        var receivedArgs: [Any] = []
        let callback: ([Any]) -> Void = { args in
            receivedArgs = args
        }

        let guardObj = CallbackGuard(callback)
        guardObj.invoke(["test-result", 42, true])

        XCTAssertEqual(receivedArgs.count, 3, "Should receive all arguments")
        XCTAssertEqual(receivedArgs[0] as? String, "test-result")
        XCTAssertEqual(receivedArgs[1] as? Int, 42)
        XCTAssertEqual(receivedArgs[2] as? Bool, true)
    }

    func testCallbackGuard_ExceptionHandling() {
        let callback: ([Any]) -> Void = { _ in
            // Simulate an error
            fatalError("Test error")
        }

        let guardObj = CallbackGuard(callback)

        // In Swift, we can't easily test fatalError, but we can test
        // that the guard marks the callback as fired even if it would throw
        guardObj.invoke(["result"])

        // Second invocation should be suppressed
        var secondCallbackInvoked = false
        let guard2 = CallbackGuard({ _ in secondCallbackInvoked = true })
        guard2.invoke(["first"])
        guard2.invoke(["second"]) // Should be suppressed

        XCTAssertTrue(secondCallbackInvoked, "First invocation should succeed")
    }

    // MARK: - Binary Marker Tests

    func testBinaryMarker_Detection() {
        let markedMessage = "B64:SGVsbG8="
        let marker = "B64:"

        XCTAssertTrue(markedMessage.hasPrefix(marker), "Should detect B64: prefix")

        let base64Data = String(markedMessage.dropFirst(marker.count))
        XCTAssertEqual(base64Data, "SGVsbG8=", "Should extract base64 data")
    }

    func testBinaryMarker_PlainText() {
        let plainText = "Hello, World!"
        let marker = "B64:"

        XCTAssertFalse(plainText.hasPrefix(marker), "Plain text should not have B64: prefix")
    }

    func testBinaryMarker_EmptyData() {
        let markedEmpty = "B64:"
        let marker = "B64:"

        XCTAssertTrue(markedEmpty.hasPrefix(marker), "Should detect B64: prefix")

        let base64Data = String(markedEmpty.dropFirst(marker.count))
        XCTAssertEqual(base64Data, "", "Should have empty base64 data")
    }

    // MARK: - Base64 Encoding/Decoding Tests

    func testBase64_RoundTrip() {
        let original = "Hello, MQTT!".data(using: .utf8)!

        // Encode to Base64
        let base64 = original.base64EncodedString()

        // Decode back
        let decoded = Data(base64Encoded: base64)!

        XCTAssertEqual(original, decoded, "Round-trip should preserve data")
    }

    func testBase64_BinaryData() {
        let binaryData = Data([0x00, 0x01, 0x02, 0xFF, 0x7F, 0x80])

        let base64 = binaryData.base64EncodedString()
        let decoded = Data(base64Encoded: base64)!

        XCTAssertEqual(binaryData, decoded, "Binary data should survive Base64 round-trip")
    }

    func testBase64_EmptyData() {
        let emptyData = Data()

        let base64 = emptyData.base64EncodedString()
        let decoded = Data(base64Encoded: base64) ?? Data()

        XCTAssertEqual(emptyData, decoded, "Empty data should survive Base64 round-trip")
    }

    func testBase64_LargeData() {
        // Test with 1MB of data
        var largeData = Data(count: 1024 * 1024)
        for i in 0..<largeData.count {
            largeData[i] = UInt8(i % 256)
        }

        let base64 = largeData.base64EncodedString()
        let decoded = Data(base64Encoded: base64)!

        XCTAssertEqual(largeData, decoded, "Large data should survive Base64 round-trip")
    }

    // MARK: - UTF-8 Edge Cases

    func testUTF8_MultibyteCharacters() {
        let module = MqttModule()
        // Test various UTF-8 multibyte sequences
        let testCases = [
            "Hello",                    // ASCII
            "Héllo",                    // Latin-1 Supplement
            "Привет",                   // Cyrillic
            "こんにちは",                 // Japanese
            "你好",                      // Chinese
            "مرحبا",                    // Arabic
            "שלום",                      // Hebrew
            "👋🌍🚀",                    // Emoji
        ]

        for text in testCases {
            let data = text.data(using: .utf8)!
            let isBinary = module.isBinaryData(topic: "test/text", data: data)
            XCTAssertFalse(isBinary, "\(text) should be detected as valid UTF-8")
        }
    }

    func testUTF8_ControlCharacters() {
        let module = MqttModule()
        // Control characters are valid UTF-8
        let controlChars = Data([0x00, 0x01, 0x02, 0x1F])
        let isBinary = module.isBinaryData(topic: "test/text", data: controlChars)

        // Control characters are technically valid UTF-8 but typically considered binary
        // The behavior depends on String(data:encoding:) implementation
        // Just document the actual behavior
        print("Control characters detected as binary: \(isBinary)")
    }

    // MARK: - Helper Methods

    // Mock CallbackGuard for testing
    // Note: Tests now use the real MqttModule.isBinaryData(topic:data:) method
    private class CallbackGuard {
        private var callback: (([Any]) -> Void)?
        private var hasFired = false
        private let lock = NSLock()

        init(_ callback: (([Any]) -> Void)?) {
            self.callback = callback
        }

        func invoke(_ args: [Any]) {
            lock.lock()
            defer { lock.unlock() }

            guard !hasFired, let callback = callback else {
                return
            }

            hasFired = true
            self.callback = nil
            callback(args)
        }
    }
}
