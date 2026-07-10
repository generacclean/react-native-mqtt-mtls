# Test Suite README

Quick reference for running and understanding the test suite for react-native-mqtt-mtls.

## Quick Start

```bash
# Install dependencies
npm install

# Run all tests
npm test

# Run with coverage
npm run test:coverage

# Watch mode for development
npm run test:watch
```

## Test Structure

```
__tests__/
├── setup.ts                  # Test environment setup (btoa/atob polyfills)
├── MqttManager.test.ts      # MqttManager class unit tests (25 tests)
├── types.test.ts            # TypeScript type validation (12 tests)
└── integration.test.ts      # End-to-end integration tests (15 tests)
```

## What's Being Tested

### 1. MqttManager.test.ts (25 tests)

Tests the main JavaScript API for MQTT operations:

- **Binary Message Handling (9 tests)**
  - Uint8Array → Base64 + B64: prefix
  - ArrayBuffer → Base64 + B64: prefix
  - Text messages (no prefix)
  - JSON stringification
  - Empty data handling
  - Large data (1MB+)

- **Binary Message Decoding (4 tests)**
  - Base64 → Uint8Array
  - Text messages as strings
  - Invalid Base64 handling

- **Connection Management (2 tests)**
  - Connect with config
  - Connection error handling
  - Disconnect

- **Subscription Management (2 tests)**
  - Subscribe to topics
  - Unsubscribe from topics

- **Event Handling (2 tests)**
  - onConnect callback
  - onConnectionLost callback

- **QoS & Retained (2 tests)**
  - QoS levels (0, 1, 2)
  - Retained message flag

- **Cleanup (1 test)**
  - Resource cleanup

### 2. types.test.ts (12 tests)

Validates TypeScript type definitions match runtime:

- **MqttMessage Type (4 tests)**
  - String messages
  - Uint8Array messages
  - Optional isBinary flag
  - QoS levels

- **MqttCertificates Type (2 tests)**
  - Required fields
  - Hardware key support

- **MqttConfig Type (2 tests)**
  - Required fields
  - Optional callbacks
  - Admin vs non-admin

- **Type Guards (4 tests)**
  - typeof string check
  - instanceof Uint8Array check
  - Union type handling
  - Buffer.from() compatibility

### 3. integration.test.ts (15 tests)

End-to-end message flow tests:

- **Binary Round-Trip (2 tests)**
  - Protobuf-like data
  - Firmware-sized data (1MB+)

- **Text Message Handling (2 tests)**
  - JSON without B64: prefix
  - JSON that's valid Base64 (the bug PR #4 fixes!)

- **Mixed Messages (1 test)**
  - Alternating binary/text

- **Memory Efficiency (1 test)**
  - Base64 vs hex size comparison

- **Edge Cases (4 tests)**
  - Empty binary/text
  - Null bytes
  - All byte values (0-255)

- **Type Safety (2 tests)**
  - Type guards
  - TextDecoder compatibility

## Running Specific Tests

```bash
# Run single test file
npm test -- MqttManager.test.ts

# Run tests matching pattern
npm test -- --testNamePattern="Binary"

# Run specific test
npm test -- --testNamePattern="should encode Uint8Array"

# Run in debug mode
node --inspect-brk node_modules/.bin/jest --runInBand
```

## Coverage Reports

```bash
# Generate coverage
npm run test:coverage

# View HTML report
open coverage/lcov-report/index.html
```

## Expected Coverage

| File | Lines | Functions | Branches | Statements |
|------|-------|-----------|----------|------------|
| src/MqttManager.ts | 85%+ | 90%+ | 80%+ | 85%+ |
| src/types.ts | 100% | N/A | N/A | 100% |

## Test Environment

Tests run in Node.js with:
- **ts-jest** for TypeScript compilation
- **btoa/atob polyfills** for Base64 operations
- **Mocked React Native modules** (NativeEventEmitter, Platform)
- **Mocked MqttModule** (native bridge)

## Key Test Patterns

### Testing Binary Encoding

```typescript
const binaryData = new Uint8Array([0x48, 0x65, 0x6C, 0x6C, 0x6F]); // "Hello"
await mqttManager.publish('test/topic', binaryData);

expect(mockPublish).toHaveBeenCalledWith(
  'test/topic',
  'B64:SGVsbG8=', // Base64 encoded with B64: prefix
  1,
  false,
  expect.any(Function),
  expect.any(Function)
);
```

### Testing Message Reception

```typescript
const mockCallback = jest.fn();
mqttManager.connect({ onMessage: mockCallback, ... });

// Simulate native event
const messageListener = getNativeEventListener('MqttMessage');
messageListener({
  topic: 'test/topic',
  message: 'SGVsbG8=', // Base64 of "Hello"
  isBinary: true,
  qos: 1,
});

expect(mockCallback).toHaveBeenCalled();
const receivedMessage = mockCallback.mock.calls[0][0];
expect(receivedMessage.message).toBeInstanceOf(Uint8Array);
```

### Testing Type Safety

```typescript
const message: MqttMessage = {
  topic: 'test',
  message: new Uint8Array([1, 2, 3]),
  qos: 1,
};

if (message.message instanceof Uint8Array) {
  // TypeScript knows message.message is Uint8Array here
  expect(message.message.byteLength).toBe(3);
}
```

## Debugging Failed Tests

### Test Timeout

If tests hang:
```bash
# Increase timeout
npm test -- --testTimeout=10000

# Run in band (no parallel)
npm test -- --runInBand
```

### Mock Issues

If mocks aren't working:
```bash
# Clear Jest cache
npm test -- --clearCache

# Check mock setup in setup.ts
cat __tests__/setup.ts
```

### TypeScript Errors

If type errors occur:
```bash
# Check tsconfig
cat tsconfig.json

# Ensure @types/jest installed
npm install --save-dev @types/jest
```

## Common Test Failures

### "btoa is not defined"

**Cause:** Running in Node.js without polyfill  
**Fix:** Ensure `__tests__/setup.ts` is loaded (check jest.config.js)

### "Cannot find module 'react-native'"

**Cause:** React Native not mocked  
**Fix:** Check mock setup in test file header

### "Callback not invoked"

**Cause:** Async operation not awaited  
**Fix:** Use `await` or `waitFor()` for async operations

## Adding New Tests

1. **Create test file:** `__tests__/YourFeature.test.ts`
2. **Import dependencies:**
   ```typescript
   import { MqttManager } from '../src/MqttManager';
   import type { MqttConfig } from '../src/types';
   ```
3. **Mock React Native modules** (see existing tests)
4. **Write tests:**
   ```typescript
   describe('Your Feature', () => {
     it('should do something', () => {
       expect(true).toBe(true);
     });
   });
   ```
5. **Run tests:** `npm test -- YourFeature.test.ts`
6. **Verify coverage:** `npm run test:coverage`

## Continuous Integration

These tests should run on every:
- Pull request
- Push to main branch
- Pre-commit hook (optional)

Example GitHub Actions:
```yaml
- name: Run Tests
  run: npm test
- name: Upload Coverage
  uses: codecov/codecov-action@v3
  with:
    files: ./coverage/lcov.info
```

## Related Documentation

- **Full Testing Guide:** `../TESTING.md`
- **Test Summary:** `../TEST_SUMMARY.md`
- **Code Review:** `../PR_4_LOCAL_CODE_REVIEW.md`
- **Architecture:** `../MQTT_ARCHITECTURE.md`

## Support

For test-related issues:
1. Check `TESTING.md` for detailed troubleshooting
2. Review test output carefully
3. Run with `--verbose` flag for more details
4. Check that mocks are set up correctly

---

**Happy Testing!** 🧪
