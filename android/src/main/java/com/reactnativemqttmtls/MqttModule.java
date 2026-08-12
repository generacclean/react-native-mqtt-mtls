package com.reactnativemqttmtls;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import info.mqtt.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

public class MqttModule extends ReactContextBaseJavaModule {
    private static final String TAG = "MqttModule";
    private static final String SOFTWARE_KEYSTORE_FILE = "software_keys.p12";
    /**
     * Prefix marker to distinguish intentional Base64-encoded binary messages
     * from plain text that happens to be valid Base64 (e.g., JSON strings).
     * Must match the prefix in JavaScript layer (MqttManager.ts).
     */
    private static final String BINARY_MARKER = "B64:";

    // Keep a direct reference to our full BouncyCastle provider instance
    // to avoid getting the system's stripped-down BC provider
    private static final Provider FULL_BC_PROVIDER = new BouncyCastleProvider();

    // Thread-safe provider initialization
    private static volatile boolean providerInitialized = false;
    private static final Object providerLock = new Object();

    private final ReactApplicationContext reactContext;
    private MqttAndroidClient client;
    private volatile boolean isAutoReconnectEnabled = false;

    /**
     * Detects whether a message is binary based on topic pattern and payload inspection.
     *
     * CRITICAL: UTF-8 heuristic alone is insufficient for protobuf detection because small
     * protobufs with ASCII serial numbers and low field tags can be valid UTF-8, causing
     * misclassification that leads to parse failures in downstream handlers.
     *
     * Detection strategy:
     * 1. Topic-based (deterministic): Known binary topics are always treated as binary
     * 2. Content-based (fallback): UTF-8 validity check for unknown topics
     *
     * NOTE: This is intentionally asymmetric with the publish path.
     * - PUBLISH (JS → Native): Uses B64: prefix marker
     * - RECEIVE (Native → JS): Uses topic patterns + UTF-8 heuristic
     *
     * This allows us to handle messages from ANY publisher, not just our app.
     * External publishers won't use our B64: convention.
     *
     * IMPORTANT: Keep topic patterns in sync with iOS/MqttModule.swift and test files.
     *
     * @param topic The MQTT topic (used for pattern matching)
     * @param payload The message payload bytes
     * @return true if message should be treated as binary, false for text
     */
    private boolean isBinaryData(String topic, byte[] payload) {
        // DETERMINISTIC: Topic-based detection for known binary message patterns
        // These topics carry protobuf or firmware data and must always be binary
        if (topic != null) {
            // Protobuf topics (device lists, RMA swap, hardware assembly, etc.)
            if (topic.contains("/proto/") ||
                topic.contains("/device") ||
                topic.contains("/rma") ||
                topic.contains("/assembly") ||
                topic.contains("/installed")) {
                return true;
            }

            // Firmware update topics
            if (topic.contains("/firmware") ||
                topic.contains("/ota") ||
                topic.contains("/upload")) {
                return true;
            }

            // Text topics (JSON status, configuration, commands)
            if (topic.contains("/status") ||
                topic.contains("/config") ||
                topic.contains("/command") ||
                topic.contains("/json")) {
                return false;
            }
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
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(ByteBuffer.wrap(payload));
            return false;  // Successfully decoded as UTF-8 → text
        } catch (CharacterCodingException e) {
            return true;  // Not valid UTF-8 → binary
        }
    }

    public MqttModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        setupBouncyCastle();

        // Clean up any stale connections from previous app instances
        Log.d(TAG, "MqttModule initialized - performing initial cleanup");
        cleanupConnection();
    }

    private void setupBouncyCastle() {
        // Fast path - if already initialized, return immediately
        if (providerInitialized) {
            return;
        }

        // Slow path - synchronize and initialize
        synchronized (providerLock) {
            // Double-check after acquiring lock
            if (providerInitialized) {
                return;
            }

            try {
                // Remove the system's stripped BC provider if it exists
                Provider systemBcProvider = Security.getProvider("BC");
                if (systemBcProvider != null && systemBcProvider.getClass().getName().startsWith("com.android.org.bouncycastle")) {
                    Log.d(TAG, "Found Android system BC provider (stripped): " + systemBcProvider.getClass().getName());
                    Log.d(TAG, "Removing system BC provider to avoid conflicts");
                    Security.removeProvider("BC");
                }

                // Register our full BouncyCastle provider at position 1 (highest priority)
                Security.insertProviderAt(FULL_BC_PROVIDER, 1);
                Log.d(TAG, "BouncyCastle provider initialized at position 1");
                Log.d(TAG, "BC Provider version: " + FULL_BC_PROVIDER.getVersion());

                // Mark as initialized
                providerInitialized = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to register BouncyCastle provider", e);
            }
        }
    }

    /**
     * Safely invoke a React Native Callback exactly once, even if called multiple times.
     * Prevents native crash (SIGABRT) from React Native bridge's single-fire invariant violation.
     *
     * @param callback The callback to invoke
     * @param fired AtomicBoolean guard to ensure single invocation
     * @param args Arguments to pass to the callback
     */
    private void safeInvoke(Callback callback, AtomicBoolean fired, Object... args) {
        if (callback == null) {
            return;
        }
        if (fired.compareAndSet(false, true)) {
            try {
                callback.invoke(args);
            } catch (Exception e) {
                Log.e(TAG, "Callback invoke error", e);
            }
        } else {
            Log.w(TAG, "Suppressed duplicate callback invocation");
        }
    }

    /**
     * Centralized cleanup method to properly close and null out the MQTT client
     */
    private void cleanupConnection() {
        Log.d(TAG, "Cleaning up MQTT connection state...");

        if (client != null) {
            try {
                if (client.isConnected()) {
                    Log.d(TAG, "  - Client is connected, disconnecting...");
                    try {
                        client.disconnect(0);
                    } catch (Exception e) {
                        Log.w(TAG, "  - Disconnect error (non-critical): " + e.getMessage());
                    }
                }

                Log.d(TAG, "  - Closing client...");
                client.close();
            } catch (Exception e) {
                Log.w(TAG, "  - Error during cleanup (non-critical): " + e.getMessage());
            } finally {
                client = null;
                Log.d(TAG, "✓ Cleanup complete");
            }
        } else {
            Log.d(TAG, "  - No active client to clean up");
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "MqttModule";
    }

    // ============================================================================
    // CLEANUP METHOD (exposed to React Native)
    // ============================================================================

    @ReactMethod
    public void cleanup(Callback successCallback, Callback errorCallback) {
        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "EXPLICIT CLEANUP REQUESTED");
        Log.d(TAG, "═══════════════════════════════════════");

        try {
            cleanupConnection();
            if (successCallback != null) {
                successCallback.invoke("Cleanup successful");
            }
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error", e);
            if (errorCallback != null) {
                errorCallback.invoke("Cleanup error: " + e.getMessage());
            }
        }
    }

    // ============================================================================
    // CUSTOM TRUSTMANAGER - Server certificate validation with optional CN check
    // ============================================================================

    private static class CustomTrustManager implements X509TrustManager {
        private final X509Certificate[] acceptedIssuers;
        private final String expectedBrokerCN;
        private final String expectedSniHost;

        public CustomTrustManager(KeyStore trustStore, String expectedBrokerCN, String expectedSniHost) throws Exception {
            this.expectedBrokerCN = expectedBrokerCN;
            this.expectedSniHost = expectedSniHost;

            List<X509Certificate> certs = new ArrayList<>();
            Enumeration<String> aliases = trustStore.aliases();

            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                java.security.cert.Certificate cert = trustStore.getCertificate(alias);
                if (cert instanceof X509Certificate) {
                    certs.add((X509Certificate) cert);
                }
            }

            this.acceptedIssuers = certs.toArray(new X509Certificate[0]);
            Log.d(TAG, "CustomTrustManager initialized with " + acceptedIssuers.length + " CA(s)");

            if (expectedBrokerCN != null && !expectedBrokerCN.isEmpty()) {
                Log.d(TAG, "Expected broker CN: " + expectedBrokerCN);
            } else {
                Log.d(TAG, "No expected broker CN configured — CN pin skipped");
            }

            if (expectedSniHost != null && !expectedSniHost.isEmpty()) {
                Log.d(TAG, "Expected SNI host (SAN pin): " + expectedSniHost);
            } else {
                Log.d(TAG, "No expected SNI host configured — SAN pin skipped");
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Not needed for client
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Server certificate chain is empty");
            }

            X509Certificate serverCert = chain[0];

            // Chain validation always runs, admin or not — isAdminUser only ever skips the
            // CN/SNI pins below.
            validateCertificateChain(chain);
            Log.d(TAG, "✓ Server certificate chain validated via CertPathValidator (PKIX)");

            // CN validation only for non-admin users (expectedBrokerCN will be null for admin)
            if (expectedBrokerCN != null && !expectedBrokerCN.isEmpty()) {
                String brokerCN = extractCN(serverCert);
                Log.d(TAG, "Broker certificate CN: " + brokerCN);

                if (!expectedBrokerCN.equals(brokerCN)) {
                    Log.e(TAG, "CN MISMATCH! Expected: " + expectedBrokerCN + ", Got: " + brokerCN);
                    throw new CertificateException(
                            "Broker CN mismatch. Expected: " + expectedBrokerCN + ", Got: " + brokerCN);
                }
                Log.d(TAG, "✓ Broker CN validated: " + brokerCN);
            }

            // SNI host/SAN validation only for non-admin users (expectedSniHost will be null for admin)
            if (expectedSniHost != null && !expectedSniHost.isEmpty()) {
                if (!certificateMatchesHost(serverCert, expectedSniHost)) {
                    Log.e(TAG, "SAN MISMATCH! No SAN entry on server certificate matches SNI host: " + expectedSniHost);
                    throw new CertificateException(
                            "Broker certificate SAN does not match SNI host: " + expectedSniHost);
                }
                Log.d(TAG, "✓ Broker certificate SAN matched SNI host: " + expectedSniHost);
            }
        }

        /**
         * Validates the server's certificate chain against the configured trust anchors using
         * the platform PKIX path validator (expiry, path-length, basic-constraints, and
         * signature checks) instead of hand-rolled per-issuer signature loops.
         */
        private void validateCertificateChain(X509Certificate[] chain) throws CertificateException {
            try {
                Set<TrustAnchor> anchors = new HashSet<>();
                for (X509Certificate ca : acceptedIssuers) {
                    anchors.add(new TrustAnchor(ca, null));
                }

                if (anchors.isEmpty()) {
                    throw new CertificateException("No trusted CA certificates configured");
                }

                // PKIX requires the path to end just below a trust anchor, not include it —
                // drop any presented cert that IS one of our configured anchors.
                List<X509Certificate> pathCerts = new ArrayList<>();
                for (X509Certificate cert : chain) {
                    boolean isAnchor = false;
                    for (X509Certificate ca : acceptedIssuers) {
                        if (cert.equals(ca)) {
                            isAnchor = true;
                            break;
                        }
                    }
                    if (!isAnchor) {
                        pathCerts.add(cert);
                    }
                }

                if (pathCerts.isEmpty()) {
                    throw new CertificateException("Server sent no leaf certificate to validate");
                }

                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                CertPath certPath = cf.generateCertPath(pathCerts);

                PKIXParameters params = new PKIXParameters(anchors);
                // No CRL/OCSP infrastructure for the Penguin gateway CA on a private network;
                // expiry, path-length, and basic-constraints checks still run.
                params.setRevocationEnabled(false);

                CertPathValidator validator = CertPathValidator.getInstance("PKIX");
                validator.validate(certPath, params);
            } catch (CertificateException e) {
                throw e;
            } catch (Exception e) {
                Log.e(TAG, "Certificate chain validation failed", e);
                throw new CertificateException("Server certificate chain validation failed: " + e.getMessage(), e);
            }
        }

        /**
         * Checks whether any DNS or IP subjectAltName entry on the certificate matches the
         * expected SNI host exactly (no wildcard matching — this is a private IoT trust boundary).
         */
        private boolean certificateMatchesHost(X509Certificate cert, String sniHost) {
            try {
                Collection<List<?>> sans = cert.getSubjectAlternativeNames();
                if (sans == null) {
                    return false;
                }
                for (List<?> san : sans) {
                    Integer type = (Integer) san.get(0);
                    Object value = san.get(1);
                    // Per the X509Certificate#getSubjectAlternativeNames javadoc, GeneralName
                    // type 2 (dNSName) and type 7 (iPAddress) are both returned as Strings —
                    // IPv4 in dotted-quad notation, IPv6 as colon-separated hex groups.
                    // Only otherName/x400Address/ediPartyName/unrecognized types use byte[].
                    if ((type == 2 || type == 7) && value instanceof String
                            && ((String) value).equalsIgnoreCase(sniHost)) {
                        return true;
                    }
                    // Defensive fallback for providers that deviate from the javadoc contract
                    // and hand back raw DER-encoded octets for an IP SAN instead of a String.
                    // Not exercised by the standard provider used in tests — both IP SAN tests
                    // hit the String branch above.
                    if (type == 7 && value instanceof byte[]) {
                        try {
                            String ip = InetAddress.getByAddress((byte[]) value).getHostAddress();
                            if (ip != null && ip.equalsIgnoreCase(sniHost)) {
                                return true;
                            }
                        } catch (UnknownHostException e) {
                            // Malformed IP SAN — not a match, keep checking other SAN entries
                        }
                    }
                }
            } catch (CertificateParsingException e) {
                Log.e(TAG, "Failed to parse SANs from server certificate", e);
            }
            return false;
        }

        private String extractCN(X509Certificate cert) {
            try {
                String dn = cert.getSubjectX500Principal().getName();
                for (String part : dn.split(",")) {
                    String trimmed = part.trim();
                    if (trimmed.startsWith("CN=")) {
                        return trimmed.substring(3);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract CN from certificate", e);
            }
            return null;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return acceptedIssuers;
        }
    }

    // ============================================================================
    // CUSTOM KEYMANAGER - Client certificate presentation
    // ============================================================================

    private static class CustomKeyManager extends X509ExtendedKeyManager {
        private final String alias;
        private final X509Certificate[] certChain;
        private final PrivateKey privateKey;

        public CustomKeyManager(String alias, X509Certificate[] certChain, PrivateKey privateKey) {
            this.alias = alias;
            this.certChain = certChain;
            this.privateKey = privateKey;
            Log.d(TAG, "CustomKeyManager initialized (alias: " + alias + ", chain: " + certChain.length + " certs)");
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return alias;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return this.alias.equals(alias) ? certChain : null;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] { alias };
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return this.alias.equals(alias) ? privateKey : null;
        }
    }

    // ============================================================================
    // MAIN CONNECT METHOD
    // ============================================================================

    @ReactMethod
    public void connect(
            String brokerUrl,
            String clientId,
            ReadableMap certificates,
            String sniHost,
            String brokerIp,
            String brokerCommonName,
            boolean isAdminUser,
            String keystorePath,
            String keystorePassword,
            String keystoreFormat,
            final Callback success,
            final Callback error) {
        final AtomicBoolean callbackFired = new AtomicBoolean(false);
        try {
            // Clean up any existing connection before creating a new one
            if (client != null) {
                Log.w(TAG, "Found existing client, cleaning up before new connection...");
                cleanupConnection();
            }

            String privateKeyAlias = certificates.hasKey("privateKeyAlias")
                    ? certificates.getString("privateKeyAlias")
                    : null;

            boolean useHardwareKey = certificates.hasKey("useHardwareKey")
                    ? certificates.getBoolean("useHardwareKey")
                    : false;

            // Hardware-backed keys are not supported for mTLS.
            // Hardware keys in AndroidKeyStore fail during TLS handshake because
            // Conscrypt requires extractable key material for ECDHE operations, but
            // hardware keys are non-extractable by design.
            if (useHardwareKey) {
                throw new IllegalArgumentException(
                    "Hardware-backed keys are not supported for mTLS. " +
                    "Hardware keys fail during TLS handshake because Conscrypt requires " +
                    "extractable key material for ECDHE operations, but hardware keys are " +
                    "non-extractable by design. Please use software-backed keys (useHardwareKey: false)."
                );
            }

            if (privateKeyAlias == null || privateKeyAlias.isEmpty()) {
                throw new IllegalArgumentException("privateKeyAlias required");
            }

            // Admin users can connect to the entire fleet of brokers — sniHost and brokerCommonName
            // are per-inverter fields and are ignored when isAdminUser = true
            String effectiveSniHost = isAdminUser ? null : sniHost;
            String effectiveBrokerCN = isAdminUser ? null : brokerCommonName;

            Log.i(TAG, "═══════════════════════════════════════");
            Log.i(TAG, "MQTT CONNECTION ATTEMPT STARTED");
            Log.i(TAG, "═══════════════════════════════════════");
            Log.i(TAG, "Broker: " + brokerUrl);
            Log.i(TAG, "Client ID: " + clientId);
            Log.i(TAG, "Admin user: " + isAdminUser);
            Log.i(TAG, "SNI host: " + (effectiveSniHost != null ? effectiveSniHost : "N/A (admin)"));
            Log.i(TAG, "Expected broker CN: " + (effectiveBrokerCN != null ? effectiveBrokerCN : "N/A (admin)"));
            Log.i(TAG, "Key: " + privateKeyAlias + " (software)");

            client = new MqttAndroidClient(
                    getReactApplicationContext(),
                    brokerUrl,
                    clientId);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(30);
            options.setKeepAliveInterval(60);
            options.setAutomaticReconnect(isAutoReconnectEnabled);

            SSLContext sslContext = createSSLContextFromKeystore(
                    certificates.getString("clientCert"),
                    certificates.getString("rootCa"),
                    privateKeyAlias,
                    effectiveBrokerCN,  // null for admin — skips CN validation
                    effectiveSniHost,   // null for admin — skips SNI/SAN pin (chain validation still runs)
                    keystorePath,
                    keystorePassword,
                    keystoreFormat);

            options.setSocketFactory(sslContext.getSocketFactory());

            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    Log.i(TAG, "MQTT connected to " + serverURI + (reconnect ? " (reconnected)" : ""));
                    sendEvent("MqttConnected", "Connected to broker: " + serverURI);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    String errorMsg = cause != null ? cause.getMessage() : "Unknown";
                    Log.w(TAG, "MQTT connection lost: " + errorMsg);
                    sendEvent("MqttDisconnected", "Connection lost: " + errorMsg);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    try {
                        byte[] payload = message.getPayload();
                        boolean isBinary = isBinaryData(topic, payload);

                        WritableMap eventData = Arguments.createMap();
                        eventData.putString("topic", topic);

                        if (isBinary) {
                            // Binary data: Base64 encode for transport over bridge
                            String payloadBase64 = android.util.Base64.encodeToString(
                                    payload,
                                    android.util.Base64.NO_WRAP);
                            eventData.putString("message", payloadBase64);
                            eventData.putBoolean("isBinary", true);
                            Log.d(TAG, "Received binary message on topic " + topic + " (" + payload.length + " bytes)");
                        } else {
                            // Text data: Send as UTF-8 string
                            String messageStr = new String(payload, StandardCharsets.UTF_8);
                            eventData.putString("message", messageStr);
                            eventData.putBoolean("isBinary", false);
                            Log.d(TAG, "Received text message on topic " + topic + " (" + payload.length + " bytes)");
                        }

                        reactContext
                                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                .emit("MqttMessage", eventData);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to process MQTT message on topic " + topic, e);
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    sendEvent("MqttDeliveryComplete", "Message delivered");
                }
            });

            client.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.i(TAG, "MQTT CONNECTION SUCCESSFUL");
                    safeInvoke(success, callbackFired, "Connected");
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    String errorMessage = "Connection failed";

                    if (exception != null) {
                        errorMessage = exception.getMessage();
                        if (errorMessage == null || errorMessage.isEmpty()) {
                            errorMessage = exception.getClass().getSimpleName();
                        }
                        Log.e(TAG, "MQTT CONNECTION FAILED", exception);
                    } else {
                        Log.e(TAG, "MQTT CONNECTION FAILED: Unknown error");
                    }

                    safeInvoke(error, callbackFired, errorMessage);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "MQTT setup error", e);
            e.printStackTrace();
            safeInvoke(error, callbackFired, e.getMessage() != null ? e.getMessage() : "Setup failed");
        }
    }

    // ============================================================================
    // SSL CONTEXT CREATION
    // ============================================================================

    private SSLContext createSSLContextFromKeystore(
            String clientPem,
            String rootPem,
            String privateKeyAlias,
            String expectedBrokerCN,
            String expectedSniHost,
            String keystorePath,
            String keystorePassword,
            String keystoreFormat) throws Exception {

        Log.d(TAG, "Creating SSL context with software-backed key");

        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        // Load client certificate chain
        Collection<? extends java.security.cert.Certificate> clientCertChain = cf.generateCertificates(
                new ByteArrayInputStream(clientPem.getBytes()));
        X509Certificate[] clientCertArray = clientCertChain.toArray(new X509Certificate[0]);
        X509Certificate clientCert = clientCertArray[0];

        // Load CA certificates
        Collection<? extends java.security.cert.Certificate> caCerts = cf.generateCertificates(
                new ByteArrayInputStream(rootPem.getBytes()));

        // Build certificate chain (exclude self-signed roots)
        ArrayList<X509Certificate> certChainList = new ArrayList<>();
        for (X509Certificate cert : clientCertArray) {
            if (!cert.getIssuerDN().equals(cert.getSubjectDN())) {
                certChainList.add(cert);
            }
        }
        X509Certificate[] certChain = certChainList.toArray(new X509Certificate[0]);

        // Load private key from software keystore (PKCS12)
        // Always use software-backed keys for TLS mTLS compatibility.
        // Hardware-backed keys in AndroidKeyStore fail during TLS handshake because
        // Conscrypt requires extractable key material for ECDHE operations, but
        // hardware keys are non-extractable by design.
        KeyStore softwareKeyStore = loadSoftwareKeyStore(keystorePath, keystorePassword, keystoreFormat);

        if (!softwareKeyStore.containsAlias(privateKeyAlias)) {
            throw new KeyException("Software key not found: " + privateKeyAlias);
        }

        KeyStore.Entry entry = softwareKeyStore.getEntry(
                privateKeyAlias,
                new KeyStore.PasswordProtection("".toCharArray()));

        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            throw new KeyException("Not a private key entry");
        }

        KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) entry;
        PrivateKey privateKey = privateKeyEntry.getPrivateKey();
        PublicKey publicKey = privateKeyEntry.getCertificate().getPublicKey();

        Log.d(TAG, "Loaded software-backed key from PKCS12 keystore");

        // Verify certificate matches key
        verifyCertMatchesKey(clientCert, publicKey);

        // Setup KeyManager
        KeyManager[] keyManagers = new KeyManager[] {
                new CustomKeyManager(privateKeyAlias, certChain, privateKey)
        };

        // Setup TrustManager
        // expectedBrokerCN/expectedSniHost are null for admin users — CN and SNI/SAN pinning
        // are skipped, but certificate chain validation (via CertPathValidator) always runs
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int i = 0;
        for (java.security.cert.Certificate cert : caCerts) {
            trustStore.setCertificateEntry("ca-cert-" + i++, (X509Certificate) cert);
        }

        TrustManager[] trustManagers = new TrustManager[] {
                new CustomTrustManager(trustStore, expectedBrokerCN, expectedSniHost)
        };

        // Create SSL context with TLS 1.3
        SSLContext sc = SSLContext.getInstance("TLSv1.3");
        sc.init(keyManagers, trustManagers, new SecureRandom());

        Log.d(TAG, "SSL context created (TLS 1.3)");
        return sc;
    }

    private void verifyCertMatchesKey(X509Certificate cert, PublicKey publicKey) throws Exception {
        byte[] certPubBytes = cert.getPublicKey().getEncoded();
        byte[] providedPubBytes = publicKey.getEncoded();

        if (!Arrays.equals(certPubBytes, providedPubBytes)) {
            throw new KeyException("Certificate does not match the private key");
        }
    }

    // ============================================================================
    // MQTT OPERATIONS
    // ============================================================================

    @ReactMethod
    public void subscribe(String topic, int qos, Callback successCallback, Callback errorCallback) {
        final AtomicBoolean callbackFired = new AtomicBoolean(false);
        try {
            if (client == null || !client.isConnected()) {
                throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
            }

            client.subscribe(topic, qos, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.i(TAG, "Subscribed to: " + topic);
                    safeInvoke(successCallback, callbackFired, "Subscribed to " + topic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    String errorMsg = exception != null ? exception.getMessage() : "Subscribe failed";
                    Log.e(TAG, "Subscribe failed: " + errorMsg);
                    safeInvoke(errorCallback, callbackFired, "Subscribe failed: " + errorMsg);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Subscribe error", e);
            safeInvoke(errorCallback, callbackFired, "Subscribe failed: " + e.getMessage());
        }
    }

    @ReactMethod
    public void unsubscribe(String topic, Callback successCallback, Callback errorCallback) {
        final AtomicBoolean callbackFired = new AtomicBoolean(false);
        try {
            if (client == null || !client.isConnected()) {
                throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
            }

            client.unsubscribe(topic, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.i(TAG, "Unsubscribed from: " + topic);
                    safeInvoke(successCallback, callbackFired, "Unsubscribed from " + topic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    String errorMsg = exception != null ? exception.getMessage() : "Unsubscribe failed";
                    Log.e(TAG, "Unsubscribe failed: " + errorMsg);
                    safeInvoke(errorCallback, callbackFired, "Unsubscribe failed: " + errorMsg);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Unsubscribe error", e);
            safeInvoke(errorCallback, callbackFired, "Unsubscribe failed: " + e.getMessage());
        }
    }

    @ReactMethod
    public void publish(String topic, String message, int qos, boolean retained,
            Callback successCallback, Callback errorCallback) {
        final AtomicBoolean callbackFired = new AtomicBoolean(false);
        try {
            if (client == null || !client.isConnected()) {
                throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
            }

            byte[] payload;
            // Check if message is marked as Base64-encoded binary
            // This prevents accidental Base64 decoding of JSON strings that happen to be valid Base64
            if (message.startsWith(BINARY_MARKER)) {
                // Remove marker and decode Base64
                String base64Data = message.substring(BINARY_MARKER.length());
                payload = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP);
                Log.d(TAG, "Publish: decoded marked Base64 message (" + payload.length + " bytes) for topic: " + topic);
            } else {
                // Plain text (UTF-8) - common case for JSON strings
                payload = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Log.d(TAG, "Publish: using UTF-8 text (" + payload.length + " bytes) for topic: " + topic);
            }

            MqttMessage mqttMessage = new MqttMessage(payload);
            mqttMessage.setQos(qos);
            mqttMessage.setRetained(retained);

            client.publish(topic, mqttMessage, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    safeInvoke(successCallback, callbackFired, "Published to " + topic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    String errorMsg = exception != null ? exception.getMessage() : "Publish failed";
                    Log.e(TAG, "Publish failed: " + errorMsg);
                    safeInvoke(errorCallback, callbackFired, "Publish failed: " + errorMsg);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Publish error", e);
            safeInvoke(errorCallback, callbackFired, "Publish failed: " + e.getMessage());
        }
    }

    @ReactMethod
    public void disconnect(Callback successCallback, Callback errorCallback) {
        Log.d(TAG, "───────────────────────────────────────");
        Log.d(TAG, "DISCONNECT REQUESTED");
        Log.d(TAG, "───────────────────────────────────────");

        final AtomicBoolean callbackFired = new AtomicBoolean(false);

        try {
            if (client == null) {
                Log.d(TAG, "No active MQTT client to disconnect");
                safeInvoke(successCallback, callbackFired, "No active connection");
                return;
            }

            if (client.isConnected()) {
                Log.d(TAG, "Client is connected, disconnecting...");
                client.disconnect(null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        try {
                            client.close();
                            client = null;
                            Log.i(TAG, "✓ MQTT disconnected and cleaned up");
                            safeInvoke(successCallback, callbackFired, "Disconnected successfully");
                        } catch (Exception e) {
                            Log.e(TAG, "Error closing client", e);
                            safeInvoke(errorCallback, callbackFired, "Disconnect error: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        String errorMsg = exception != null ? exception.getMessage() : "Disconnect failed";
                        Log.e(TAG, "Disconnect failed: " + errorMsg);

                        try {
                            if (client != null) {
                                client.close();
                                client = null;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error force-closing client", e);
                        }

                        safeInvoke(errorCallback, callbackFired, "Disconnect failed: " + errorMsg);
                    }
                });
            } else {
                Log.d(TAG, "Client not connected, cleaning up...");
                try {
                    client.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing disconnected client", e);
                }
                client = null;
                safeInvoke(successCallback, callbackFired, "Disconnected successfully");
            }

        } catch (Exception e) {
            Log.e(TAG, "Disconnect error", e);

            try {
                if (client != null) {
                    client.close();
                    client = null;
                }
            } catch (Exception cleanupException) {
                Log.e(TAG, "Cleanup error", cleanupException);
            }

            safeInvoke(errorCallback, callbackFired, "Disconnect failed: " + e.getMessage());
        }
    }

    @ReactMethod
    public void isConnected(Callback callback) {
        boolean connected = (client != null && client.isConnected());
        if (callback != null) {
            callback.invoke(connected);
        }
    }

    // ============================================================================
    // DIAGNOSTIC METHODS
    // ============================================================================

    @ReactMethod
    public void diagnoseKeyPurposes(String privateKeyAlias, Callback callback) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            if (!keyStore.containsAlias(privateKeyAlias)) {
                if (callback != null) {
                    callback.invoke("ERROR: Key not found: " + privateKeyAlias);
                }
                return;
            }

            KeyStore.Entry entry = keyStore.getEntry(privateKeyAlias, null);
            if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
                if (callback != null) {
                    callback.invoke("ERROR: Not a private key entry");
                }
                return;
            }

            PrivateKey privateKey = ((KeyStore.PrivateKeyEntry) entry).getPrivateKey();

            StringBuilder result = new StringBuilder();
            result.append("Key Purposes Diagnostic\n");
            result.append("========================\n");
            result.append("Alias: ").append(privateKeyAlias).append("\n");
            result.append("Android API: ").append(android.os.Build.VERSION.SDK_INT).append("\n\n");

            try {
                KeyFactory factory = KeyFactory.getInstance(privateKey.getAlgorithm(), "AndroidKeyStore");
                android.security.keystore.KeyInfo keyInfo = factory.getKeySpec(
                        privateKey,
                        android.security.keystore.KeyInfo.class);

                int purposes = keyInfo.getPurposes();
                result.append("Raw purposes: ").append(purposes).append("\n\n");

                boolean hasSign = (purposes & android.security.keystore.KeyProperties.PURPOSE_SIGN) != 0;
                boolean hasVerify = (purposes & android.security.keystore.KeyProperties.PURPOSE_VERIFY) != 0;

                result.append("Purposes:\n");
                result.append("  SIGN: ").append(hasSign ? "YES" : "NO").append("\n");
                result.append("  VERIFY: ").append(hasVerify ? "YES" : "NO").append("\n");

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    boolean hasAgreeKey = (purposes & android.security.keystore.KeyProperties.PURPOSE_AGREE_KEY) != 0;
                    result.append("  AGREE_KEY: ").append(hasAgreeKey ? "YES" : "NO").append("\n");
                } else {
                    result.append("  AGREE_KEY: N/A (Android 12+ only)\n");
                }

                result.append("\nKey size: ").append(keyInfo.getKeySize()).append(" bits\n");
                result.append("Hardware-backed: ").append(keyInfo.isInsideSecureHardware()).append("\n");

            } catch (Exception e) {
                result.append("Error: ").append(e.getMessage()).append("\n");
            }

            if (callback != null) {
                callback.invoke(result.toString());
            }

        } catch (Exception e) {
            if (callback != null) {
                callback.invoke("ERROR: " + e.getMessage());
            }
        }
    }

    @ReactMethod
    public void checkKeyExists(String privateKeyAlias, Callback callback) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (callback != null) {
                callback.invoke(keyStore.containsAlias(privateKeyAlias));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(false);
            }
        }
    }

    @ReactMethod
    public void listKeyAliases(Callback callback) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            Enumeration<String> aliases = keyStore.aliases();
            StringBuilder sb = new StringBuilder("Available key aliases:\n");
            while (aliases.hasMoreElements()) {
                sb.append("- ").append(aliases.nextElement()).append("\n");
            }
            if (callback != null) {
                callback.invoke(sb.toString());
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke("Error: " + e.getMessage());
            }
        }
    }

    /**
     * Directories the software keystore may legitimately live in, in preference order.
     *
     * react-native-ecc-csr 1.3.1+ writes the keystore to getNoBackupFilesDir() so the private key is
     * excluded from Android Auto Backup unconditionally; earlier versions used getFilesDir(). Both
     * are app-private, so both are accepted - and filesDir has to stay accepted because a device
     * that has not yet run the ecc-csr migration still has its keystore there.
     */
    private List<File> keystoreRoots() {
        List<File> roots = new ArrayList<>();
        File noBackupDir = getReactApplicationContext().getNoBackupFilesDir();
        if (noBackupDir != null) {
            roots.add(noBackupDir);
        }
        File filesDir = getReactApplicationContext().getFilesDir();
        if (filesDir != null) {
            roots.add(filesDir);
        }
        return roots;
    }

    /**
     * Resolves the keystore location, tolerating both storage generations.
     *
     * - Absolute path that exists: used verbatim.
     * - Absolute path that does not exist: retried by filename under each root. This is what makes
     *   the ecc-csr no-backup move invisible to the app. The installer persists keystorePath across
     *   launches, so right after upgrading it hands us a files/ path for a file ecc-csr has already
     *   moved to no_backup/; without this retry the first post-upgrade connect fails.
     * - Relative path or default: resolved against each root in order.
     *
     * @return the first candidate that exists, or the preferred candidate when none do, so the
     *         caller's not-found error names the location the keystore is supposed to be in
     */
    private File resolveKeystoreFile(String filename) {
        List<File> candidates = new ArrayList<>();
        File supplied = new File(filename);
        if (supplied.isAbsolute()) {
            candidates.add(supplied);
            for (File root : keystoreRoots()) {
                candidates.add(new File(root, supplied.getName()));
            }
        } else {
            for (File root : keystoreRoots()) {
                candidates.add(new File(root, filename));
            }
        }
        if (candidates.isEmpty()) {
            return supplied;
        }
        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    /**
     * Whether the resolved keystore path sits inside one of the app-private keystore roots.
     */
    private boolean isInsideKeystoreRoot(File keystoreFile) throws IOException {
        String canonicalKeystorePath = keystoreFile.getCanonicalPath();
        for (File root : keystoreRoots()) {
            if (canonicalKeystorePath.startsWith(root.getCanonicalPath() + File.separator)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads software keystore with dual-format support for migration compatibility.
     *
     * Supports both:
     * 1. Encrypted PKCS12 (new format) - written by react-native-ecc-csr with EncryptedFile
     * 2. Plain PKCS12 (legacy format) - written by older CSR module versions
     *
     * This addresses PR #4 reviewer Blocker B by making the keystore contract explicit.
     * The path, password, and format are now API parameters instead of hidden filesystem conventions.
     *
     * @param keystorePath Path to keystore file, or null to use default (SOFTWARE_KEYSTORE_FILE);
     *                     see {@link #resolveKeystoreFile(String)} for how it is resolved
     * @param keystorePassword Password for keystore, or null to use empty string
     * @param keystoreFormat Format hint: "pkcs12", "encrypted", or null for auto-detect
     * @return KeyStore loaded from the specified keystore file
     * @throws KeyException if keystore file doesn't exist or cannot be loaded
     */
    private KeyStore loadSoftwareKeyStore(String keystorePath, String keystorePassword, String keystoreFormat) throws Exception {
        // Use defaults for backward compatibility
        String filename = (keystorePath != null && !keystorePath.isEmpty()) ? keystorePath : SOFTWARE_KEYSTORE_FILE;
        String password = (keystorePassword != null) ? keystorePassword : "";

        File keystoreFile = resolveKeystoreFile(filename);

        // Containment check: the resolved path must stay within app-private storage, even for
        // absolute paths supplied across the RN bridge. Checked after resolution so neither the
        // caller-supplied path nor the no-backup fallback can escape via "..".
        if (!isInsideKeystoreRoot(keystoreFile)) {
            throw new KeyException("Keystore path must be inside app-private storage: " + keystoreFile.getCanonicalPath());
        }

        // Check if keystore file exists
        if (!keystoreFile.exists()) {
            throw new KeyException(
                "Software keystore not found: " + keystoreFile.getAbsolutePath() +
                ". Ensure CSR module has run and created the keystore file."
            );
        }

        Log.d(TAG, "Loading software keystore from: " + keystoreFile.getAbsolutePath());
        Log.d(TAG, "Keystore format hint: " + (keystoreFormat != null ? keystoreFormat : "auto-detect"));

        // If format is explicitly specified, try only that format
        if ("pkcs12".equals(keystoreFormat)) {
            KeyStore keyStore = tryLoadPlainKeyStore(keystoreFile, password);
            if (keyStore != null) {
                Log.d(TAG, "Loaded plain PKCS12 keystore successfully");
                return keyStore;
            }
            throw new KeyException("Failed to load keystore as PKCS12 format");
        } else if ("encrypted".equals(keystoreFormat)) {
            KeyStore keyStore = tryLoadEncryptedKeyStore(keystoreFile, password);
            if (keyStore != null) {
                Log.d(TAG, "Loaded encrypted keystore successfully");
                return keyStore;
            }
            throw new KeyException("Failed to load keystore as encrypted format");
        }

        // Auto-detect: Try encrypted format first (new CSR module behavior)
        KeyStore keyStore = tryLoadEncryptedKeyStore(keystoreFile, password);
        if (keyStore != null) {
            Log.d(TAG, "Loaded encrypted keystore successfully");
            return keyStore;
        }

        // Fall back to plain PKCS12 format (legacy CSR module behavior)
        keyStore = tryLoadPlainKeyStore(keystoreFile, password);
        if (keyStore != null) {
            Log.d(TAG, "Loaded plain PKCS12 keystore successfully (legacy format)");
            Log.w(TAG, "Consider updating CSR module to use encrypted keystore format");
            return keyStore;
        }

        // Neither format worked
        throw new KeyException(
            "Failed to load software keystore. " +
            "File exists but could not be read as encrypted or plain PKCS12. " +
            "The keystore may be corrupted or in an unsupported format. " +
            "Try regenerating certificates with the CSR module."
        );
    }

    /**
     * Attempts to load keystore as EncryptedFile (AES256-GCM encrypted PKCS12).
     *
     * @param keystoreFile The PKCS12 keystore file
     * @param password Password for the PKCS12 keystore
     * @return Loaded KeyStore, or null if file is not in encrypted format
     */
    private KeyStore tryLoadEncryptedKeyStore(File keystoreFile, String password) {
        try {
            MasterKey masterKey = new MasterKey.Builder(getReactApplicationContext())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

            EncryptedFile encryptedFile = new EncryptedFile.Builder(
                getReactApplicationContext(),
                keystoreFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
                .build();

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = encryptedFile.openFileInput()) {
                keyStore.load(fis, password.toCharArray());
            }

            return keyStore;
        } catch (SecurityException e) {
            // SecurityException means file is not encrypted or encryption format mismatch
            Log.d(TAG, "Keystore is not in encrypted format: " + e.getMessage());
            return null;
        } catch (Exception e) {
            // Other exceptions (IO, key format, etc.) - try next format
            Log.d(TAG, "Failed to load encrypted keystore: " + e.getMessage());
            return null;
        }
    }

    /**
     * Attempts to load keystore as plain PKCS12 (unencrypted).
     *
     * @param keystoreFile The PKCS12 keystore file
     * @param password Password for the PKCS12 keystore
     * @return Loaded KeyStore, or null if file cannot be loaded as plain PKCS12
     */
    private KeyStore tryLoadPlainKeyStore(File keystoreFile, String password) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(keystoreFile)) {
                keyStore.load(fis, password.toCharArray());
            }

            return keyStore;
        } catch (Exception e) {
            // Not a valid plain PKCS12 file
            Log.d(TAG, "Failed to load plain PKCS12 keystore: " + e.getMessage());
            return null;
        }
    }

    private void sendEvent(String eventName, String message) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, message);
    }
}