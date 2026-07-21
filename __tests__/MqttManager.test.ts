/**
 * Unit tests for MqttManager
 * Tests binary message handling, event subscriptions, and API methods
 */

import { MqttManager } from '../src/MqttManager';
import type { MqttConfig } from '../src/types';

// React Native is mocked via __mocks__/react-native.js
// MqttModule is mocked via __mocks__/MqttModule.ts
jest.mock('../src/MqttModule');

// Get references to mocked module
import MqttModule from '../src/MqttModule';

describe('MqttManager', () => {
  let mqttManager: MqttManager;

  // Get mock references (they exist on the mocked module)
  const getMocks = () => ({
    connect: MqttModule.connect as jest.Mock,
    disconnect: MqttModule.disconnect as jest.Mock,
    subscribe: MqttModule.subscribe as jest.Mock,
    unsubscribe: MqttModule.unsubscribe as jest.Mock,
    publish: MqttModule.publish as jest.Mock,
    cleanup: MqttModule.cleanup as jest.Mock,
  });

  beforeEach(() => {
    // Clear all mocks
    jest.clearAllMocks();

    // Reset singleton instance
    (MqttManager as any)._instance = undefined;
    mqttManager = MqttManager.Instance;

    // Reset all mock implementations to their defaults
    const mocks = getMocks();
    mocks.connect.mockImplementation((broker, clientId, certs, sni, ip, cn, isAdmin, success, error) => {
      if (success) success('Connected');
    });
    mocks.disconnect.mockImplementation((success, error) => {
      if (success) success('Disconnected');
    });
    mocks.subscribe.mockImplementation((topic, qos, success, error) => {
      if (success) success(`Subscribed to ${topic}`);
    });
    mocks.unsubscribe.mockImplementation((topic, success, error) => {
      if (success) success(`Unsubscribed from ${topic}`);
    });
    mocks.publish.mockImplementation((topic, message, qos, retained, success, error) => {
      if (success) success(`Published to ${topic}`);
    });
    mocks.cleanup.mockImplementation((success, error) => {
      if (success) success('Cleaned up');
    });
  });

  describe('Singleton Pattern', () => {
    it('should return the same instance', () => {
      const instance1 = MqttManager.Instance;
      const instance2 = MqttManager.Instance;
      expect(instance1).toBe(instance2);
    });
  });

  describe('Binary Message Handling', () => {
    it('should encode Uint8Array to Base64 with B64: prefix', async () => {
      getMocks().publish.mockImplementation((topic, message, qos, retained, success, error) => {
        success('Published');
      });

      const binaryData = new Uint8Array([0x48, 0x65, 0x6C, 0x6C, 0x6F]); // "Hello"
      await mqttManager.publish('test/topic', binaryData);

      expect(getMocks().publish).toHaveBeenCalledWith(
        'test/topic',
        expect.stringMatching(/^B64:/), // Should start with B64: marker
        1,
        false,
        expect.any(Function),
        expect.any(Function)
      );

      // Verify the Base64 encoding is correct
      const publishedMessage = getMocks().publish.mock.calls[0][1] as string;
      expect(publishedMessage).toBe('B64:SGVsbG8='); // Base64 of "Hello"
    });

    it('should encode ArrayBuffer to Base64 with B64: prefix', async () => {
      getMocks().publish.mockImplementation((topic, message, qos, retained, success, error) => {
        success('Published');
      });

      const buffer = new ArrayBuffer(5);
      const view = new Uint8Array(buffer);
      view.set([0x48, 0x65, 0x6C, 0x6C, 0x6F]); // "Hello"

      await mqttManager.publish('test/topic', buffer);

      const publishedMessage = getMocks().publish.mock.calls[0][1] as string;
      expect(publishedMessage).toBe('B64:SGVsbG8=');
    });

    it('should handle text messages without B64: prefix', async () => {
      getMocks().publish.mockImplementation((topic, message, qos, retained, success, error) => {
        success('Published');
      });

      const textMessage = 'Hello, MQTT!';
      await mqttManager.publish('test/topic', textMessage);

      expect(getMocks().publish).toHaveBeenCalledWith(
        'test/topic',
        'Hello, MQTT!',
        1,
        false,
        expect.any(Function),
        expect.any(Function)
      );

      const publishedMessage = getMocks().publish.mock.calls[0][1] as string;
      expect(publishedMessage).not.toMatch(/^B64:/);
    });

    it('should handle JSON objects by stringifying', async () => {
      getMocks().publish.mockImplementation((topic, message, qos, retained, success, error) => {
        success('Published');
      });

      const jsonObject = { status: 'active', count: 42 };
      await mqttManager.publish('test/topic', jsonObject as any);

      const publishedMessage = getMocks().publish.mock.calls[0][1] as string;
      expect(publishedMessage).toBe('{"status":"active","count":42}');
    });

    it('should handle empty binary data', async () => {
      getMocks().publish.mockImplementation((topic, message, qos, retained, success, error) => {
        success('Published');
      });

      const emptyData = new Uint8Array(0);
      await mqttManager.publish('test/topic', emptyData);

      const publishedMessage = getMocks().publish.mock.calls[0][1] as string;
      expect(publishedMessage).toBe('B64:'); // Just the marker, no data
    });

    it('should handle large binary data correctly', async () => {
      getMocks().publish.mockImplementation((topic, message, qos, retained, success, error) => {
        success('Published');
      });

      // Create 1MB of test data
      const largeData = new Uint8Array(1024 * 1024);
      for (let i = 0; i < largeData.length; i++) {
        largeData[i] = i % 256;
      }

      await mqttManager.publish('test/topic', largeData);

      expect(getMocks().publish).toHaveBeenCalled();
      const publishedMessage = getMocks().publish.mock.calls[0][1] as string;
      expect(publishedMessage).toMatch(/^B64:/);
      expect(publishedMessage.length).toBeGreaterThan(1024 * 1024); // Base64 is larger
    });
  });

  describe('Binary Message Decoding', () => {
    it('should decode Base64 binary messages to Uint8Array', () => {
      const mockCallback = jest.fn();
      const config: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'test-client',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
        onMessage: mockCallback,
      };

      mqttManager.connect(config);

      // Simulate receiving a binary message
      const { NativeEventEmitter } = require('react-native');
      const emitterInstance = NativeEventEmitter.mock.results[0].value;
      const messageListener = emitterInstance.addListener.mock.calls.find(
        (call: any[]) => call[0] === 'MqttMessage'
      )[1];

      const base64Data = btoa('Hello'); // SGVsbG8=
      messageListener({
        topic: 'test/topic',
        message: base64Data,
        isBinary: true,
        qos: 1,
      });

      expect(mockCallback).toHaveBeenCalled();
      const receivedMessage = mockCallback.mock.calls[0][0];
      expect(receivedMessage.message).toBeInstanceOf(Uint8Array);
      expect(Array.from(receivedMessage.message as Uint8Array)).toEqual([
        0x48, 0x65, 0x6C, 0x6C, 0x6F,
      ]);
    });

    it('should handle text messages as strings', () => {
      const mockCallback = jest.fn();
      const config: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'test-client',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
        onMessage: mockCallback,
      };

      mqttManager.connect(config);

      const { NativeEventEmitter } = require('react-native');
      const emitterInstance = NativeEventEmitter.mock.results[0].value;
      const messageListener = emitterInstance.addListener.mock.calls.find(
        (call: any[]) => call[0] === 'MqttMessage'
      )[1];

      messageListener({
        topic: 'test/topic',
        message: 'Plain text message',
        isBinary: false,
        qos: 1,
      });

      expect(mockCallback).toHaveBeenCalled();
      const receivedMessage = mockCallback.mock.calls[0][0];
      expect(typeof receivedMessage.message).toBe('string');
      expect(receivedMessage.message).toBe('Plain text message');
    });

    it('should handle invalid Base64 gracefully', () => {
      const mockCallback = jest.fn();
      const config: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'test-client',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
        onMessage: mockCallback,
      };

      mqttManager.connect(config);

      const { NativeEventEmitter } = require('react-native');
      const emitterInstance = NativeEventEmitter.mock.results[0].value;
      const messageListener = emitterInstance.addListener.mock.calls.find(
        (call: any[]) => call[0] === 'MqttMessage'
      )[1];

      // Send invalid Base64
      messageListener({
        topic: 'test/topic',
        message: '!!!invalid-base64!!!',
        isBinary: true,
        qos: 1,
      });

      // Should still call callback with original message
      expect(mockCallback).toHaveBeenCalled();
      const receivedMessage = mockCallback.mock.calls[0][0];
      expect(receivedMessage.message).toBe('!!!invalid-base64!!!');
    });
  });

  describe('Connection Management', () => {
    it('should connect with valid configuration', async () => {
      getMocks().connect.mockImplementation((broker, clientId, certs, sni, ip, cn, isAdmin, success, error) => {
        success('Connected');
      });

      const config: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'test-client',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
      };

      await expect(mqttManager.connect(config)).resolves.toBeUndefined();
      expect(getMocks().connect).toHaveBeenCalledWith(
        'mqtt.example.com',
        'test-client',
        config.certificates,
        null,
        null,
        null,
        false, // isAdminUser now defaults to false (secure-by-default)
        expect.any(Function),
        expect.any(Function)
      );
    });

    it('should handle connection errors', async () => {
      getMocks().connect.mockImplementation((broker, clientId, certs, sni, ip, cn, isAdmin, success, error) => {
        error('Connection failed');
      });

      const config: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'test-client',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
      };

      await expect(mqttManager.connect(config)).rejects.toThrow('Connection failed');
    });

    it('should disconnect successfully', async () => {
      getMocks().disconnect.mockImplementation((success, error) => {
        success('Disconnected');
      });

      await expect(mqttManager.disconnect()).resolves.toBeUndefined();
      expect(mqttManager.isConnected()).toBe(false);
    });
  });

  describe('Subscription Management', () => {
    it('should subscribe to topic', async () => {
      getMocks().subscribe.mockImplementation((topic, qos, success, error) => {
        success('Subscribed');
      });

      await expect(mqttManager.subscribe('test/topic', 1)).resolves.toBeUndefined();
      expect(getMocks().subscribe).toHaveBeenCalledWith(
        'test/topic',
        1,
        expect.any(Function),
        expect.any(Function)
      );
    });

    it('should unsubscribe from topic', async () => {
      getMocks().unsubscribe.mockImplementation((topic, success, error) => {
        success('Unsubscribed');
      });

      await expect(mqttManager.unsubscribe('test/topic')).resolves.toBeUndefined();
      expect(getMocks().unsubscribe).toHaveBeenCalledWith(
        'test/topic',
        expect.any(Function),
        expect.any(Function)
      );
    });
  });

  describe('Event Handling', () => {
    it('should trigger onConnect callback', () => {
      const mockOnConnect = jest.fn();
      const config: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'test-client',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
        onConnect: mockOnConnect,
      };

      mqttManager.connect(config);

      const { NativeEventEmitter } = require('react-native');
      const emitterInstance = NativeEventEmitter.mock.results[0].value;
      const connectListener = emitterInstance.addListener.mock.calls.find(
        (call: any[]) => call[0] === 'MqttConnected'
      )[1];

      connectListener('Connected');

      expect(mockOnConnect).toHaveBeenCalled();
      expect(mqttManager.isConnected()).toBe(true);
    });

    it('should trigger onConnectionLost callback', () => {
      const mockOnConnectionLost = jest.fn();
      const config: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'test-client',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
        onConnectionLost: mockOnConnectionLost,
      };

      mqttManager.connect(config);

      const { NativeEventEmitter } = require('react-native');
      const emitterInstance = NativeEventEmitter.mock.results[0].value;
      const disconnectListener = emitterInstance.addListener.mock.calls.find(
        (call: any[]) => call[0] === 'MqttDisconnected'
      )[1];

      disconnectListener('Connection lost');

      expect(mockOnConnectionLost).toHaveBeenCalledWith('Connection lost');
      expect(mqttManager.isConnected()).toBe(false);
    });
  });

  describe('QoS and Retained Messages', () => {
    it('should publish with specified QoS', async () => {
      getMocks().publish.mockImplementation((topic, message, qos, retained, success, error) => {
        success('Published');
      });

      await mqttManager.publish('test/topic', 'message', 2, false);

      expect(getMocks().publish).toHaveBeenCalledWith(
        'test/topic',
        'message',
        2,
        false,
        expect.any(Function),
        expect.any(Function)
      );
    });

    it('should publish with retained flag', async () => {
      getMocks().publish.mockImplementation((topic, message, qos, retained, success, error) => {
        success('Published');
      });

      await mqttManager.publish('test/topic', 'message', 1, true);

      expect(getMocks().publish).toHaveBeenCalledWith(
        'test/topic',
        'message',
        1,
        true,
        expect.any(Function),
        expect.any(Function)
      );
    });
  });

  describe('Cleanup', () => {
    it('should cleanup resources', () => {
      mqttManager.cleanup();

      expect(getMocks().cleanup).toHaveBeenCalled();
      expect(mqttManager.isConnected()).toBe(false);
    });
  });
});
