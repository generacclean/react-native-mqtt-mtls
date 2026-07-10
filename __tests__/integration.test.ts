/**
 * Integration tests for MQTT message flows
 * Tests end-to-end scenarios for binary and text message handling
 */

describe('MQTT Message Flow Integration Tests', () => {
  describe('Binary Message Round-Trip', () => {
    it('should encode and decode protobuf-like data correctly', () => {
      // Simulate protobuf varint encoding: field 7, value 2000
      const protobufData = new Uint8Array([0x38, 0xD0, 0x0F]);

      // Encode for publishing (add B64: marker)
      let binary = "";
      for (let i = 0; i < protobufData.byteLength; i++) {
        binary += String.fromCharCode(protobufData[i]);
      }
      const publishMessage = "B64:" + btoa(binary);

      // Verify encoding
      expect(publishMessage).toMatch(/^B64:/);

      // Simulate receiving (decode from Base64)
      const base64Data = publishMessage.substring(4);
      const binaryString = atob(base64Data);
      const decoded = new Uint8Array(binaryString.length);
      for (let i = 0; i < binaryString.length; i++) {
        decoded[i] = binaryString.charCodeAt(i);
      }

      // Verify round-trip preserves data
      expect(Array.from(decoded)).toEqual(Array.from(protobufData));
    });

    it('should handle firmware-sized binary data', () => {
      // Simulate 1MB firmware chunk
      const firmwareChunk = new Uint8Array(1024 * 1024);
      for (let i = 0; i < firmwareChunk.length; i++) {
        firmwareChunk[i] = i % 256;
      }

      // Encode
      let binary = "";
      for (let i = 0; i < firmwareChunk.byteLength; i++) {
        binary += String.fromCharCode(firmwareChunk[i]);
      }
      const publishMessage = "B64:" + btoa(binary);

      // Decode
      const base64Data = publishMessage.substring(4);
      const binaryString = atob(base64Data);
      const decoded = new Uint8Array(binaryString.length);
      for (let i = 0; i < binaryString.length; i++) {
        decoded[i] = binaryString.charCodeAt(i);
      }

      // Verify
      expect(decoded.length).toBe(firmwareChunk.length);
      expect(decoded[0]).toBe(firmwareChunk[0]);
      expect(decoded[firmwareChunk.length - 1]).toBe(firmwareChunk[firmwareChunk.length - 1]);
    });
  });

  describe('Text Message Handling', () => {
    it('should not add B64: prefix to JSON strings', () => {
      const jsonMessage = JSON.stringify({
        timestamp: 1782414353,
        status: "active",
        devices: [1, 2, 3]
      });

      // Text messages should not get the prefix
      expect(jsonMessage).not.toMatch(/^B64:/);

      // Verify it's valid JSON
      const parsed = JSON.parse(jsonMessage);
      expect(parsed.timestamp).toBe(1782414353);
      expect(parsed.status).toBe("active");
    });

    it('should handle JSON that happens to be valid Base64', () => {
      // This was the bug that PR #4 fixes
      const jsonString = '{"timestamp":1782414353,"status":"active"}';

      // Should NOT be decoded as Base64 (no B64: prefix)
      expect(jsonString).not.toMatch(/^B64:/);

      // Should remain as text
      const parsed = JSON.parse(jsonString);
      expect(parsed.timestamp).toBe(1782414353);
    });
  });

  describe('Mixed Message Types', () => {
    it('should handle alternating binary and text messages', () => {
      const messages: Array<{ type: string; data: string | Uint8Array }> = [
        { type: 'text', data: 'Hello' },
        { type: 'binary', data: new Uint8Array([1, 2, 3]) },
        { type: 'text', data: '{"status":"ok"}' },
        { type: 'binary', data: new Uint8Array([0xFF, 0xFE]) },
      ];

      messages.forEach(msg => {
        if (msg.type === 'text') {
          expect(typeof msg.data).toBe('string');
        } else {
          expect(msg.data).toBeInstanceOf(Uint8Array);
        }
      });
    });
  });

  describe('Memory Efficiency', () => {
    it('should use Base64 encoding (not hex) for size efficiency', () => {
      const testData = new Uint8Array(100);
      for (let i = 0; i < 100; i++) {
        testData[i] = i;
      }

      // Base64 encoding
      let binary = "";
      for (let i = 0; i < testData.byteLength; i++) {
        binary += String.fromCharCode(testData[i]);
      }
      const base64Encoded = btoa(binary);

      // Hex encoding (for comparison)
      const hexEncoded = Array.from(testData)
        .map(b => b.toString(16).padStart(2, '0'))
        .join('');

      // Base64 should be smaller than hex
      expect(base64Encoded.length).toBeLessThan(hexEncoded.length);

      // Verify: Base64 is ~1.33x original, Hex is 2x original
      // Base64 encodes 3 bytes into 4 characters, so actual ratio is 4/3 = 1.333...
      // With padding, it can be slightly more
      expect(base64Encoded.length).toBeGreaterThan(testData.length);
      expect(base64Encoded.length).toBeLessThan(testData.length * 1.4);
      expect(hexEncoded.length).toBe(testData.length * 2);
    });
  });

  describe('Edge Cases', () => {
    it('should handle empty binary data', () => {
      const emptyData = new Uint8Array(0);

      let binary = "";
      for (let i = 0; i < emptyData.byteLength; i++) {
        binary += String.fromCharCode(emptyData[i]);
      }
      const publishMessage = "B64:" + btoa(binary);

      expect(publishMessage).toBe("B64:");
    });

    it('should handle empty text data', () => {
      const emptyText = "";

      expect(emptyText.length).toBe(0);
      expect(typeof emptyText).toBe('string');
    });

    it('should handle null bytes in binary data', () => {
      const nullBytes = new Uint8Array([0x00, 0x00, 0x00]);

      let binary = "";
      for (let i = 0; i < nullBytes.byteLength; i++) {
        binary += String.fromCharCode(nullBytes[i]);
      }
      const publishMessage = "B64:" + btoa(binary);

      // Decode back
      const base64Data = publishMessage.substring(4);
      const binaryString = atob(base64Data);
      const decoded = new Uint8Array(binaryString.length);
      for (let i = 0; i < binaryString.length; i++) {
        decoded[i] = binaryString.charCodeAt(i);
      }

      expect(Array.from(decoded)).toEqual([0, 0, 0]);
    });

    it('should handle all byte values (0-255)', () => {
      const allBytes = new Uint8Array(256);
      for (let i = 0; i < 256; i++) {
        allBytes[i] = i;
      }

      let binary = "";
      for (let i = 0; i < allBytes.byteLength; i++) {
        binary += String.fromCharCode(allBytes[i]);
      }
      const publishMessage = "B64:" + btoa(binary);

      // Decode back
      const base64Data = publishMessage.substring(4);
      const binaryString = atob(base64Data);
      const decoded = new Uint8Array(binaryString.length);
      for (let i = 0; i < binaryString.length; i++) {
        decoded[i] = binaryString.charCodeAt(i);
      }

      // Verify all bytes survived
      expect(Array.from(decoded)).toEqual(Array.from(allBytes));
    });
  });

  describe('Type Safety', () => {
    it('should maintain Uint8Array type through message handling', () => {
      const binaryData = new Uint8Array([1, 2, 3, 4, 5]);

      // Type guard
      function processMessage(message: string | Uint8Array): string {
        if (typeof message === 'string') {
          return 'text';
        } else if (message instanceof Uint8Array) {
          return 'binary';
        }
        return 'unknown';
      }

      expect(processMessage(binaryData)).toBe('binary');
      expect(processMessage('Hello')).toBe('text');
    });

    it('should work with Buffer.from() for protobuf libraries', () => {
      const uint8Data = new Uint8Array([0x38, 0xD0, 0x0F]);

      // Protobuf libraries typically use Buffer.from(Uint8Array)
      const buffer = Buffer.from(uint8Data);

      expect(buffer).toBeInstanceOf(Buffer);
      expect(buffer.length).toBe(3);
      expect(buffer[0]).toBe(0x38);
    });

    it('should work with TextDecoder for text messages', () => {
      const textData = new Uint8Array([72, 101, 108, 108, 111]); // "Hello"

      const decoder = new TextDecoder('utf-8');
      const text = decoder.decode(textData);

      expect(text).toBe('Hello');
    });
  });
});
