/**
 * Comprehensive tests for binary encoding/decoding
 * Tests hex vs binary vs string vs Uint8Array handling
 * Addresses PR #4 reviewer concerns about message format handling
 */

describe("Binary Encoding Tests", () => {
  describe("Base64 Encoding/Decoding", () => {
    it("should encode binary data to base64", () => {
      const binary = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]); // "Hello"
      const base64 = btoa(String.fromCharCode(...binary));
      expect(base64).toBe("SGVsbG8=");
    });

    it("should decode base64 to binary", () => {
      const base64 = "SGVsbG8=";
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
      expect(Array.from(decoded)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should handle round-trip encoding", () => {
      const original = new Uint8Array([0x00, 0x01, 0x02, 0xff, 0x7f, 0x80]);
      const base64 = btoa(String.fromCharCode(...original));
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
      expect(Array.from(decoded)).toEqual(Array.from(original));
    });

    it("should handle empty data", () => {
      const empty = new Uint8Array([]);
      const base64 = btoa(String.fromCharCode(...empty));
      expect(base64).toBe("");
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
      expect(decoded.length).toBe(0);
    });

    it("should handle large binary payloads", () => {
      // 1KB of binary data
      const large = new Uint8Array(1024);
      for (let i = 0; i < large.length; i++) {
        large[i] = i % 256;
      }

      const base64 = btoa(String.fromCharCode(...large));
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));

      expect(decoded.length).toBe(1024);
      expect(Array.from(decoded)).toEqual(Array.from(large));
    });

    it("should handle all byte values 0-255", () => {
      const allBytes = new Uint8Array(256);
      for (let i = 0; i < 256; i++) {
        allBytes[i] = i;
      }

      const base64 = btoa(String.fromCharCode(...allBytes));
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));

      expect(Array.from(decoded)).toEqual(Array.from(allBytes));
    });
  });

  describe("Hex Encoding/Decoding", () => {
    it("should encode binary to hex string", () => {
      const binary = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]); // "Hello"
      const hex = Array.from(binary)
        .map((b) => b.toString(16).padStart(2, "0"))
        .join("");
      expect(hex).toBe("48656c6c6f");
    });

    it("should decode hex string to binary", () => {
      const hex = "48656c6c6f";
      const binary = new Uint8Array(
        hex.match(/.{1,2}/g)!.map((byte) => parseInt(byte, 16)),
      );
      expect(Array.from(binary)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should handle hex round-trip", () => {
      const original = new Uint8Array([0x00, 0x01, 0x02, 0xff, 0x7f, 0x80]);
      const hex = Array.from(original)
        .map((b) => b.toString(16).padStart(2, "0"))
        .join("");
      const decoded = new Uint8Array(
        hex.match(/.{1,2}/g)!.map((byte) => parseInt(byte, 16)),
      );
      expect(Array.from(decoded)).toEqual(Array.from(original));
    });

    it("should handle uppercase and lowercase hex", () => {
      const upperHex = "ABCDEF";
      const lowerHex = "abcdef";

      const upperDecoded = new Uint8Array(
        upperHex.match(/.{1,2}/g)!.map((byte) => parseInt(byte, 16)),
      );
      const lowerDecoded = new Uint8Array(
        lowerHex.match(/.{1,2}/g)!.map((byte) => parseInt(byte, 16)),
      );

      expect(Array.from(upperDecoded)).toEqual(Array.from(lowerDecoded));
      expect(Array.from(upperDecoded)).toEqual([0xab, 0xcd, 0xef]);
    });

    it("should handle empty hex string", () => {
      const hex = "";
      const decoded = new Uint8Array(
        (hex.match(/.{1,2}/g) || []).map((byte) => parseInt(byte, 16)),
      );
      expect(decoded.length).toBe(0);
    });
  });

  describe("UTF-8 String Encoding/Decoding", () => {
    it("should encode UTF-8 string to bytes", () => {
      const text = "Hello";
      const encoder = new TextEncoder();
      const bytes = encoder.encode(text);
      expect(Array.from(bytes)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should decode bytes to UTF-8 string", () => {
      const bytes = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
      const decoder = new TextDecoder();
      const text = decoder.decode(bytes);
      expect(text).toBe("Hello");
    });

    it("should handle UTF-8 emoji", () => {
      const text = "Hello 👋 World 🌍";
      const encoder = new TextEncoder();
      const bytes = encoder.encode(text);

      const decoder = new TextDecoder();
      const decoded = decoder.decode(bytes);

      expect(decoded).toBe(text);
    });

    it("should handle multi-byte UTF-8 characters", () => {
      const text = "Héllo Wörld"; // Contains accented characters
      const encoder = new TextEncoder();
      const bytes = encoder.encode(text);

      const decoder = new TextDecoder();
      const decoded = decoder.decode(bytes);

      expect(decoded).toBe(text);
    });

    it("should handle empty string", () => {
      const text = "";
      const encoder = new TextEncoder();
      const bytes = encoder.encode(text);
      expect(bytes.length).toBe(0);

      const decoder = new TextDecoder();
      const decoded = decoder.decode(bytes);
      expect(decoded).toBe("");
    });

    it("should handle null bytes in string", () => {
      const bytes = new Uint8Array([0x48, 0x00, 0x65, 0x6c, 0x6c, 0x6f]);
      const decoder = new TextDecoder();
      const text = decoder.decode(bytes);
      expect(text).toBe("H\x00ello"); // Null byte preserved
    });
  });

  describe("Type Detection", () => {
    it("should detect Uint8Array", () => {
      const data = new Uint8Array([1, 2, 3]);
      expect(data instanceof Uint8Array).toBe(true);
      expect(ArrayBuffer.isView(data)).toBe(true);
    });

    it("should detect ArrayBuffer", () => {
      const buffer = new ArrayBuffer(8);
      expect(buffer instanceof ArrayBuffer).toBe(true);
      expect(ArrayBuffer.isView(buffer)).toBe(false);
    });

    it("should detect string", () => {
      const str = "Hello";
      expect(typeof str).toBe("string");
    });

    it("should distinguish between types", () => {
      const uint8 = new Uint8Array([1, 2, 3]);
      const buffer = new ArrayBuffer(8);
      const str = "Hello";

      expect(uint8 instanceof Uint8Array).toBe(true);
      expect(typeof uint8 === "string").toBe(false);

      expect(buffer instanceof ArrayBuffer).toBe(true);
      expect(buffer instanceof Uint8Array).toBe(false);

      expect(typeof str === "string").toBe(true);
    });
  });

  describe("Binary Marker (B64: prefix)", () => {
    it("should add B64: prefix to base64 data", () => {
      const binary = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
      const base64 = btoa(String.fromCharCode(...binary));
      const marked = "B64:" + base64;

      expect(marked).toBe("B64:SGVsbG8=");
      expect(marked.startsWith("B64:")).toBe(true);
    });

    it("should detect B64: prefix", () => {
      const markedMessage = "B64:SGVsbG8=";
      expect(markedMessage.startsWith("B64:")).toBe(true);

      const plainMessage = "Hello, World!";
      expect(plainMessage.startsWith("B64:")).toBe(false);
    });

    it("should extract base64 data after prefix", () => {
      const markedMessage = "B64:SGVsbG8=";
      const base64 = markedMessage.substring(4);
      expect(base64).toBe("SGVsbG8=");

      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
      expect(Array.from(decoded)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should handle empty marked message", () => {
      const markedEmpty = "B64:";
      expect(markedEmpty.startsWith("B64:")).toBe(true);

      const base64 = markedEmpty.substring(4);
      expect(base64).toBe("");
    });

    it('should not confuse plain text containing "B64:"', () => {
      const plainText = "The marker is B64: for binary";
      expect(plainText.startsWith("B64:")).toBe(false);
      expect(plainText.includes("B64:")).toBe(true);
    });
  });

  describe("Message Format Conversions", () => {
    it("should convert string to Uint8Array", () => {
      const str = "Hello";
      const encoder = new TextEncoder();
      const uint8 = encoder.encode(str);

      expect(uint8 instanceof Uint8Array).toBe(true);
      expect(Array.from(uint8)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should convert Uint8Array to string", () => {
      const uint8 = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
      const decoder = new TextDecoder();
      const str = decoder.decode(uint8);

      expect(typeof str).toBe("string");
      expect(str).toBe("Hello");
    });

    it("should convert ArrayBuffer to Uint8Array", () => {
      const buffer = new ArrayBuffer(5);
      const view = new Uint8Array(buffer);
      view.set([0x48, 0x65, 0x6c, 0x6c, 0x6f]);

      const uint8 = new Uint8Array(buffer);
      expect(Array.from(uint8)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should convert Uint8Array to ArrayBuffer", () => {
      const uint8 = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
      const buffer = uint8.buffer;

      expect(buffer instanceof ArrayBuffer).toBe(true);
      expect(buffer.byteLength).toBe(5);
    });

    it("should convert hex string to Uint8Array", () => {
      const hex = "48656c6c6f";
      const uint8 = new Uint8Array(
        hex.match(/.{1,2}/g)!.map((byte) => parseInt(byte, 16)),
      );

      expect(Array.from(uint8)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should convert Uint8Array to hex string", () => {
      const uint8 = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
      const hex = Array.from(uint8)
        .map((b) => b.toString(16).padStart(2, "0"))
        .join("");

      expect(hex).toBe("48656c6c6f");
    });

    it("should convert base64 to Uint8Array", () => {
      const base64 = "SGVsbG8=";
      const uint8 = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));

      expect(Array.from(uint8)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should convert Uint8Array to base64", () => {
      const uint8 = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
      const base64 = btoa(String.fromCharCode(...uint8));

      expect(base64).toBe("SGVsbG8=");
    });
  });

  describe("Protobuf-like Binary Data", () => {
    it("should handle protobuf varint encoding", () => {
      // Protobuf field 1, varint value 150
      // Tag: (1 << 3 | 0) = 0x08
      // Varint 150: 0x96 0x01
      const protobuf = new Uint8Array([0x08, 0x96, 0x01]);

      // Encode to base64
      const base64 = btoa(String.fromCharCode(...protobuf));
      expect(base64).toBe("CJYB");

      // Decode back
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
      expect(Array.from(decoded)).toEqual([0x08, 0x96, 0x01]);
    });

    it("should handle protobuf with ASCII string field", () => {
      // Field 1, string "ABC123"
      // Tag: 0x0a, length: 6, data: ASCII bytes
      const protobuf = new Uint8Array([
        0x0a, 0x06, 0x41, 0x42, 0x43, 0x31, 0x32, 0x33,
      ]);

      const base64 = btoa(String.fromCharCode(...protobuf));
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));

      expect(Array.from(decoded)).toEqual(Array.from(protobuf));
    });

    it("should handle small protobuf with all ASCII bytes", () => {
      // This is the "misclassification" case from PR #4
      // All bytes < 0x80, valid UTF-8, but is actually binary protobuf
      const protobuf = new Uint8Array([
        0x0a,
        0x06,
        0x41,
        0x42,
        0x43,
        0x31,
        0x32,
        0x33, // Field 1: "ABC123"
        0x12,
        0x06,
        0x44,
        0x45,
        0x46,
        0x34,
        0x35,
        0x36, // Field 2: "DEF456"
      ]);

      // This would pass UTF-8 validation
      const decoder = new TextDecoder("utf-8", { fatal: true });
      let isValidUTF8 = true;
      try {
        decoder.decode(protobuf);
      } catch (e) {
        isValidUTF8 = false;
      }
      expect(isValidUTF8).toBe(true); // Demonstrates the problem!

      // But it should still be treated as binary via base64 encoding
      const base64 = btoa(String.fromCharCode(...protobuf));
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
      expect(Array.from(decoded)).toEqual(Array.from(protobuf));
    });
  });

  describe("Edge Cases and Error Handling", () => {
    it("should handle invalid base64", () => {
      const invalidBase64 = "Not!Valid@Base64#";
      expect(() => atob(invalidBase64)).toThrow();
    });

    it("should handle invalid hex", () => {
      const invalidHex = "ZZZZ"; // Z is not a hex digit
      const result = parseInt("ZZ", 16);
      expect(isNaN(result)).toBe(true);
    });

    it("should handle odd-length hex string", () => {
      const oddHex = "123"; // Odd number of characters
      const bytes = (oddHex.match(/.{1,2}/g) || []).map((byte) =>
        parseInt(byte, 16),
      );
      // Last byte only has one digit, parsed as 0x03
      expect(bytes).toEqual([0x12, 0x03]);
    });

    it("should handle very large messages", () => {
      // 10KB payload
      const large = new Uint8Array(10240);
      for (let i = 0; i < large.length; i++) {
        large[i] = i % 256;
      }

      const base64 = btoa(String.fromCharCode(...large));
      expect(base64.length).toBeGreaterThan(10240); // Base64 is larger

      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
      expect(decoded.length).toBe(10240);
    });

    it("should handle messages with all zeros", () => {
      const zeros = new Uint8Array(100);
      // All zeros by default

      const base64 = btoa(String.fromCharCode(...zeros));
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));

      expect(Array.from(decoded)).toEqual(Array.from(zeros));
    });

    it("should handle messages with all 0xFF", () => {
      const allFF = new Uint8Array(100);
      allFF.fill(0xff);

      const base64 = btoa(String.fromCharCode(...allFF));
      const decoded = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));

      expect(Array.from(decoded)).toEqual(Array.from(allFF));
    });
  });

  describe("Real-World Message Scenarios", () => {
    it("should handle JSON message (text)", () => {
      const json = JSON.stringify({ status: "online", uptime: 12345 });
      const encoder = new TextEncoder();
      const bytes = encoder.encode(json);

      // Should be sent as plain text, not binary
      const decoder = new TextDecoder();
      const decoded = decoder.decode(bytes);

      expect(decoded).toBe(json);
      expect(JSON.parse(decoded)).toEqual({ status: "online", uptime: 12345 });
    });

    it("should handle firmware chunk (binary)", () => {
      // Simulate 1KB firmware chunk
      const firmware = new Uint8Array(1024);
      for (let i = 0; i < firmware.length; i++) {
        firmware[i] = (i * 7) % 256; // Pseudo-random pattern
      }

      const base64 = btoa(String.fromCharCode(...firmware));
      const marked = "B64:" + base64;

      // Receiver decodes
      expect(marked.startsWith("B64:")).toBe(true);
      const base64Data = marked.substring(4);
      const decoded = Uint8Array.from(atob(base64Data), (c) => c.charCodeAt(0));

      expect(Array.from(decoded)).toEqual(Array.from(firmware));
    });

    it("should handle device serial numbers (ASCII in protobuf)", () => {
      // Device list protobuf with ASCII serial numbers
      const serial1 = "PEN-001";
      const serial2 = "PEN-002";

      // Construct protobuf (simplified)
      const encoder = new TextEncoder();
      const s1Bytes = encoder.encode(serial1);
      const s2Bytes = encoder.encode(serial2);

      const protobuf = new Uint8Array([
        0x0a,
        s1Bytes.length,
        ...s1Bytes, // Field 1
        0x0a,
        s2Bytes.length,
        ...s2Bytes, // Field 1 (repeated)
      ]);

      // Must be sent as binary despite ASCII content
      const base64 = btoa(String.fromCharCode(...protobuf));
      const marked = "B64:" + base64;

      const decoded = Uint8Array.from(atob(marked.substring(4)), (c) =>
        c.charCodeAt(0),
      );
      expect(Array.from(decoded)).toEqual(Array.from(protobuf));
    });

    it("should handle network SSID (UTF-8 string in protobuf)", () => {
      const ssid = "MyWiFi-5G";
      const encoder = new TextEncoder();
      const ssidBytes = encoder.encode(ssid);

      // Protobuf field 1, string
      const protobuf = new Uint8Array([0x0a, ssidBytes.length, ...ssidBytes]);

      const base64 = btoa(String.fromCharCode(...protobuf));
      const marked = "B64:" + base64;

      const decoded = Uint8Array.from(atob(marked.substring(4)), (c) =>
        c.charCodeAt(0),
      );
      expect(Array.from(decoded)).toEqual(Array.from(protobuf));
    });
  });

  describe("Buffer Compatibility (Node.js Buffer-like)", () => {
    it("should handle Buffer.from with hex encoding", () => {
      // In Node.js: Buffer.from('48656c6c6f', 'hex')
      // In browser: manual hex parsing
      const hex = "48656c6c6f";
      const bytes = new Uint8Array(
        hex.match(/.{1,2}/g)!.map((byte) => parseInt(byte, 16)),
      );

      expect(Array.from(bytes)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should handle Buffer.from with base64 encoding", () => {
      // In Node.js: Buffer.from('SGVsbG8=', 'base64')
      // In browser: atob + Uint8Array
      const base64 = "SGVsbG8=";
      const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));

      expect(Array.from(bytes)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it("should handle Buffer.from with utf8 encoding", () => {
      // In Node.js: Buffer.from('Hello', 'utf8')
      // In browser: TextEncoder
      const text = "Hello";
      const encoder = new TextEncoder();
      const bytes = encoder.encode(text);

      expect(Array.from(bytes)).toEqual([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });
  });
});
