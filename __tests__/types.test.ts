/**
 * Type definition tests
 * Validates that type definitions match runtime behavior
 */

import type { MqttMessage, MqttConfig, MqttCertificates } from '../src/types';

describe('Type Definitions', () => {
  describe('MqttMessage', () => {
    it('should accept string message', () => {
      const message: MqttMessage = {
        topic: 'test/topic',
        message: 'Hello, MQTT!',
        qos: 1,
      };

      expect(message.message).toBe('Hello, MQTT!');
      expect(typeof message.message).toBe('string');
    });

    it('should accept Uint8Array message', () => {
      const binaryData = new Uint8Array([0x48, 0x65, 0x6C, 0x6C, 0x6F]);
      const message: MqttMessage = {
        topic: 'test/topic',
        message: binaryData,
        qos: 1,
      };

      expect(message.message).toBeInstanceOf(Uint8Array);
      expect(Array.from(message.message as Uint8Array)).toEqual([
        0x48, 0x65, 0x6C, 0x6C, 0x6F,
      ]);
    });

    it('should have optional isBinary flag', () => {
      const message1: MqttMessage = {
        topic: 'test/topic',
        message: 'text',
        qos: 1,
        isBinary: false,
      };

      const message2: MqttMessage = {
        topic: 'test/topic',
        message: new Uint8Array([1, 2, 3]),
        qos: 1,
        isBinary: true,
      };

      const message3: MqttMessage = {
        topic: 'test/topic',
        message: 'text',
        qos: 1,
        // isBinary is optional
      };

      expect(message1.isBinary).toBe(false);
      expect(message2.isBinary).toBe(true);
      expect(message3.isBinary).toBeUndefined();
    });

    it('should have optional isRetained flag', () => {
      const replay: MqttMessage = {
        topic: 'test/topic',
        message: 'text',
        qos: 1,
        isRetained: true,
      };

      const liveDelivery: MqttMessage = {
        topic: 'test/topic',
        message: 'text',
        qos: 1,
        isRetained: false,
      };

      const olderLibraryVersion: MqttMessage = {
        topic: 'test/topic',
        message: 'text',
        qos: 1,
      };

      expect(replay.isRetained).toBe(true);
      expect(liveDelivery.isRetained).toBe(false);
      expect(olderLibraryVersion.isRetained).toBeUndefined();
    });

    it('should support all QoS levels', () => {
      const qos0: MqttMessage = {
        topic: 'test',
        message: 'data',
        qos: 0,
      };

      const qos1: MqttMessage = {
        topic: 'test',
        message: 'data',
        qos: 1,
      };

      const qos2: MqttMessage = {
        topic: 'test',
        message: 'data',
        qos: 2,
      };

      expect(qos0.qos).toBe(0);
      expect(qos1.qos).toBe(1);
      expect(qos2.qos).toBe(2);
    });
  });

  describe('MqttCertificates', () => {
    it('should have required certificate fields', () => {
      const certs: MqttCertificates = {
        clientCert: '/path/to/client.crt',
        privateKeyAlias: 'my-key-alias',
        rootCa: '/path/to/ca.crt',
        useHardwareKey: false,
      };

      expect(certs.clientCert).toBeTruthy();
      expect(certs.privateKeyAlias).toBeTruthy();
      expect(certs.rootCa).toBeTruthy();
      expect(typeof certs.useHardwareKey).toBe('boolean');
    });

    it('should support hardware key configuration', () => {
      const hardwareCerts: MqttCertificates = {
        clientCert: '/path/to/client.crt',
        privateKeyAlias: 'hardware-key',
        rootCa: '/path/to/ca.crt',
        useHardwareKey: true,
      };

      expect(hardwareCerts.useHardwareKey).toBe(true);
    });
  });

  describe('MqttConfig', () => {
    it('should have required fields', () => {
      const config: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'test-client-123',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
      };

      expect(config.broker).toBeTruthy();
      expect(config.clientId).toBeTruthy();
      expect(config.certificates).toBeTruthy();
    });

    it('should support optional callback fields', () => {
      const onMessage = jest.fn();
      const onConnect = jest.fn();
      const onConnectionLost = jest.fn();
      const onReconnect = jest.fn();
      const onError = jest.fn();

      const config: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'test-client',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
        onMessage,
        onConnect,
        onConnectionLost,
        onReconnect,
        onError,
      };

      expect(config.onMessage).toBe(onMessage);
      expect(config.onConnect).toBe(onConnect);
      expect(config.onConnectionLost).toBe(onConnectionLost);
      expect(config.onReconnect).toBe(onReconnect);
      expect(config.onError).toBe(onError);
    });

    it('should support admin and non-admin configurations', () => {
      const adminConfig: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'admin-client',
        isAdminUser: true,
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
      };

      const userConfig: MqttConfig = {
        broker: 'mqtt.example.com',
        clientId: 'user-client',
        isAdminUser: false,
        sniHostname: 'mqtt-sni.example.com',
        brokerIp: '192.168.1.100',
        brokerCommonName: 'mqtt-broker',
        certificates: {
          clientCert: '/path/to/cert',
          privateKeyAlias: 'alias',
          rootCa: '/path/to/ca',
          useHardwareKey: false,
        },
      };

      expect(adminConfig.isAdminUser).toBe(true);
      expect(userConfig.isAdminUser).toBe(false);
      expect(userConfig.sniHostname).toBeTruthy();
      expect(userConfig.brokerIp).toBeTruthy();
      expect(userConfig.brokerCommonName).toBeTruthy();
    });
  });

  describe('Type Guard Patterns', () => {
    it('should distinguish string from Uint8Array messages', () => {
      const textMessage: MqttMessage = {
        topic: 'test',
        message: 'text data',
        qos: 1,
      };

      const binaryMessage: MqttMessage = {
        topic: 'test',
        message: new Uint8Array([1, 2, 3]),
        qos: 1,
      };

      // Type guard using typeof
      if (typeof textMessage.message === 'string') {
        expect(textMessage.message.length).toBe(9);
      }

      // Type guard using instanceof
      if (binaryMessage.message instanceof Uint8Array) {
        expect(binaryMessage.message.byteLength).toBe(3);
      }
    });

    it('should handle union type correctly', () => {
      function processMessage(message: string | Uint8Array): number {
        if (typeof message === 'string') {
          return message.length;
        } else {
          return message.byteLength;
        }
      }

      expect(processMessage('Hello')).toBe(5);
      expect(processMessage(new Uint8Array([1, 2, 3]))).toBe(3);
    });

    it('should work with Buffer.from() for protobuf decoding', () => {
      // Simulate protobuf decoding pattern
      const binaryMessage: MqttMessage = {
        topic: 'device/telemetry',
        message: new Uint8Array([0x38, 0xD0, 0x0F]), // Protobuf varint
        qos: 1,
        isBinary: true,
      };

      if (binaryMessage.message instanceof Uint8Array) {
        // This is the pattern used in consumer apps
        const buffer = Buffer.from(binaryMessage.message);
        expect(buffer).toBeInstanceOf(Buffer);
        expect(buffer.length).toBe(3);
        expect(buffer[0]).toBe(0x38);
      }
    });
  });
});
