# Changelog

All notable changes to this project will be documented in this file.

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
