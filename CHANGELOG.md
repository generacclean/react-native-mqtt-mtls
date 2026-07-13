# Changelog

All notable changes to this project will be documented in this file.

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
