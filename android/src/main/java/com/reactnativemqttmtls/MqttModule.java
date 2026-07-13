package com.reactnativemqttmtls;

import android.util.Log;
import androidx.annotation.NonNull;
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
import java.net.Socket;
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
     * Detects whether payload is binary data or UTF-8 text.
     * Returns true if the data cannot be decoded as UTF-8.
     *
     * Note: Protobuf messages use varint encoding which produces invalid UTF-8 byte sequences,
     * so they are correctly detected as binary by this method.
     */
    private boolean isBinaryData(byte[] payload) {
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

        public CustomTrustManager(KeyStore trustStore, String expectedBrokerCN) throws Exception {
            this.expectedBrokerCN = expectedBrokerCN;

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
                Log.d(TAG, "Broker CN validation skipped (admin user)");
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

            boolean validated = false;

            // Try direct validation (server cert signed by one of our CAs)
            for (X509Certificate ca : acceptedIssuers) {
                try {
                    serverCert.verify(ca.getPublicKey());
                    validated = true;
                    Log.d(TAG, "Server certificate validated by: " + ca.getSubjectDN());
                    break;
                } catch (Exception e) {
                    // Try next CA
                }
            }

            // Try validation via intermediate certificates
            if (!validated && chain.length > 1) {
                for (int i = 1; i < chain.length; i++) {
                    X509Certificate intermediate = chain[i];
                    for (X509Certificate ca : acceptedIssuers) {
                        try {
                            intermediate.verify(ca.getPublicKey());
                            serverCert.verify(intermediate.getPublicKey());
                            validated = true;
                            Log.d(TAG, "Server certificate validated via intermediate");
                            break;
                        } catch (Exception e) {
                            // Try next
                        }
                    }
                    if (validated) break;
                }
            }

            // Check if intermediate IS a trusted CA
            if (!validated && chain.length > 1) {
                for (int i = 1; i < chain.length; i++) {
                    X509Certificate intermediate = chain[i];
                    for (X509Certificate ca : acceptedIssuers) {
                        if (intermediate.getSubjectDN().equals(ca.getSubjectDN())) {
                            try {
                                byte[] intermediatePubKey = intermediate.getPublicKey().getEncoded();
                                byte[] caPubKey = ca.getPublicKey().getEncoded();

                                if (Arrays.equals(intermediatePubKey, caPubKey)) {
                                    serverCert.verify(intermediate.getPublicKey());
                                    validated = true;
                                    Log.d(TAG, "Server certificate validated by trusted intermediate");
                                    break;
                                }
                            } catch (Exception e) {
                                // Try next
                            }
                        }
                    }
                    if (validated) break;
                }
            }

            if (!validated) {
                Log.e(TAG, "Server certificate validation failed - not trusted by any CA");
                throw new CertificateException("Server certificate not trusted by any configured CA");
            }
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
                    effectiveBrokerCN);  // null for admin — skips CN validation

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
                        boolean isBinary = isBinaryData(payload);

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
            String expectedBrokerCN) throws Exception {

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
        String keystorePath = getReactApplicationContext().getFilesDir() + "/" + SOFTWARE_KEYSTORE_FILE;
        KeyStore softwareKeyStore = KeyStore.getInstance("PKCS12");

        // Load keystore with try-with-resources to ensure FileInputStream is closed
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            softwareKeyStore.load(fis, "".toCharArray());
        }

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
        // expectedBrokerCN is null for admin users — CN validation is skipped,
        // but certificate chain validation still runs for security
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int i = 0;
        for (java.security.cert.Certificate cert : caCerts) {
            trustStore.setCertificateEntry("ca-cert-" + i++, (X509Certificate) cert);
        }

        TrustManager[] trustManagers = new TrustManager[] {
                new CustomTrustManager(trustStore, expectedBrokerCN)
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

        try {
            if (client == null) {
                Log.d(TAG, "No active MQTT client to disconnect");
                if (successCallback != null) {
                    successCallback.invoke("No active connection");
                }
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
                            if (successCallback != null) {
                                successCallback.invoke("Disconnected successfully");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error closing client", e);
                            if (errorCallback != null) {
                                errorCallback.invoke("Disconnect error: " + e.getMessage());
                            }
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

                        if (errorCallback != null) {
                            errorCallback.invoke("Disconnect failed: " + errorMsg);
                        }
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
                if (successCallback != null) {
                    successCallback.invoke("Disconnected successfully");
                }
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

            if (errorCallback != null) {
                errorCallback.invoke("Disconnect failed: " + e.getMessage());
            }
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

    private void sendEvent(String eventName, String message) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, message);
    }
}