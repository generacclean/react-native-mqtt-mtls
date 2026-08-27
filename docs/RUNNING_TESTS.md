# Running Tests

This project has three test suites. All three run from the command line with no Xcode project and
no device; two of them run in CI on every pull request.

| Suite | Command | CI workflow |
| --- | --- | --- |
| JavaScript/TypeScript (Jest) | `yarn test` | not yet wired |
| Android native (JUnit + Mockito + Robolectric) | `yarn test:android` | `.github/workflows/android-tests.yml` |
| iOS trust validation (XCTest via SwiftPM) | `yarn test:ios` | `.github/workflows/ios-trust-tests.yml` |

The one thing still not executable anywhere is `ios/MqttModuleTests.swift` — see
[iOS: what `swift test` does not cover](#ios-what-swift-test-does-not-cover).

## 1. JavaScript/TypeScript Tests (Jest)

**What it tests:**
- MqttManager API (connect, disconnect, subscribe, publish)
- Binary message encoding/decoding with the `B64:` prefix
- Event handling and callbacks
- Type definitions
- Integration scenarios

**How to run:**
```bash
yarn test              # all tests
yarn test:watch        # watch mode
yarn test:coverage     # with coverage report
```

**Test files:** `__tests__/` — `MqttManager.test.ts`, `integration.test.ts`, `types.test.ts`, and
the connect-argument suites. 154 tests across 6 suites.

---

## 2. Android Unit Tests (JUnit + Mockito + Robolectric)

**How to run:**
```bash
yarn test:android          # or: cd android && ./gradlew test
```

Requires JDK 17 (the version CI uses). A newer JDK fails the Kotlin plugin with
`Unknown Kotlin JVM target`; point `JAVA_HOME` at 17 if your default is newer.

**Test files** (`android/src/test/java/com/reactnativemqttmtls/`):

- `CustomTrustManagerTest.java` — server-certificate trust validation: PKIX chain validation
  (expiry, path length, basic constraints, signature to a configured anchor), the extended-key-usage
  check that rejects a leaf which is not a TLS server certificate, SNI host/SAN matching, CN
  pinning, and RFC 2253 DN parsing. Fixtures are a real EC P-384 chain generated with openssl,
  including an expired leaf, a wrong-host leaf, a self-signed impostor carrying the broker's CN, and
  a second CA whose leaves differ only in `extendedKeyUsage`.
- `MqttModulePathResolutionTest.java` — keystore path resolution: absolute vs relative paths, the
  `getNoBackupFilesDir()`/`getFilesDir()` preference order and fallback, and the app-private
  containment check that rejects traversal.
- `MqttBinaryDetectionTest.java` — binary vs text payload classification (UTF-8 validation,
  topic rules).
- `MqttModuleTest.java` — connection lifecycle: teardown always disconnecting and never closing (the
  "Client is closed" 32111 reconnect fix), teardown targeting the client it was issued for rather
  than whichever is current, teardown on module destroy via `invalidate()`, each of `disconnect()`'s
  paths, and which connect failures mean the cached handle is unusable. Also callback guards
  (thread-safe single invocation), Base64 handling, and `B64:` marker detection.

109 tests per build variant, run for both `debug` and `release`.

**Test reports:** `android/build/reports/tests/testDebugUnitTest/index.html`

---

## 3. iOS Trust Validation Tests (XCTest via SwiftPM)

**How to run:**
```bash
yarn test:ios          # or: swift test
```

Requires a macOS host with the Swift toolchain (Xcode command-line tools). No `.xcodeproj`, no
simulator, no host app.

`Package.swift` in the repository root defines a `TrustValidation` library target
(`ios/TrustValidation/TrustValidator.swift`) and a `TrustValidationTests` test target
(`ios/TrustValidationTests/TrustValidatorTests.swift`). The trust logic is deliberately kept free of
React Native and CocoaMQTT so SwiftPM can build it on its own; `MqttModule.swift`'s
`didReceive(trust:)` delegate is a thin wrapper over `TrustValidator.evaluate`.

**What it tests** (15 tests): chain validation against app-provided anchors with system roots
excluded, rejection of a self-signed impostor and of an expired leaf, fail-closed behaviour when no
anchors are configured, CN pinning (match, mismatch, and skipped when no CN is configured), and the
four `extendedKeyUsage` cases the Android EKU check is written around — `serverAuth` accepted, and
`clientAuth`, no EKU extension, and `anyExtendedKeyUsage` all rejected.

Those last three do **not** describe current iOS behaviour. `TrustValidator` uses
`SecPolicyCreateBasicX509()`, which performs no EKU check, so all three are marked
`XCTExpectFailure` pending [IA-6160](https://generacet.atlassian.net/browse/IA-6160). They are kept
as the specification: `swift test` reports them as expected failures today, and they flip to a hard
"unexpectedly passed" the moment the check is reinstated.

The fixtures are the same openssl-generated chain as the Android suite, so a change in one platform's
expectations is visible against the other's.

### iOS: what `swift test` does not cover

`ios/MqttModuleTests.swift` — binary detection, callback guards, Base64/UTF-8 handling — has no
target and does not compile in this repository. It covers `MqttModule.swift`, which imports React
Native and CocoaMQTT and is built by CocoaPods via the podspec, so running it needs a host app:

```bash
# In a React Native app that consumes this library
cd ios && pod install
open YourApp.xcworkspace
# Add ios/MqttModuleTests.swift to the app's test target, then Cmd+U
```

The Android equivalents of those tests (`MqttModuleTest.java`, `MqttBinaryDetectionTest.java`) do
run, and the logic is mirrored, so the gap is iOS-specific coverage of shared logic rather than
untested behaviour overall.

---

## Test Architecture

```
┌─────────────────────────────────────────────┐
│           Test Coverage by Layer            │
├─────────────────────────────────────────────┤
│  JavaScript Bridge Layer (Jest) ✅ runs      │
│  ├─ MqttManager API surface                 │
│  ├─ Event subscriptions                     │
│  ├─ Binary message encoding / decoding      │
│  └─ Type safety                             │
├─────────────────────────────────────────────┤
│  Native Android Layer (JUnit) ✅ runs in CI  │
│  ├─ Server-certificate trust validation     │
│  ├─ Keystore path resolution + containment  │
│  ├─ Binary detection algorithm              │
│  └─ Callback guard (thread safety)          │
├─────────────────────────────────────────────┤
│  Native iOS Layer                           │
│  ├─ Trust validation (XCTest) ✅ runs in CI  │
│  └─ Binary detection / callback guards      │
│     (needs a host app target) ⚠️             │
└─────────────────────────────────────────────┘
```

## Running Everything Locally

```bash
yarn test            # JavaScript/TypeScript
yarn test:android    # Android native (JDK 17)
yarn test:ios        # iOS trust validation (macOS)
```

## Continuous Integration

Two workflows gate pull requests to `main`:

- `.github/workflows/android-tests.yml` — JDK 17 on `ubuntu-latest`, runs
  `./gradlew test --no-daemon` and uploads the HTML reports as an artifact.
- `.github/workflows/ios-trust-tests.yml` — `macos-14`, runs `swift test`.

The Jest suite is not yet wired into CI.

## Test Development

When adding new tests:

1. **JavaScript/TypeScript**: add to `__tests__/`
2. **Android**: add to `android/src/test/java/com/reactnativemqttmtls/`
3. **iOS trust logic**: add to `ios/TrustValidationTests/` — it runs in CI, so prefer putting new
   iOS coverage here and keeping the code under test free of React Native imports
4. **iOS module logic**: `ios/MqttModuleTests.swift`, understanding that it only runs inside a host
   app

Certificate fixtures are inline PEM strings rather than resource files so both native suites stay
self-contained. When you add one, generate it with openssl and say in a comment what makes it
different from the others — the EKU fixtures, for example, are identical apart from that one
extension.
