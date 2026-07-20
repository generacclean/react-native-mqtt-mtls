/**
 * Tests for topic-based binary detection logic
 * Validates the deterministic topic pattern matching
 * Addresses PR #4 concerns about topic collision and misclassification
 */

describe("Topic-Based Binary Detection", () => {
  /**
   * Simulates the isBinaryData logic from native modules
   * This is the TypeScript version for documentation/testing purposes
   */
  function isBinaryData(topic: string | null, payload: Uint8Array): boolean {
    // DETERMINISTIC: Topic-based detection for known binary patterns
    if (topic) {
      // Protobuf topics (device lists, RMA swap, hardware assembly)
      if (
        topic.includes("/proto/") ||
        topic.includes("/device") ||
        topic.includes("/rma") ||
        topic.includes("/assembly") ||
        topic.includes("/installed")
      ) {
        return true;
      }

      // Firmware update topics
      if (
        topic.includes("/firmware") ||
        topic.includes("/ota") ||
        topic.includes("/upload")
      ) {
        return true;
      }

      // Text topics (JSON status, configuration, commands)
      if (
        topic.includes("/status") ||
        topic.includes("/config") ||
        topic.includes("/command") ||
        topic.includes("/json")
      ) {
        return false;
      }
    }

    // FALLBACK: UTF-8 heuristic for unknown topics
    try {
      const decoder = new TextDecoder("utf-8", { fatal: true });
      decoder.decode(payload);
      return false; // Valid UTF-8 → text
    } catch (e) {
      return true; // Invalid UTF-8 → binary
    }
  }

  describe("Binary Topics - Protobuf", () => {
    it("should detect /proto/ topics as binary", () => {
      const topic = "device/proto/list";
      const payload = new Uint8Array([0x41, 0x42, 0x43]); // "ABC" - valid UTF-8
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should detect /device topics as binary", () => {
      const topic = "remote/device/12345";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should detect /rma topics as binary", () => {
      const topic = "device/rma/swap";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should detect /assembly topics as binary", () => {
      const topic = "penguin/assembly/info";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should detect /installed topics as binary", () => {
      const topic = "device/installed/hardware";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });
  });

  describe("Binary Topics - Firmware", () => {
    it("should detect /firmware topics as binary", () => {
      const topic = "device/firmware/update";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should detect /ota topics as binary", () => {
      const topic = "penguin/ota/chunk";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should detect /upload topics as binary", () => {
      const topic = "device/upload/firmware";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });
  });

  describe("Text Topics - JSON", () => {
    it("should detect /status topics as text", () => {
      const topic = "device/status";
      const payload = new Uint8Array([0xff, 0xfe]); // Invalid UTF-8
      expect(isBinaryData(topic, payload)).toBe(false);
    });

    it("should detect /config topics as text", () => {
      const topic = "penguin/config/network";
      const payload = new Uint8Array([0xff, 0xfe]);
      expect(isBinaryData(topic, payload)).toBe(false);
    });

    it("should detect /command topics as text", () => {
      const topic = "device/command/execute";
      const payload = new Uint8Array([0xff, 0xfe]);
      expect(isBinaryData(topic, payload)).toBe(false);
    });

    it("should detect /json topics as text", () => {
      const topic = "remote/json/response";
      const payload = new Uint8Array([0xff, 0xfe]);
      expect(isBinaryData(topic, payload)).toBe(false);
    });
  });

  describe("Topic Pattern Collisions (PR #4 Concern)", () => {
    it("should classify /device/status as binary (device before status)", () => {
      const topic = "penguin/device/status";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      // Binary patterns are checked first, so /device matches before /status
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should classify /device/config as binary (device before config)", () => {
      const topic = "remote/device/config";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should classify /proto/status as binary (proto before status)", () => {
      const topic = "device/proto/status";
      const payload = new Uint8Array([0x41, 0x42, 0x43]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should classify status topic without device as text", () => {
      const topic = "system/status";
      const payload = new Uint8Array([0xff, 0xfe]);
      expect(isBinaryData(topic, payload)).toBe(false);
    });
  });

  describe("UTF-8 Heuristic Fallback", () => {
    it("should classify unknown topic with valid UTF-8 as text", () => {
      const topic = "custom/unknown/topic";
      const payload = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]); // "Hello"
      expect(isBinaryData(topic, payload)).toBe(false);
    });

    it("should classify unknown topic with invalid UTF-8 as binary", () => {
      const topic = "custom/unknown/topic";
      const payload = new Uint8Array([0xff, 0xfe, 0xfd]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should handle null topic with valid UTF-8", () => {
      const topic = null;
      const payload = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
      expect(isBinaryData(topic, payload)).toBe(false);
    });

    it("should handle null topic with invalid UTF-8", () => {
      const topic = null;
      const payload = new Uint8Array([0xff, 0xfe, 0xfd]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });
  });

  describe("ASCII Protobuf Misclassification (PR #4 Core Bug)", () => {
    it("should correctly classify ASCII protobuf on device topic", () => {
      const topic = "device/proto/list";
      // Small protobuf with ASCII serial numbers - all bytes < 0x80
      const payload = new Uint8Array([
        0x0a,
        0x06,
        0x41,
        0x42,
        0x43,
        0x31,
        0x32,
        0x33, // "ABC123"
        0x12,
        0x06,
        0x44,
        0x45,
        0x46,
        0x34,
        0x35,
        0x36, // "DEF456"
      ]);

      // Topic-based detection saves us from UTF-8 heuristic misclassification
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should misclassify ASCII protobuf on unknown topic", () => {
      const topic = "unknown/custom/path";
      // Same ASCII protobuf
      const payload = new Uint8Array([
        0x0a, 0x06, 0x41, 0x42, 0x43, 0x31, 0x32, 0x33,
      ]);

      // Without topic-based detection, UTF-8 heuristic misclassifies as text
      // This demonstrates the limitation of UTF-8 heuristic alone
      expect(isBinaryData(topic, payload)).toBe(false);
    });
  });

  describe("Real-World installer-app Topics", () => {
    it("should handle network config topic", () => {
      const topic = "remote/network/config/get/accepted";
      const payload = new Uint8Array([0x0a, 0x09]); // Protobuf header
      // Contains "config" but not explicitly binary - falls to UTF-8 check
      expect(isBinaryData(topic, payload)).toBe(false);
    });

    it("should handle device list topic", () => {
      const topic = "remote/device/list/response";
      const payload = new Uint8Array([0x0a, 0x07]);
      // Contains "/device" - binary
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should handle RMA swap topic", () => {
      const topic = "device/rma/swap/response";
      const payload = new Uint8Array([0x08, 0x01]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should handle firmware update topic", () => {
      const topic = "device/firmware/update/chunk";
      const payload = new Uint8Array([0x00, 0x01, 0x02]);
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should handle system status topic", () => {
      const topic = "penguin/status/health";
      const payload = new Uint8Array([0x7b, 0x7d]); // "{}"
      expect(isBinaryData(topic, payload)).toBe(false);
    });
  });

  describe("Edge Cases", () => {
    it("should handle empty topic", () => {
      const topic = "";
      const validUTF8 = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
      const invalidUTF8 = new Uint8Array([0xff, 0xfe]);

      expect(isBinaryData(topic, validUTF8)).toBe(false);
      expect(isBinaryData(topic, invalidUTF8)).toBe(true);
    });

    it("should handle empty payload", () => {
      const empty = new Uint8Array([]);

      expect(isBinaryData("device/proto/list", empty)).toBe(true); // Binary by topic
      expect(isBinaryData("device/status", empty)).toBe(false); // Text by topic
      expect(isBinaryData("unknown/topic", empty)).toBe(false); // Empty is valid UTF-8
    });

    it("should handle topic with multiple pattern matches", () => {
      // Topic contains both binary and text patterns
      const topic = "device/proto/status/config";
      const payload = new Uint8Array([0x41, 0x42]);

      // First match wins (binary patterns checked first)
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should handle case sensitivity", () => {
      // Topics should be case-sensitive
      const upperTopic = "device/PROTO/list";
      const lowerTopic = "device/proto/list";
      const payload = new Uint8Array([0x41, 0x42]);

      expect(isBinaryData(lowerTopic, payload)).toBe(true);
      // Uppercase PROTO doesn't match /proto/ pattern
      expect(isBinaryData(upperTopic, payload)).toBe(false); // Falls to UTF-8 check
    });

    it("should handle topics with special characters", () => {
      const topic = "remote/device/proto-list";
      const payload = new Uint8Array([0x41, 0x42]);

      // "proto-list" doesn't match "/proto/" (requires slashes)
      expect(isBinaryData(topic, payload)).toBe(true); // But matches "/device"
    });
  });

  describe("Performance - Pattern Matching Order", () => {
    it("should check binary patterns before text patterns", () => {
      // If a topic matches both binary and text patterns,
      // binary should take precedence (checked first)
      const ambiguousTopic = "penguin/device/status"; // Has both /device and /status
      const payload = new Uint8Array([0x41, 0x42]);

      // /device is checked first (binary), so result is binary
      expect(isBinaryData(ambiguousTopic, payload)).toBe(true);
    });

    it("should short-circuit on first binary match", () => {
      const topic = "device/proto/firmware/ota"; // Matches multiple binary patterns
      const payload = new Uint8Array([0x41, 0x42]);

      // Should return true on first match, not evaluate all patterns
      expect(isBinaryData(topic, payload)).toBe(true);
    });

    it("should short-circuit on first text match", () => {
      const topic = "system/status/json/config"; // Matches multiple text patterns
      const payload = new Uint8Array([0xff, 0xfe]);

      // Should return false on first text match
      expect(isBinaryData(topic, payload)).toBe(false);
    });

    it("should only check UTF-8 heuristic for unknown topics", () => {
      const knownBinaryTopic = "device/proto/list";
      const knownTextTopic = "system/status";
      const unknownTopic = "custom/unknown";

      const validUTF8 = new Uint8Array([0x41, 0x42]);
      const invalidUTF8 = new Uint8Array([0xff, 0xfe]);

      // Known topics don't need UTF-8 check
      expect(isBinaryData(knownBinaryTopic, validUTF8)).toBe(true);
      expect(isBinaryData(knownTextTopic, invalidUTF8)).toBe(false);

      // Unknown topic uses UTF-8 check
      expect(isBinaryData(unknownTopic, validUTF8)).toBe(false);
      expect(isBinaryData(unknownTopic, invalidUTF8)).toBe(true);
    });
  });

  describe("Documentation Examples", () => {
    it("example: device list with ASCII serials (the PR #4 bug)", () => {
      // This is the exact scenario that was failing before topic-based detection
      const topic = "device/proto/list";
      const deviceListProtobuf = new Uint8Array([
        0x0a,
        0x07,
        0x50,
        0x45,
        0x4e,
        0x2d,
        0x30,
        0x30,
        0x31, // "PEN-001"
        0x0a,
        0x07,
        0x50,
        0x45,
        0x4e,
        0x2d,
        0x30,
        0x30,
        0x32, // "PEN-002"
      ]);

      // All bytes are valid UTF-8, but topic tells us it's protobuf
      expect(isBinaryData(topic, deviceListProtobuf)).toBe(true);

      // Verify it's actually valid UTF-8 (demonstrating the problem)
      let isValidUTF8 = true;
      try {
        new TextDecoder("utf-8", { fatal: true }).decode(deviceListProtobuf);
      } catch (e) {
        isValidUTF8 = false;
      }
      expect(isValidUTF8).toBe(true);
    });

    it("example: JSON status message", () => {
      const topic = "device/status";
      const jsonPayload = new TextEncoder().encode(
        '{"status":"online","uptime":12345}',
      );

      // Topic tells us it's text, even if it happens to have high-bit bytes
      expect(isBinaryData(topic, jsonPayload)).toBe(false);
    });

    it("example: firmware binary chunk", () => {
      const topic = "device/firmware/update/chunk/42";
      const binaryChunk = new Uint8Array(256);
      for (let i = 0; i < 256; i++) {
        binaryChunk[i] = i;
      }

      // Topic tells us it's binary firmware data
      expect(isBinaryData(topic, binaryChunk)).toBe(true);
    });
  });
});
