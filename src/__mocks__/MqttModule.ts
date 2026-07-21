/**
 * Mock for MqttModule
 * Placed in __mocks__ directory for automatic mocking
 */

const MqttModule = {
  connect: jest.fn((broker, clientId, certs, sni, ip, cn, isAdmin, success, error) => {
    // Default: call success callback
    if (success) success('Connected');
  }),
  disconnect: jest.fn((success, error) => {
    if (success) success('Disconnected');
  }),
  subscribe: jest.fn((topic, qos, success, error) => {
    if (success) success(`Subscribed to ${topic}`);
  }),
  unsubscribe: jest.fn((topic, success, error) => {
    if (success) success(`Unsubscribed from ${topic}`);
  }),
  publish: jest.fn((topic, message, qos, retained, success, error) => {
    if (success) success(`Published to ${topic}`);
  }),
  cleanup: jest.fn((success, error) => {
    if (success) success('Cleaned up');
  }),
  // NativeModule interface properties for NativeEventEmitter
  addListener: jest.fn(),
  removeListeners: jest.fn(),
};

export default MqttModule;
