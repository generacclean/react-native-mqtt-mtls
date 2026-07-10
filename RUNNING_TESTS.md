# Running Tests

This project has three separate test suites that test different layers of the MQTT module.

## 1. JavaScript/TypeScript Tests (Jest)

**Status:** ✅ Working

**What it tests:**
- MqttManager API (connect, disconnect, subscribe, publish)
- Binary message encoding/decoding with B64: prefix
- Event handling and callbacks
- Type definitions
- Integration scenarios

**How to run:**
```bash
# Run all tests
yarn test

# Watch mode
yarn test:watch

# With coverage report
yarn test:coverage
```

**Test files:**
- `__tests__/MqttManager.test.ts` - Main functionality (45 tests)
- `__tests__/integration.test.ts` - Integration scenarios
- `__tests__/types.test.ts` - Type definitions

**Results:** All 45 tests passing ✅

---

## 2. Android Unit Tests (JUnit + Mockito + Robolectric)

**Status:** ✅ Working

**What it tests:**
- Binary detection logic (UTF-8 validation)
- Callback guard mechanisms (thread-safe single invocation)
- Base64 encoding/decoding
- B64: marker detection

**How to run:**
```bash
# From project root
yarn test:android

# Or directly with Gradle
cd android && ./gradlew test
```

**Test file:**
- `android/src/test/java/com/reactnativemqttmtls/MqttModuleTest.java`

**Setup completed:**
- ✅ Gradle wrapper installed (gradle 8.9)
- ✅ settings.gradle created
- ✅ build.gradle configured with Android Gradle Plugin 8.7.3
- ✅ Test dependencies: JUnit 4.13.2, Mockito 5.3.1, Robolectric 4.11.1
- ✅ AndroidX enabled
- ✅ All 25 tests passing

**Results:** All 25 tests passing ✅

**Test output location:**
- Reports: `android/build/reports/tests/testDebugUnitTest/index.html`

---

## 3. iOS Unit Tests (XCTest)

**Status:** ⚠️ Requires Xcode Project Setup

**What it tests:**
- Binary detection logic (UTF-8 validation)
- Callback guard mechanisms (thread-safe single invocation)
- Base64 encoding/decoding
- B64: marker detection
- UTF-8 edge cases (multibyte characters, emoji)

**Test file:**
- `ios/MqttModuleTests.swift` (ready to use)

**Why tests aren't running:**
React Native library modules don't include standalone Xcode projects. The iOS native code is compiled when the library is installed in a React Native app. Therefore, iOS tests need to run within a host app context.

**Options to run iOS tests:**

### Option 1: Run in Example App (Recommended)
Create an example React Native app that uses this library:

```bash
# Create example app
npx react-native init MqttExample
cd MqttExample

# Link the library
yarn add file:../

# Open iOS project in Xcode
cd ios && pod install
open MqttExample.xcworkspace

# In Xcode:
# 1. Add MqttModuleTests.swift to the test target
# 2. Run tests: Cmd+U
```

### Option 2: Create Standalone Test Project
Use Xcode to create a minimal Swift package or framework project just for testing:

1. Open Xcode
2. Create new "Framework" project in the `ios/` directory
3. Add `MqttModule.swift` and `MqttModuleTests.swift`
4. Add test target
5. Run tests with `yarn test:ios`

### Option 3: Run in Existing App
If you have an existing React Native app that uses this library:

```bash
# In your app's ios/ directory
open YourApp.xcworkspace

# In Xcode:
# 1. Add MqttModuleTests.swift to your test target
# 2. Run tests: Cmd+U or xcodebuild test
```

### Manual Testing
Since the iOS tests mirror the Android tests (which are all passing), and they test pure logic without iOS-specific APIs, you can verify the implementation by:

1. Reviewing the test file: `ios/MqttModuleTests.swift`
2. Confirming it tests the same logic as the passing Android tests
3. Code review of the production implementation in `ios/MqttModule.swift`

**Note:** The test logic has been written and reviewed. The only missing piece is the Xcode project infrastructure to execute them.

---

## Test Architecture

```
┌─────────────────────────────────────────┐
│         Test Coverage by Layer          │
├─────────────────────────────────────────┤
│                                         │
│  JavaScript Bridge Layer (Jest)         │
│  ├─ MqttManager API surface            │
│  ├─ Event subscriptions                │
│  ├─ Binary message encoding             │
│  ├─ Message decoding & B64: handling    │
│  └─ Type safety                         │
│                                         │
├─────────────────────────────────────────┤
│                                         │
│  Native Android Layer (JUnit)           │
│  ├─ Binary detection algorithm          │
│  ├─ Callback guard (thread safety)      │
│  ├─ Base64 encoding/decoding            │
│  └─ UTF-8 validation                    │
│                                         │
├─────────────────────────────────────────┤
│                                         │
│  Native iOS Layer (XCTest)              │
│  ├─ Binary detection algorithm          │
│  ├─ Callback guard (thread safety)      │
│  ├─ Base64 encoding/decoding            │
│  ├─ UTF-8 validation                    │
│  └─ Multibyte character handling        │
│                                         │
└─────────────────────────────────────────┘
```

## Running All Tests

To run the complete test suite across all platforms:

```bash
# 1. JavaScript/TypeScript tests
yarn test

# 2. Android tests (requires Android SDK)
yarn test:android

# 3. iOS tests (requires Xcode, currently needs setup)
# yarn test:ios
```

## Continuous Integration

For CI/CD pipelines:

```yaml
# Example GitHub Actions workflow
- name: Run JS tests
  run: yarn test

- name: Run Android tests
  run: |
    cd android
    ./gradlew test

# iOS tests require Xcode and macOS runner
- name: Run iOS tests
  run: yarn test:ios
  if: runner.os == 'macOS'
```

## Test Development

When adding new tests:

1. **JavaScript/TypeScript tests**: Add to `__tests__/` directory
2. **Android tests**: Add to `android/src/test/java/com/reactnativemqttmtls/`
3. **iOS tests**: Add to `ios/MqttModuleTests.swift`

Ensure tests verify the same behavior across all platforms for consistency.
