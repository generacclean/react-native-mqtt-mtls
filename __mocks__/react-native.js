/**
 * Mock for react-native module
 * Used in Jest tests to avoid requiring actual React Native
 */

const createEmitterInstance = () => ({
  addListener: jest.fn((eventType, listener, context) => ({
    remove: jest.fn(),
  })),
  removeAllListeners: jest.fn(),
  removeSubscription: jest.fn(),
});

const NativeEventEmitter = jest.fn((nativeModule) => {
  return createEmitterInstance();
});

module.exports = {
  NativeEventEmitter,
  Platform: {
    OS: 'ios',
    select: jest.fn((obj) => obj.ios || obj.default),
  },
  NativeModules: {},
};
