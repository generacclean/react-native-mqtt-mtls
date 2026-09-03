# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Fixed

- **Events from a superseded connection attempt were attributed to the current one**
  ([GREM-64](https://generacet.atlassian.net/browse/GREM-64))
  - A reconnect supersedes the previous client, but the old one keeps emitting: on Android the Paho
    client stays alive through its registered `BroadcastReceiver` and service binding regardless of
    our reference, and on iOS a superseded `CocoaMQTT` can still deliver a queued delegate call. The
    JS layer registers one listener per event and dispatches through a `config` that every
    `connect()` overwrites, so a late `connectionLost` or message landed on the new attempt's
    handlers — surfacing as a spurious disconnect, or a message routed to the wrong subscriber.
  - Every native event callback now carries the client that created it and emits only while that
    client is still the one the module owns. Android holds the check under the same `clientLock` as
    the teardown that clears the field, so a callback already queued on the main looper cannot pass
    the guard after its client has been retired; iOS uses an `NSRecursiveLock` around the same
    comparison.
  - The identity check is the enforcing control. Detaching the callback on teardown is a secondary
    measure and cannot stand alone — `MqttAndroidClient.setCallback` rejects null on this Kotlin
    fork, so the detach installs an inert callback rather than removing one, and it does nothing for
    a callback already in flight.
  - Not addressed here: the JS layer still has no attempt identity of its own, so correct routing
    rests on the native guards. iOS carries no executable coverage for the guards (no Xcode target
    compiles `MqttModule.swift`); Android does.

## [1.5.2] - 2026-08-27

### Fixed

- **iOS could not complete the MQTT TLS handshake against a real gateway ("certificate is not
  standards compliant")**
  - The handshake reached the trust delegate, which rejected the chain with
    `Trust evaluate failure: [leaf OtherTrustValidityPeriod]`. Android accepted the same
    certificates.
  - Root cause: `TrustValidator.evaluate` used `SecPolicyCreateSSL(true, nil)`, and the SSL policy
    enforces Apple's maximum lifetime rule for TLS server certificates — 398 days for anything
    issued after 2020-09-01 ([HT211025](https://support.apple.com/en-us/HT211025)). Broker leaves
    are long-lived device certificates, so the rule rejects a genuine gateway. Java PKIX has no such
    rule, which is why Android was unaffected.
  - The policy is now `SecPolicyCreateBasicX509()`, which still enforces chain construction,
    signature verification, expiry, basic constraints, and weak key/signature rejection. Neither
    policy carried a hostname check here (`nil` host); CN pinning remains the hostname-equivalent
    control.

### Known gap

- **iOS no longer enforces `id-kp-serverAuth` on the server leaf**
  ([IA-6160](https://generacet.atlassian.net/browse/IA-6160)). The SSL policy was the only thing
  checking extended key usage; basic X.509 does not check it. Because device client certificates
  chain to the same CA as the broker, one presented as the server would now be accepted whenever
  the CN pin is skipped — which is every production connection today, since the app forces admin
  mode. iOS server identity therefore rests on a single check: the chain reaches an app-supplied
  anchor. No hostname, no SAN, no CN, no EKU. Android still closes this in
  `CustomTrustManager.requireTlsServerCertificate`, so **iOS is the more permissive platform until
  IA-6160 lands** — weigh that before taking this bump.

## [1.5.1] - 2026-08-26

### Fixed

- **`cleanup` was missing from the JS module on iOS**
  - Every app start logged `The Objective-C cleanup:(RCTResponseSenderBlock)successCallback
errorCallback:(RCTResponseSenderBlock)errorCallback method signature for the JS method cleanup
can not be found in the Objective-C definition of the MqttModule module.` React Native then
    dropped the method, so `MqttModule.cleanup` was `undefined` on iOS.
  - Root cause: `RCT_EXTERN_MODULE` splits the module in two. `MqttModule.m` declared the selector
    `cleanup:errorCallback:`, while `MqttModule.swift` implemented `func cleanup(_ callback:)`,
    which exports `cleanup:`. React Native reads the declaration, asks the class for that selector,
    finds nothing, and skips the method. The mismatch is invisible at compile time because the two
    halves never reference each other.
  - The Swift signature now takes both callbacks, matching the declaration, the Android module, and
    the `MqttModuleType` interface. `errorCallback` stays unused because nothing in the cleanup path
    can fail, the same as `disconnect`.
  - Effect on callers: `MqttManager` and `MqttProvider` both guard with
    `typeof MqttModule.cleanup === "function"` and fall back to `disconnect`, which runs the same
    teardown, so iOS behaviour does not change. iOS now runs the intended path and stops warning.

- **`MqttModuleType` was declared twice and the two copies had drifted**
  - `src/MqttModule.ts` kept a private copy that listed `cleanup`; the published copy in `index.d.ts`
    did not. That is how the omission above reached consumers. `index.d.ts` already carried the rule
    it broke: re-export from `src/types.ts` to prevent type drift.
  - `MqttModuleType` now lives in `src/types.ts` only. `index.d.ts` re-exports it and
    `src/MqttModule.ts` imports it, so there is one declaration and nothing to drift.
  - The merge kept the correct half of each copy. `connect` keeps the two overloads from
    `src/MqttModule.ts`, because iOS takes 7 arguments and Android takes 10; the single 12-argument
    signature in `index.d.ts` rejected the iOS call. `publish` keeps `message: string` from
    `index.d.ts`, because `MqttManager` Base64-encodes binary before the call and the native side
    takes `NSString`; `string | Uint8Array` would have let a `Uint8Array` cross the bridge.

### Added

- `__tests__/native-bridge-parity.test.ts` reads `MqttModule.m`, `MqttModule.swift`, `src/types.ts`
  and the Android module as text and compares the exported selectors, the method sets and the
  argument counts. It fails on both bugs above.
- A `Jest` workflow, so the JavaScript suite runs on every pull request. Android and iOS trust
  validation already had one; JavaScript did not, so the parity test above would never have run.

### Changed

- `MqttManager.publish` types its local `publishMessage` as `string` instead of
  `string | Uint8Array`. Every branch already produced a string; the wider type needed an `as any`
  that hid the mismatch with the native `NSString` argument.

## [1.5.0] - 2026-08-24

### Added

- **`isRetained` on the inbound `MqttMessage` event**
  - The native modules dropped the MQTT retain flag when building the bridge event, so a consumer
    could not tell a live delivery from a replay the broker serves out of its retained store on
    subscribe. That replay may predate the current session entirely — a reboot, a factory reset, or
    another gateway — and a consumer treating it as an answer to a request it just sent will act on
    stale state.
  - Per MQTT 3.1.1 a broker clears the flag when forwarding to an already-established subscription
    and sets it only when serving from the retained store, so the flag is a reliable discriminator.
  - Forwarded on both platforms: Android reads `MqttMessage.isRetained()`, iOS reads
    `CocoaMQTTMessage.retained`. The field is optional, so consumers on older versions are
    unaffected and consumers that ignore it behave as before.

## [1.4.1] - 2026-08-19

### Fixed

- **Android could not reconnect after an ungraceful disconnect ("Client is closed", 32111)**
  - Every connect after the transport died — airplane mode toggled on and off, or the device leaving
    range of the gateway's access point — failed instantly with `Client is closed (32111)` thrown
    from `MqttAsyncClient.connect`, before any socket was opened. Certificates, keystore, and the
    TLS 1.3 context all built successfully; the client instance behind the connection was simply
    already closed. Nothing recovered it short of the user force-quitting the app, because the
    stranded instance lived in a service that dies only with the process.
  - Root cause: `MqttService` keys a cache of `MqttConnection` instances by
    `serverURI:clientId:packageName` and hands the cached instance to every `MqttAndroidClient`
    built from those same three values. `MqttService.disconnect()` removes the entry;
    `MqttService.close()` closes the underlying `MqttAsyncClient` and leaves the entry in place.
    Teardown only disconnected when `isConnected()` was true and closed unconditionally, so an
    ungraceful drop — where the client is already not connected — took the close-only path and
    stranded a permanently closed client under the handle the next connect resolves to. Consumers
    reuse a persisted `clientId` against a fixed broker URL precisely so the broker can queue
    messages across reconnects, which guarantees the same handle is resolved every time.
  - Teardown now always disconnects, whatever `isConnected()` reports, and never closes.
    `MqttConnection.disconnect()` tolerates a client that was never connected (it reports an error
    status rather than throwing) and the handle is evicted either way. `close()` is not called at
    all: after the disconnect the handle is gone, so it could only log an invalid-handle error.
    Nothing is left for it to release either — `MqttAsyncClient` opens its file persistence in its
    constructor and closes it again on the next line, taking the lock back only on connect, and
    `MqttDefaultFilePersistence.open()` swallows a lock failure anyway.
  - Teardown now also calls `unregisterResources()`, which releases the `BroadcastReceiver`
    registration and the bound service. `close()` released neither, so every teardown leaked both.
  - Teardown now runs when React Native destroys the module, via `invalidate()` and
    `onCatalystInstanceDestroy()`. The two are alternatives picked by the runtime, not a chain: below
    0.69 only the latter exists, on 0.83 `BaseJavaModule.invalidate()` is empty so only the former
    fires, and on the 0.71 this module compiles against `invalidate()` still delegates to
    `onCatalystInstanceDestroy()` so both do — which is why teardown has to be idempotent.
    A JS reload drops the module while
    `MqttService` keeps running, and it is the one teardown the app cannot request for itself, so
    without it a reload left the receiver registered, the service bound and the handle cached. The
    constructor no longer attempts a teardown: `client` is an instance field, always null in a fresh
    module, so that call could only return at its first check.
  - A connect that still fails with `REASON_CODE_CLIENT_CLOSED`, `REASON_CODE_CLIENT_CONNECTED` or
    `REASON_CODE_CLIENT_DISCONNECTING` now evicts its handle instead of leaving it for the next
    attempt to trip over. `ClientComms.connect()` throws all three from the same block, and none can
    be recovered by retrying against the same cached connection — `CLIENT_DISCONNECTING` is
    transient in plain Paho, where `shutdownConnection()` moves the state on to `DISCONNECTED`, but
    not here, because `MqttService.disconnect()` drops the map entry the moment it is called while
    the underlying disconnect is still running. `REASON_CODE_CONNECT_IN_PROGRESS` is deliberately
    excluded: it resolves on its own, and evicting there would tear down a healthy attempt.
  - A connect that throws after its client was installed now tears that client down instead of
    leaving a client that has already registered its receiver and bound the service for the next
    connect to overwrite.
  - A callback arriving after a new connection has replaced the client no longer tears down that
    replacement. Every teardown outside `invalidate()` now names the client it was issued for rather
    than reading whichever is current, because reading the field twice — once to check, once to tear
    down — was the whole problem: a `connect()` on the bridge thread can install a new client between
    the two reads. `releaseClientResources()` releases the receiver and binding of the client it is
    given and only forgets the field if that client is still the one in it.
  - `cleanupConnection(MqttAndroidClient)` issues its disconnect only while the client it was given is
    still the current one, since a client does not own its handle.
    `MqttAndroidClient.disconnect()` passes nothing but its handle string to the service, which
    resolves it against whichever `MqttConnection` is cached under it now — and a replacement built
    from the same broker URL and `clientId` shares that string. Issued from a stale client it would
    drop the replacement's live session and remove the entry the replacement still needs, after which
    every call on it throws `IllegalArgumentException("Invalid ClientHandle")`. Skipping it strands
    nothing, because the replacement owns the handle and evicts it in its own teardown. The check
    holds the same lock as the install in `connect()`, and holds it across the disconnect, so no
    client can be installed between the two; `volatile` alone would have given visibility without
    atomicity. That is safe because nothing in the call blocks or re-enters the module — Paho
    publishes its status with `Context.sendBroadcast()`, delivered later on the main-thread looper.
  - iOS is unaffected: CocoaMQTT holds its client directly, with no service-owned cache.

## [1.4.0] - 2026-08-12

> A minor bump rather than a patch: the public API is unchanged, but Android now rejects server
> certificates that 1.3.x accepted — any leaf that does not assert the `serverAuth` extended key
> usage — and this version must ship together with react-native-ecc-csr 1.4.0+. (Expiry and
> host-mismatch rejection landed earlier in this release line; the EKU check is what is new here.)

### Fixed

- **Server-certificate trust validation on Android and iOS**

  - **Android**: `CustomTrustManager.checkServerTrusted` validated the chain via hand-rolled per-issuer signature loops with no expiry check and no hostname/SAN matching, so a certificate validly issued by a trusted CA for one host was accepted when connecting to a different host, and expired certificates were accepted. Replaced with the platform `CertPathValidator` ("PKIX"), which enforces expiry, path-length, and basic-constraints, and added SAN matching against the expected SNI host.
  - **iOS**: `didReceive(trust:)` accepted any server certificate unconditionally in admin mode, and only did a CN string comparison otherwise — the app-provided root CA bundle was parsed but never evaluated against the presented chain. Now validates the chain via `SecTrustEvaluateWithError` against app-provided anchors unconditionally; admin mode only skips the CN pin, never chain validation.
  - On both platforms, chain validation always runs; identity pinning is skipped only when no expected value is configured (admin mode). The two platforms do not pin the same thing: Android matches the presented certificate's SANs (DNS and iPAddress) against the expected SNI host in addition to comparing CN, while iOS still only compares CN. `SecPolicyCreateSSL(true, nil)` is created without a hostname, so iOS performs no SAN check — closing that gap is tracked separately.
  - Both platforms now require the leaf to be a TLS server certificate, and this closes the EKU asymmetry the 1.3.x notes recorded as a known gap. iOS gets the check from `SecPolicyCreateSSL(true, nil)`; Android checks `getExtendedKeyUsage()` directly, because bare `PKIXParameters` validates that a certificate is genuine, not what it is for. Without it, a device's own _client_ certificate from the same CA — every device holds one — would be accepted as the broker whenever the CN and SAN pins are skipped, which is every production connection today.
  - Android requires `id-kp-serverAuth` (1.3.6.1.5.5.7.3.1) to be present explicitly. Two cases are rejected for parity with Apple's policy rather than because RFC 5280 demands it: a leaf with no `extendedKeyUsage` extension at all (unconstrained per RFC 5280) and a leaf asserting only `anyExtendedKeyUsage` (2.5.29.37.0). `SecPolicyCreateSSL(true, nil)` fails both with "Extended key usage does not match certificate usage", verified against the Security framework in `TrustValidatorTests` (`testLeafWithNoExtendedKeyUsage_Rejected`, `testLeafWithAnyExtendedKeyUsage_Rejected`, with a `serverAuth` control), so accepting either on Android would be a one-platform relaxation for a broker the iOS build cannot reach anyway.
  - What chain validation still does not check: revocation is off on both platforms (`params.setRevocationEnabled(false)` on Android; iOS does not opt in), because the private gateway CA publishes no CRL or OCSP responder, so a revoked broker certificate is accepted until it expires.
  - Residual risk, accepted: the installer app passes `isAdminUser: true` on every connection, so `expectedBrokerCN` and `expectedSniHost` are null in production and neither the CN nor the SAN pin runs today. What this release buys in production is chain validation — expiry, path length, basic constraints, and a signature chain to an app-provided anchor — which previously did not run at all on iOS. Any certificate issued by the trusted CA is still accepted for any host until the app stops forcing admin mode.

- **Handle absolute keystore paths from ecc-csr 1.4.0+ (Issue #21)**

  - `loadSoftwareKeyStore()` now uses `File.isAbsolute()` to distinguish absolute paths (used directly) from relative paths (resolved against `filesDir`)
  - Fixes path doubling bug where absolute paths like `/data/user/0/com.app/files/software_keys.p12` were incorrectly prepended with `filesDir`, resulting in `/data/user/0/com.app/files/data/user/0/com.app/files/software_keys.p12`
  - Backward compatible: relative paths, null, and empty defaults behave unchanged
  - Related: react-native-ecc-csr 1.4.0 now returns explicit keystore descriptors with absolute paths
  - Resolved keystore paths (absolute or relative) are now checked to remain inside app-private storage before being opened

- **Load the keystore from `getNoBackupFilesDir()` (ecc-csr 1.4.0+)**

  - react-native-ecc-csr 1.4.0 moved the software keystore from `getFilesDir()` to `getNoBackupFilesDir()` so the private key is excluded from Android Auto Backup unconditionally, instead of depending on the consuming app's `fullBackupContent`/`dataExtractionRules` configuration
  - `loadSoftwareKeyStore()` now resolves relative/default paths against `getNoBackupFilesDir()` first and falls back to `getFilesDir()`, so devices that have not yet run the ecc-csr migration keep working
  - An absolute path that no longer exists is retried by filename under both directories. The installer persists `keystorePath` across launches, so immediately after upgrading it supplies a `files/` path for a keystore ecc-csr has already moved; without this fallback the first post-upgrade connect would fail
  - The app-private containment check now accepts either directory, and is applied after resolution so neither a caller-supplied path nor the fallback can escape via `..`
  - Requires ecc-csr 1.4.0+ to be shipped together with this version

- **Parse multi-valued RDNs when reading the broker CN**
  - The RFC 2253 DN parser split on unescaped commas but not on the unescaped `+` that joins the attributes of a multi-valued RDN, so `CN=penguin-broker.local+OU=field` read back as `penguin-broker.local+OU=field` and failed the CN pin on a certificate that should have matched. Fail-closed either way (a spurious mismatch, never a false accept), and the CN pin does not run in production today, but the parser now handles both separators

### Documentation

- **`sniHostname` is Android-only certificate verification**
  - The JSDoc on `MqttConnectionConfig.sniHostname` and the README feature list promised hostname verification without naming a platform. Android matches the value against the certificate's subjectAltName entries (DNS and iPAddress, exact, no wildcards) and never sends it as the TLS SNI extension; iOS only announces it as `kCFStreamSSLPeerName` and performs no SAN check, because its policy is built with a nil hostname. Both surfaces now say so, instead of leaving the split documented only in release notes
- **`docs/RUNNING_TESTS.md` rewritten against the test infrastructure that exists**
  - It described iOS tests as needing an Xcode project that must be created by hand, and listed only the binary-detection and callback suites. It now documents `swift test` and the `TrustValidation` SwiftPM package, the two CI workflows, the JDK 17 requirement for `./gradlew test`, the trust and path-resolution suites, and — still accurately — that `ios/MqttModuleTests.swift` alone needs a host app
  - `yarn test:ios` now runs `swift test` instead of printing a pointer to that document and exiting 1

### Tests

- iOS `TrustValidatorTests` covers what Apple's SSL policy does with each `extendedKeyUsage` value, using four leaves from one CA that differ in nothing else: `serverAuth` accepted as the control, `clientAuth`, no EKU extension, and `anyExtendedKeyUsage` all rejected. This is the evidence for the Android EKU decision above rather than an assumption about it
- Android `CustomTrustManagerTest` covers the empty-trust-store guard (`No trusted CA certificates configured`), which had no test on Android although iOS covered the symmetric case, plus the no-EKU and anyExtendedKeyUsage rejections and multi-valued RDN parsing
- Android `MqttModulePathResolutionTest` covers a null `getNoBackupFilesDir()` — declared `@Nullable`, previously always mocked to a real directory — so the fallback to `filesDir` for resolution and containment is exercised

## [1.3.2] - 2026-08-05

> `1.3.1` is skipped intentionally: it is claimed by the concurrent keystore-path branch, whose `1.3.1` betas are already published and pinned by consumers.

### Fixed

- **Classify `/network/` topics as binary so protobuf responses parse (GREM-50)**
  - `isBinaryData` classified network config/state responses as text because their topics contain `/config`, then lossily UTF-8-decoded the payload (U+FFFD for bytes > 0x7F), corrupting the protobuf before JS received it
  - Adds `/network/` to the binary branch ahead of the `/config` text rule on both iOS and Android, so these payloads are base64-encoded losslessly
  - `/network/` requires the trailing slash, so `penguin/config/network` still classifies as text

## [1.3.0] - 2026-07-20

### ⚠️ BREAKING CHANGE

- **`isAdminUser` now defaults to `false` (secure-by-default)**
  - **Previous behavior**: Defaulted to `true`, skipping SNI and CN verification
  - **New behavior**: Defaults to `false`, enforcing full certificate verification
  - **Why**: Addresses security review feedback - insecure mode should not be the default
  - **Migration**: Consumers must either:
    1. Explicitly pass `isAdminUser: true` to maintain insecure behavior (dev/test only)
    2. Provide `sniHostname` and `brokerCommonName` for secure production use
  - **Cross-repo coordination**: installer-app updated to explicitly pass `isAdminUser: true`
  - **See**: `docs/SECURITY_DEFAULT_CHANGE.md` for detailed migration guide

### Added

- **Security documentation and test coverage for `isAdminUser` parameter**

  - Added comprehensive security warnings in `src/types.ts` JSDoc
  - Added "Security Considerations" section to `README.md` with production/dev examples
  - Added 20 test cases in `__tests__/isAdminUser-default.test.ts`
  - Documents the security implications of admin mode vs secure mode

- **Comprehensive test coverage for binary detection**

  - Android: `MqttBinaryDetectionTest.java` uses reflection to test real `isBinaryData(String topic, byte[] payload)` method
  - iOS: `MqttModuleTests.swift` now tests real `isBinaryData(topic:data:)` method (made `internal` for testing)
  - Topic collision tests: Verify binary-first precedence (`/device/config` → binary, not text)
  - ASCII protobuf edge case tests: Document known limitation for unknown topics
  - Over 40 test cases covering topic patterns, UTF-8 heuristic, and edge cases

- **Encrypted keystore support for CSR module compatibility**
  - Dual-format keystore loading: Tries encrypted format first, falls back to plain PKCS12
  - AndroidX Security integration: `androidx.security:security-crypto:1.1.0`
  - Hardware-backed AES256-GCM master key encryption
  - Clear error messages for troubleshooting keystore issues

### Changed

- **iOS `isBinaryData` method visibility**: Changed from `private` to `internal` for testing
- **BouncyCastle provider initialization**: Refactored with double-checked locking pattern
  - Static `FULL_BC_PROVIDER` reference to avoid Android's stripped-down BC provider
  - Position 1 (highest priority) insertion to ensure our BC provider is used
  - Thread-safe initialization with volatile flag and synchronized block

### Fixed

- **Topic pattern documentation**: Added notes about asymmetric publish/receive handling
- **Test coverage gaps**: Eliminated reimplemented test helpers in favor of real method testing
- **Documentation sync**: Added comments to keep Android/iOS topic patterns in sync

### Technical Details

**Android Layer (`android/src/main/java/com/reactnativemqttmtls/MqttModule.java`):**

- Line 49-91: Enhanced `isBinaryData()` documentation with asymmetry notes and sync reminders
- Line 1020-1114: New `loadSoftwareKeyStore()` with dual-format support
- Line 44-46: Static `FULL_BC_PROVIDER` to avoid system BC conflicts

**iOS Layer (`ios/MqttModule.swift`):**

- Line 14-28: Enhanced `isBinaryData()` documentation and changed to `internal` visibility
- Line 28-65: Topic-based binary detection (matches Android patterns)

**Test Coverage (`android/src/test/java/com/reactnativemqttmtls/MqttBinaryDetectionTest.java`):**

- 464 lines of comprehensive binary detection tests
- Uses reflection to access private `isBinaryData(String, byte[])` method
- Tests all topic patterns, collision cases, and ASCII protobuf edge cases

**Test Coverage (`ios/MqttModuleTests.swift`):**

- Updated to test real `MqttModule.isBinaryData(topic:data:)` method
- Removed duplicate single-arg helper method
- Added topic collision and ASCII protobuf edge case tests

### Migration

**BREAKING CHANGE - `isAdminUser` default**:

- If your code does NOT pass `isAdminUser` and relies on the default, you must update:
  - **Option 1**: Add `isAdminUser: true` (for dev/test environments only)
  - **Option 2**: Add `sniHostname` and `brokerCommonName` (recommended for production)
- See `docs/SECURITY_DEFAULT_CHANGE.md` for detailed migration steps

**Non-breaking enhancements**:

- Encrypted keystore is optional (falls back to plain PKCS12)
- Topic patterns match existing production usage
- Binary detection behavior unchanged for known topics
- Test improvements are internal only

## [1.2.0] - 2026-07-13

### Fixed

- **CRITICAL:** Callback double-invocation crashes on Android and iOS

  - **Root Cause:** Network disconnects or publish failures could trigger both success and error callbacks
  - **Impact:** App crashes (SIGABRT) from React Native bridge's single-fire invariant violation
  - **Solution:** Added callback guards (`safeInvoke` on Android, `CallbackGuard` on iOS) with atomic flags
  - **Additional Fix (iOS):** Connect callbacks now use shared settled state for mutual exclusion (matches Android behavior)

- **CRITICAL:** Type declaration mismatch in `index.d.ts`

  - **Root Cause:** Public API declared `message: string | ArrayBuffer`, but runtime delivers `string | Uint8Array`
  - **Impact:** TypeScript consumers receive wrong types, breaking `.length`, index access, `Buffer.from()`
  - **Solution:** Changed type to `message: string | Uint8Array` to match actual runtime behavior

- **CRITICAL:** Binary message detection failures for ASCII-range protobufs
  - **Root Cause:** UTF-8 heuristic alone misclassifies small protobufs with ASCII content as text
  - **Impact:** Intermittent payload-dependent parse failures in gateway transport handlers
  - **Solution:** Added topic-based deterministic detection for known binary/text patterns (protobuf, firmware, JSON topics)
  - **Fallback:** UTF-8 validity check for unknown topics (with warning in code comments)

### Changed

- Binary detection now uses topic patterns first (deterministic), then UTF-8 heuristic (fallback)
- iOS connect callbacks use mutual exclusion to prevent both success and error from firing
- Documentation clarified that protobuf varint encoding is correctly detected

### Technical Details

**Android Layer (`android/src/main/java/com/reactnativemqttmtls/MqttModule.java`):**

- Line 67-114: Rewrote `isBinaryData()` with topic-based detection
- Line 124-137: Added `safeInvoke()` for callback double-invocation prevention

**iOS Layer (`ios/MqttModule.swift`):**

- Line 28-70: Rewrote `isBinaryData()` with topic-based detection
- Line 134-168: Added shared settled guard for connect callbacks (mutual exclusion)

**TypeScript Layer (`index.d.ts`):**

- Line 6: Changed `message: string | ArrayBuffer` → `message: string | Uint8Array`

## [1.1.1] - 2026-06-25

### Fixed

- **CRITICAL BUG FIX:** Fixed binary encoding issue where JSON strings were incorrectly decoded as Base64
  - **Root Cause:** Native modules attempted to Base64 decode all incoming strings, causing JSON payloads that happened to be valid Base64 to be decoded into garbage binary data
  - **Impact:** MQTT message parsing failures in receivers (e.g., Penguin gateway would receive binary blob instead of JSON, causing "expected value at line 1 column 1" parse errors)
  - **Solution:** Added `"B64:"` prefix marker to distinguish intentional Base64-encoded binary from plain text
  - **Affected Platforms:** Android and iOS

### Changed

- Binary messages now include `"B64:"` prefix before Base64 encoding (4 bytes overhead)
- Publish method logs now clearly indicate whether message is binary or text
- Android: Added debug logs showing payload type and size
- iOS: Updated log messages to distinguish marked Base64 vs UTF-8 text

### Technical Details

**JavaScript Layer (`src/MqttManager.ts`):**

- Line 266: Added `"B64:"` prefix when encoding binary data to Base64

**Android Layer (`android/src/main/java/com/reactnativemqttmtls/MqttModule.java`):**

- Line 660-672: Changed from blind Base64 decode attempt to prefix-based detection
- Now checks for `"B64:"` prefix before attempting Base64 decode
- Plain text (no prefix) is sent as UTF-8 bytes

**iOS Layer (`ios/MqttModule.swift`):**

- Line 352-379: Same prefix-based detection as Android
- Checks `hasPrefix("B64:")` before Base64 decoding
- Plain text sent as UTF-8 bytes

### Migration

**No breaking changes for consumers** - this is a transparent bug fix:

- Binary messages will be slightly larger (+4 bytes for `"B64:"` prefix)
- Text messages now correctly sent as UTF-8 (previously might be decoded as binary if accidentally valid Base64)
- Receivers expecting JSON will now get proper JSON text instead of binary garbage

### Testing

To verify the fix is working, check native logs:

**Android (logcat):**

```
[MqttModule] Publish: using UTF-8 text (197 bytes) for topic: ia/jobs/notify
```

**iOS (Console.app):**

```
✓ Published UTF-8 text data (197 bytes)
```

If you see "decoded marked Base64" for JSON messages, the bug still exists.

## [1.1.0] - Previous Release

- Initial release with mTLS support
