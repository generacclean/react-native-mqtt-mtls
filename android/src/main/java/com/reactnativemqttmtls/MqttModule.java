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

    /**
     * Quiesce timeout for a teardown disconnect. Zero because teardown must not wait on in-flight
     * messages: either a new connection is about to be established, or the transport is already
     * gone and waiting would only delay the failure.
     */
    private static final long TEARDOWN_QUIESCE_TIMEOUT_MS = 0;

    // Keep a direct reference to our full BouncyCastle provider instance
    // to avoid getting the system's stripped-down BC provider
    private static final Provider FULL_BC_PROVIDER = new BouncyCastleProvider();

    // Thread-safe provider initialization
    private static volatile boolean providerInitialized = false;
    private static final Object providerLock = new Object();

    private final ReactApplicationContext reactContext;
    /**
     * Guards every mutation of {@link #client}. The field is volatile so the plain null and
     * isConnected() reads scattered through the module see the current instance, but visibility
     * alone is not enough for the compare-and-clear in {@link #releaseClientResources}: without a
     * lock, a connect() on the bridge thread could install a new client between that compare and
     * its clear, and a stale callback would then null out a live client. Both the install in
     * connect() and the compare-and-clear hold this lock, so they cannot interleave.
     */
    private final Object clientLock = new Object();
    // Written on the bridge thread by connect(), read and cleared from Paho's callbacks on the main
    // thread, so both sides must see the same instance to avoid tearing down the wrong client.
    private volatile MqttAndroidClient client;
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
            // `/network/` must precede the `/config` text rule below: network config/state
            // responses are protobuf, but their topics contain `/config`.
            if (topic.contains("/proto/") ||
                topic.contains("/device") ||
                topic.contains("/rma") ||
                topic.contains("/assembly") ||
                topic.contains("/installed") ||
                topic.contains("/network/")) {
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
        // No teardown here: `client` is an instance field, so it is always null in a fresh module
        // and cleanupConnection() could only return at its first check. State that outlives a module
        // is torn down by invalidate() on the way out, not by the next module on the way in.
    }

    /**
     * Tears down the connection when React Native destroys the module.
     *
     * This is the one moment the app cannot ask for cleanup itself, and the moment that needs it
     * most: a JS reload drops the module while MqttService keeps running, so without this the
     * receiver stays registered, the service stays bound, and the handle stays cached — the three
     * leaks this teardown exists to prevent.
     */
    @Override
    public void invalidate() {
        Log.d(TAG, "React context invalidated - tearing down MQTT connection");
        cleanupConnection();
        super.invalidate();
    }

    /**
     * The pre-0.69 equivalent of {@link #invalidate()}, kept because peerDependencies still allows
     * React Native 0.60, where invalidate() does not exist and this is the only hook that fires.
     *
     * The two are alternatives picked by the runtime, not a chain, and which of them fires depends
     * on the version:
     *   - below 0.69: invalidate() does not exist, so React Native calls this directly.
     *   - 0.83 (what installer-app runs): BaseJavaModule.invalidate() is an empty body, so only
     *     invalidate() fires and this override is dead.
     *   - the 0.71 this module compiles against sits between the two: BaseJavaModule.invalidate()
     *     still delegates here, so both fire.
     *
     * That last case is why cleanupConnection() has to be idempotent — it returns at its first check
     * once the client is forgotten, so a runtime that calls both still tears down once.
     *
     * NativeModule.onCatalystInstanceDestroy() is a default method marked forRemoval, so this
     * @Override only compiles while build.gradle pins compileOnly react-android 0.71.0. If that pin
     * is ever raised past the removal, delete this method rather than the @Override — on any runtime
     * new enough to have dropped it, invalidate() is the hook that fires.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onCatalystInstanceDestroy() {
        Log.d(TAG, "Catalyst instance destroyed - tearing down MQTT connection");
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
     * Tears down whichever client is current. Use {@link #cleanupConnection(MqttAndroidClient)}
     * instead from anywhere that already knows which client it means — a callback, for instance,
     * which may be running after its client was replaced. "Whatever is current" is only the right
     * target for teardown that is not tied to one attempt, such as {@link #invalidate()}.
     */
    private void cleanupConnection() {
        cleanupConnection(client);
    }

    /**
     * Tears down the given client and evicts its handle from the Paho Android service.
     *
     * The service keeps a map of MqttConnection instances keyed by
     * "serverURI:clientId:packageName", and hands the cached instance to every MqttAndroidClient
     * built from those same three values. Only MqttService.disconnect() removes an entry from that
     * map; MqttService.close() closes the underlying MqttAsyncClient and leaves the entry in place.
     * Because callers reuse a persisted clientId against a fixed broker URL, closing without
     * disconnecting strands a permanently closed client under the handle the next connect resolves
     * to, and from then on every connect fails immediately with "Client is closed (32111)" for the
     * remaining life of the process.
     *
     * So disconnect() is the only teardown issued here, and it is issued even when isConnected() is
     * false. MqttConnection.disconnect() tolerates a client that is not connected — it reports an
     * error status instead of throwing — and the handle is evicted either way. That not-connected
     * path is what an ungraceful drop looks like (airplane mode, or the device leaving range of the
     * gateway's access point), and it is the case that used to strand the handle.
     *
     * close() is deliberately not called: after disconnect() the handle is gone, so close() could
     * only log an invalid-handle error. Nothing is left for it to release either — MqttAsyncClient
     * opens its file persistence in its constructor and closes it again on the next line, taking the
     * lock back only on connect, and MqttDefaultFilePersistence.open() swallows a lock failure
     * anyway, so the next client is never waiting on this one.
     */
    private void cleanupConnection(MqttAndroidClient staleClient) {
        Log.d(TAG, "Cleaning up MQTT connection state...");

        if (staleClient == null) {
            Log.d(TAG, "  - No active client to clean up");
            return;
        }

        try {
            Log.d(TAG, "  - Disconnecting client (evicts its handle from the MQTT service)...");
            staleClient.disconnect(TEARDOWN_QUIESCE_TIMEOUT_MS);
        } catch (Exception e) {
            // Thrown when the client never finished binding to the service, or when its handle is
            // already gone. Either way there is no handle left to strand.
            Log.w(TAG, "  - Disconnect error (non-critical): " + e.getMessage());
        }

        releaseClientResources(staleClient);
        Log.d(TAG, "✓ Cleanup complete");
    }

    /**
     * Releases the receiver registration and service binding of a client that has already been
     * disconnected, and forgets it.
     *
     * Neither is released by close(), so every teardown that went through close() leaked a
     * registered BroadcastReceiver and a bound service. The client is only forgotten if it is still
     * the current one: a disconnect callback can arrive after a new connection has replaced it, and
     * that new client must not be torn down by the old client's callback.
     */
    private void releaseClientResources(MqttAndroidClient disconnectedClient) {
        if (disconnectedClient == null) {
            return;
        }

        try {
            disconnectedClient.unregisterResources();
        } catch (Exception e) {
            Log.w(TAG, "Resource release error (non-critical): " + e.getMessage());
        }

        synchronized (clientLock) {
            if (client == disconnectedClient) {
                client = null;
            }
        }
    }

    /**
     * Whether a connect failure means the cached MqttConnection behind our handle is unusable,
     * rather than the broker being unreachable. Retrying against the same handle can never succeed,
     * so the handle has to be evicted first. See {@link #cleanupConnection()}.
     *
     * ClientComms.connect() throws all three of these codes from the same block, when the client is
     * in a state it cannot connect from. CLIENT_DISCONNECTING is transient in plain Paho, because
     * shutdownConnection() moves the state on to DISCONNECTED; it is not transient here, because
     * MqttService.disconnect() drops the map entry the moment it is called while the underlying
     * disconnect is still running, so a handle that reports DISCONNECTING is already orphaned.
     * Evicting it is safe either way: the next attempt builds a fresh MqttConnection instead of
     * waiting on state it cannot observe.
     *
     * CONNECT_IN_PROGRESS (32110) is deliberately excluded — it resolves on its own, and evicting
     * there would tear down a healthy attempt.
     */
    private static boolean isUnusableClientFailure(Throwable exception) {
        if (!(exception instanceof MqttException)) {
            return false;
        }

        int reasonCode = ((MqttException) exception).getReasonCode();
        return reasonCode == MqttException.REASON_CODE_CLIENT_CLOSED
                || reasonCode == MqttException.REASON_CODE_CLIENT_CONNECTED
                || reasonCode == MqttException.REASON_CODE_CLIENT_DISCONNECTING;
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
        /** id-kp-serverAuth: the certificate may authenticate a TLS server. */
        private static final String EKU_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

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

            // Also unconditional: PKIX says the leaf is genuine, not that it is a broker.
            requireTlsServerCertificate(serverCert);

            // Both pins below key off whether an expected value was configured, not off isAdminUser
            // directly: admin mode nulls them, but a non-admin caller passing an empty string loses
            // the pin the same way. The skip logs say which value was missing so a support engineer
            // reading a device log is not told "admin" for a configuration bug.
            if (expectedBrokerCN != null && !expectedBrokerCN.isEmpty()) {
                String brokerCN = extractCN(serverCert);
                Log.d(TAG, "Broker certificate CN: " + brokerCN);

                if (!expectedBrokerCN.equals(brokerCN)) {
                    Log.e(TAG, "CN MISMATCH! Expected: " + expectedBrokerCN + ", Got: " + brokerCN);
                    throw new CertificateException(
                            "Broker CN mismatch. Expected: " + expectedBrokerCN + ", Got: " + brokerCN);
                }
                Log.d(TAG, "✓ Broker CN validated: " + brokerCN);
            } else {
                Log.w(TAG, "No expected broker CN configured — CN pin skipped");
            }

            if (expectedSniHost != null && !expectedSniHost.isEmpty()) {
                if (!certificateMatchesHost(serverCert, expectedSniHost)) {
                    Log.e(TAG, "SAN MISMATCH! No SAN entry on server certificate matches SNI host: " + expectedSniHost);
                    throw new CertificateException(
                            "Broker certificate SAN does not match SNI host: " + expectedSniHost);
                }
                Log.d(TAG, "✓ Broker certificate SAN matched SNI host: " + expectedSniHost);
            } else {
                Log.w(TAG, "No expected SNI host configured — SAN pin skipped");
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
         * Requires the leaf to assert id-kp-serverAuth explicitly, and rejects it otherwise.
         *
         * PKIX checks signatures, validity dates, path length, and basic constraints, but not the
         * leaf's purpose. Every device holds a *client* certificate from the same gateway CA, so
         * without this check one of those would pass as the broker whenever the CN and SAN pins
         * are skipped — which is every production connection today, since the app forces admin
         * mode. This is the check iOS gets from SecPolicyCreateSSL(true, nil).
         *
         * Two cases are rejected for parity with that iOS policy rather than because PKIX asks
         * for it. A leaf with no extendedKeyUsage extension at all is unconstrained as far as
         * RFC 5280 is concerned, and a leaf asserting anyExtendedKeyUsage (2.5.29.37.0) claims
         * every purpose — but Apple's SSL policy rejects both with "Extended key usage does not
         * match certificate usage", so a broker that presents either cannot be reached from the
         * iOS build today and accepting it here would be an Android-only relaxation.
         */
        private void requireTlsServerCertificate(X509Certificate leaf) throws CertificateException {
            List<String> purposes;
            try {
                purposes = leaf.getExtendedKeyUsage();
            } catch (CertificateParsingException e) {
                throw new CertificateException(
                        "Cannot parse the extendedKeyUsage extension on the broker certificate", e);
            }

            if (purposes == null) {
                Log.e(TAG, "EKU MISSING! Certificate declares no extended key usage, so it does not "
                        + "assert TLS server authentication");
                throw new CertificateException(
                        "Broker certificate declares no extended key usage; it is not a TLS server "
                                + "certificate");
            }

            if (purposes.contains(EKU_SERVER_AUTH)) {
                Log.d(TAG, "✓ Broker certificate is valid for TLS server authentication");
                return;
            }

            Log.e(TAG, "EKU MISMATCH! Certificate is not a TLS server certificate: " + purposes);
            throw new CertificateException(
                    "Broker certificate is not valid for TLS server authentication. "
                            + "Extended key usage: " + purposes);
        }

        /**
         * Checks whether any DNS or IP subjectAltName entry on the certificate matches the
         * expected SNI host exactly (no wildcard matching — this is a private IoT trust boundary).
         *
         * Returns false only for a genuine mismatch. An unparseable or absent SAN extension
         * throws instead, so the three ways this check can fail do not collapse into one
         * "SAN does not match" message and send the installer after the wrong problem.
         */
        private boolean certificateMatchesHost(X509Certificate cert, String sniHost)
                throws CertificateException {
            Collection<List<?>> sans;
            try {
                sans = cert.getSubjectAlternativeNames();
            } catch (CertificateParsingException e) {
                throw new CertificateException(
                        "Cannot parse the subjectAltName extension on the broker certificate", e);
            }
            if (sans == null) {
                throw new CertificateException(
                        "Broker certificate has no subjectAltName extension, so it cannot be bound "
                        + "to SNI host: " + sniHost);
            }
            for (List<?> san : sans) {
                Integer type = (Integer) san.get(0);
                Object value = san.get(1);
                // Per the X509Certificate#getSubjectAlternativeNames javadoc, GeneralName
                // type 2 (dNSName) and type 7 (iPAddress) are both returned as Strings —
                // IPv4 in dotted-quad notation, IPv6 as colon-separated hex groups.
                // Only otherName/x400Address/ediPartyName/unrecognized types use byte[], so
                // there is no byte[] branch here: it would be unreachable and untested.
                if ((type == 2 || type == 7) && value instanceof String
                        && ((String) value).equalsIgnoreCase(sniHost)) {
                    return true;
                }
            }
            return false;
        }

        private String extractCN(X509Certificate cert) {
            try {
                return cnFromDn(cert.getSubjectX500Principal().getName());
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract CN from certificate", e);
                return null;
            }
        }

        /**
         * Reads the CN attribute out of an RFC 2253 distinguished name.
         *
         * A plain {@code dn.split(",")} is wrong here: RFC 2253 escapes a comma inside an attribute
         * value as {@code \,}, so {@code CN=Acme\, Inc,O=Acme} splits into two RDNs and the CN comes
         * back truncated to {@code Acme\}. Since this value is compared against the expected broker
         * CN, a truncated read is a failed pin rather than a cosmetic bug. Splitting on unescaped
         * separators only, then unescaping the value, handles it. javax.naming.ldap.LdapName would
         * do this properly but is not available on Android.
         */
        static String cnFromDn(String dn) {
            if (dn == null) {
                return null;
            }
            for (String rdn : splitOnUnescapedSeparators(dn)) {
                String trimmed = rdn.trim();
                // Attribute types are case-insensitive per RFC 4519.
                if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                    return unescapeDnValue(trimmed.substring(3));
                }
            }
            return null;
        }

        /**
         * Splits a DN on the two separators RFC 2253 defines: {@code ,} between RDNs and {@code +}
         * between the attributes of a multi-valued RDN. Without the {@code +} case,
         * {@code CN=broker.local+OU=field} yields one part whose value reads
         * {@code broker.local+OU=field} and the CN pin fails on a certificate that should match.
         */
        private static List<String> splitOnUnescapedSeparators(String dn) {
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean escaped = false;
            for (int i = 0; i < dn.length(); i++) {
                char c = dn.charAt(i);
                if (escaped) {
                    current.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    current.append(c);
                    escaped = true;
                } else if (c == ',' || c == '+') {
                    parts.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            parts.add(current.toString());
            return parts;
        }

        /** Drops the backslashes RFC 2253 uses to escape special characters within a value. */
        private static String unescapeDnValue(String value) {
            StringBuilder unescaped = new StringBuilder(value.length());
            boolean escaped = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (!escaped && c == '\\') {
                    escaped = true;
                } else {
                    unescaped.append(c);
                    escaped = false;
                }
            }
            return unescaped.toString();
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

            final MqttAndroidClient attemptClient = new MqttAndroidClient(
                    getReactApplicationContext(),
                    brokerUrl,
                    clientId);
            synchronized (clientLock) {
                client = attemptClient;
            }

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

            attemptClient.setCallback(new MqttCallbackExtended() {
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

            attemptClient.connect(options, null, new IMqttActionListener() {
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

                    // Nothing this client does can recover an unusable cached connection, so evict
                    // its handle now and let the next attempt build a fresh one. Named explicitly
                    // rather than via "whatever is current": this runs on the main thread, off Paho's
                    // broadcast, so a connect() on the bridge thread can have installed a newer
                    // client by now, and that one must not be torn down by this attempt's failure.
                    if (isUnusableClientFailure(exception)) {
                        Log.w(TAG, "Client handle is unusable, evicting it so the next attempt starts clean");
                        cleanupConnection(attemptClient);
                    }

                    safeInvoke(error, callbackFired, errorMessage);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "MQTT setup error", e);
            e.printStackTrace();
            // Anything thrown after the client was installed leaves a client that has already
            // registered its receiver and bound the service, and whose handle is cached. The next
            // connect() would clear the field without releasing any of that, so release it here.
            //
            // Untargeted is safe here, unlike in the onFailure above: this runs on the bridge thread,
            // the only thread that installs a client, and @ReactMethod dispatch is serialized, so no
            // newer client can exist yet. Paho's callbacks only ever clear the field, never install.
            cleanupConnection();
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
        // Read once and work from the local: every teardown below then names the client this call was
        // issued for, rather than whatever happens to be current by the time it runs.
        final MqttAndroidClient disconnectingClient = client;

        try {
            if (disconnectingClient == null) {
                Log.d(TAG, "No active MQTT client to disconnect");
                safeInvoke(successCallback, callbackFired, "No active connection");
                return;
            }

            if (disconnectingClient.isConnected()) {
                Log.d(TAG, "Client is connected, disconnecting...");
                disconnectingClient.disconnect(null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        releaseClientResources(disconnectingClient);
                        Log.i(TAG, "✓ MQTT disconnected and cleaned up");
                        safeInvoke(successCallback, callbackFired, "Disconnected successfully");
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        String errorMsg = exception != null ? exception.getMessage() : "Disconnect failed";
                        Log.e(TAG, "Disconnect failed: " + errorMsg);

                        // The handle is evicted whether or not the broker acknowledged, so the
                        // client is finished with either way.
                        releaseClientResources(disconnectingClient);

                        safeInvoke(errorCallback, callbackFired, "Disconnect failed: " + errorMsg);
                    }
                });
            } else {
                // A client that is not connected still has to go through disconnect(), because that
                // is the only call that evicts its handle from the MQTT service.
                Log.d(TAG, "Client not connected, cleaning up...");
                cleanupConnection(disconnectingClient);
                safeInvoke(successCallback, callbackFired, "Disconnected successfully");
            }

        } catch (Exception e) {
            Log.e(TAG, "Disconnect error", e);
            cleanupConnection(disconnectingClient);
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
     * react-native-ecc-csr 1.4.0+ writes the keystore to getNoBackupFilesDir() so the private key is
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
     * @param roots the keystore roots to resolve against; the caller passes the same list to
     *              {@link #isInsideKeystoreRoot(File, List)} so resolution and containment cannot
     *              disagree about which directories are legitimate
     * @return the first candidate that exists, or the preferred candidate when none do, so the
     *         caller's not-found error names the location the keystore is supposed to be in
     */
    private File resolveKeystoreFile(String filename, List<File> roots) {
        List<File> candidates = new ArrayList<>();
        File supplied = new File(filename);
        if (supplied.isAbsolute()) {
            candidates.add(supplied);
            for (File root : roots) {
                candidates.add(new File(root, supplied.getName()));
            }
        } else {
            for (File root : roots) {
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
     *
     * @param roots must be the same list used to resolve {@code keystoreFile}. getNoBackupFilesDir()
     *              can in principle return null, and re-deriving the roots here would let a path
     *              resolved into no_backup/ be rejected by a containment check that no longer knows
     *              about that directory.
     */
    private boolean isInsideKeystoreRoot(File keystoreFile, List<File> roots) throws IOException {
        String canonicalKeystorePath = keystoreFile.getCanonicalPath();
        for (File root : roots) {
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
     * The path, password, and format are API parameters rather than hidden filesystem conventions,
     * so the keystore contract between this module and the CSR module is explicit.
     *
     * The plain-PKCS12 branch is a migration bridge, not a supported format: it exists so a device
     * that provisioned its key before ecc-csr wrote EncryptedFile keystores keeps connecting after
     * an app upgrade. Those keystores are never rewritten in place, so the branch cannot be removed
     * until every device in the field has re-provisioned; drop it when that is confirmed rather
     * than on a chosen version.
     *
     * @param keystorePath Path to keystore file, or null to use default (SOFTWARE_KEYSTORE_FILE);
     *                     see {@link #resolveKeystoreFile(String, List)} for how it is resolved
     * @param keystorePassword Password for keystore, or null to use empty string
     * @param keystoreFormat Format hint: "pkcs12", "encrypted", or null for auto-detect
     * @return KeyStore loaded from the specified keystore file
     * @throws KeyException if keystore file doesn't exist or cannot be loaded
     */
    private KeyStore loadSoftwareKeyStore(String keystorePath, String keystorePassword, String keystoreFormat) throws Exception {
        // Use defaults for backward compatibility
        String filename = (keystorePath != null && !keystorePath.isEmpty()) ? keystorePath : SOFTWARE_KEYSTORE_FILE;
        String password = (keystorePassword != null) ? keystorePassword : "";

        // Resolved once and shared: resolution and the containment check below must agree on which
        // directories count as app-private, and getNoBackupFilesDir() is not guaranteed to return
        // the same value on two separate calls.
        List<File> roots = keystoreRoots();
        File keystoreFile = resolveKeystoreFile(filename, roots);

        // Containment check: the resolved path must stay within app-private storage, even for
        // absolute paths supplied across the RN bridge. Checked after resolution so neither the
        // caller-supplied path nor the no-backup fallback can escape via "..".
        if (!isInsideKeystoreRoot(keystoreFile, roots)) {
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
            // States what is true of this device rather than asking the reader to act: this line
            // lands in a field installer's device log, and only an app developer can change which
            // format react-native-ecc-csr writes. Phrase it as a finding they can grep for.
            Log.w(TAG, "Keystore is plain PKCS12, not EncryptedFile — the private key is protected "
                + "only by app-private storage, without Android Keystore-backed file encryption");
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