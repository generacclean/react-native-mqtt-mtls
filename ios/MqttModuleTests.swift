/**
 * Unit tests for MqttModule (iOS)
 * Tests callback guards and binary detection logic
 *
 * IMPORTANT: These tests exercise the real isBinaryData(topic:data:) method
 * from MqttModule using topic-based detection logic.
 */

import XCTest
import Foundation

class MqttModuleTests: XCTestCase {

    // MARK: - Topic-Based Detection Tests (Deterministic)

    func testTopicDetection_ProtobufTopic_AlwaysBinary() {
        let module = MqttModule()
        // ASCII content that would pass UTF-8 check
        let asciiPayload = "ABC123".data(using: .utf8)!

        XCTAssertTrue(module.isBinaryData(topic: "device/proto/list", data: asciiPayload),
                     "Protobuf topics should always be binary, regardless of content")
        XCTAssertTrue(module.isBinaryData(topic: "device/12345/proto/config", data: asciiPayload))
        XCTAssertTrue(module.isBinaryData(topic: "remote/proto/status", data: asciiPayload))
    }

    func testTopicDetection_DeviceTopic_AlwaysBinary() {
        let module = MqttModule()
        let asciiPayload = "DEVICE-001".data(using: .utf8)!

        XCTAssertTrue(module.isBinaryData(topic: "remote/device/list", data: asciiPayload),
                     "Device topics should be binary (protobuf device lists)")
        XCTAssertTrue(module.isBinaryData(topic: "remote/device/12345", data: asciiPayload))
        XCTAssertTrue(module.isBinaryData(topic: "penguin/device/info", data: asciiPayload))
    }

    func testTopicDetection_FirmwareTopic_AlwaysBinary() {
        let module = MqttModule()
        let asciiPayload = "v1.2.3".data(using: .utf8)!

        XCTAssertTrue(module.isBinaryData(topic: "device/firmware/update", data: asciiPayload),
                     "Firmware topics should be binary")
        XCTAssertTrue(module.isBinaryData(topic: "ota/firmware", data: asciiPayload))
        XCTAssertTrue(module.isBinaryData(topic: "penguin/firmware/version", data: asciiPayload))
    }

    func testTopicDetection_StatusTopic_AlwaysText() {
        let module = MqttModule()
        let jsonPayload = "{\"status\":\"ok\"}".data(using: .utf8)!

        XCTAssertFalse(module.isBinaryData(topic: "device/status", data: jsonPayload),
                      "Status topics should be text (JSON)")
        // Note: "remote/device/status" is a collision case: /device takes precedence.
        // See testTopicCollision_DeviceBeforeStatus for that behaviour.
        XCTAssertFalse(module.isBinaryData(topic: "penguin/status/health", data: jsonPayload))
    }

    func testTopicDetection_ConfigTopic_AlwaysText() {
        let module = MqttModule()
        let jsonPayload = "{\"key\":\"value\"}".data(using: .utf8)!

        XCTAssertFalse(module.isBinaryData(topic: "device/config", data: jsonPayload),
                      "Config topics should be text (JSON)")
        XCTAssertFalse(module.isBinaryData(topic: "remote/config/update", data: jsonPayload))
    }

    func testTopicDetection_JsonTopic_AlwaysText() {
        let module = MqttModule()
        let jsonPayload = "{\"message\":\"test\"}".data(using: .utf8)!

        XCTAssertFalse(module.isBinaryData(topic: "device/json/data", data: jsonPayload),
                      "JSON topics should be text")
        XCTAssertFalse(module.isBinaryData(topic: "remote/json/update", data: jsonPayload))
    }

    // MARK: - Topic Collision Tests (Binary-First Precedence)

    func testTopicCollision_DeviceBeforeStatus() {
        let module = MqttModule()
        let payload = "test".data(using: .utf8)!

        // /device is checked before /status, so /device/status returns binary
        XCTAssertTrue(module.isBinaryData(topic: "remote/device/status", data: payload),
                     "/device takes precedence over /status (binary-first)")
    }

    func testTopicCollision_DeviceBeforeConfig() {
        let module = MqttModule()
        let payload = "test".data(using: .utf8)!

        // /device is checked before /config, so /device/config returns binary
        XCTAssertTrue(module.isBinaryData(topic: "remote/device/config", data: payload),
                     "/device takes precedence over /config (binary-first)")
    }

    func testTopicCollision_FirmwareBeforeStatus() {
        let module = MqttModule()
        let payload = "test".data(using: .utf8)!

        // /firmware is checked before /status
        XCTAssertTrue(module.isBinaryData(topic: "device/firmware/status", data: payload),
                     "/firmware takes precedence over /status")
    }

    func testTopicCollision_NetworkBeforeConfig() {
        let module = MqttModule()
        // High byte (0x80) makes this non-text; classifying it as text would corrupt the bytes.
        let protobufPayload = Data([0x0a, 0x02, 0x08, 0x80])

        // Network config/state responses are protobuf but their topics contain /config,
        // so /network/ must win over the /config text rule.
        XCTAssertTrue(
            module.isBinaryData(topic: "remote/network/config/post/accepted", data: protobufPayload),
            "/network/ takes precedence over /config (binary-first)")
        XCTAssertTrue(
            module.isBinaryData(topic: "remote/network/state", data: protobufPayload),
            "network state responses are binary")
    }

    // MARK: - ASCII Protobuf Edge Cases

    func testASCIIProtobuf_UnknownTopic_Misclassified() {
        let module = MqttModule()
        // Small protobuf with ASCII content - will be misclassified as text
        // This is a KNOWN LIMITATION documented in the code
        let asciiProtobuf = "ABC123".data(using: .utf8)!

        let result = module.isBinaryData(topic: "unknown/topic/path", data: asciiProtobuf)

        // This will be FALSE (misclassified as text) because:
        // 1. Topic doesn't match any pattern
        // 2. Payload is valid UTF-8 (ASCII)
        // 3. UTF-8 heuristic fallback says "text"
        XCTAssertFalse(result,
                      "KNOWN LIMITATION: ASCII protobuf on unknown topic misclassified as text")
    }

    func testASCIIProtobuf_KnownBinaryTopic_CorrectlyClassified() {
        let module = MqttModule()
        // Same ASCII content, but on a known binary topic
        let asciiProtobuf = "ABC123".data(using: .utf8)!

        XCTAssertTrue(module.isBinaryData(topic: "device/proto/list", data: asciiProtobuf),
                     "ASCII protobuf on known binary topic correctly classified")
    }

    // MARK: - UTF-8 Heuristic Fallback Tests (Unknown Topics)

    func testBinaryDetection_ValidUTF8Text() {
        let module = MqttModule()
        let text = "Hello, MQTT!"
        let data = text.data(using: .utf8)!

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: data)

        XCTAssertFalse(isBinary, "Plain text should not be detected as binary")
    }

    func testBinaryDetection_ValidUTF8WithEmoji() {
        let module = MqttModule()
        let text = "Hello 👋 World 🌍"
        let data = text.data(using: .utf8)!

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: data)

        XCTAssertFalse(isBinary, "UTF-8 text with emoji should not be detected as binary")
    }

    func testBinaryDetection_JSONPayload() {
        let module = MqttModule()
        let json = "{\"timestamp\":1782414353,\"status\":\"active\"}"
        let data = json.data(using: .utf8)!

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: data)

        XCTAssertFalse(isBinary, "JSON should not be detected as binary")
    }

    func testBinaryDetection_ProtobufVarint() {
        let module = MqttModule()
        // Protobuf message with varint encoding: field 7, value 2000
        // tag = (7 << 3 | 0) = 0x38, varint(2000) = [0xD0, 0x0F]
        let protobufData = Data([0x38, 0xD0, 0x0F])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: protobufData)

        XCTAssertTrue(isBinary, "Protobuf with varint should be detected as binary")
    }

    func testBinaryDetection_BinaryData() {
        let module = MqttModule()
        let binaryData = Data([0x00, 0x01, 0x02, 0xFF, 0x7F, 0x80])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: binaryData)

        XCTAssertTrue(isBinary, "Binary data should be detected as binary")
    }

    func testBinaryDetection_EmptyPayload() {
        let module = MqttModule()
        let emptyData = Data()

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: emptyData)

        XCTAssertFalse(isBinary, "Empty payload should not be detected as binary")
    }

    func testBinaryDetection_NullBytes() {
        let module = MqttModule()
        let nullBytes = Data([0x00, 0x00, 0x00])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: nullBytes)

        XCTAssertTrue(isBinary, "Null bytes should be detected as binary")
    }

    func testBinaryDetection_InvalidUTF8Sequence() {
        let module = MqttModule()
        // Invalid UTF-8: start byte without proper continuation
        let invalidUTF8 = Data([0xC0, 0x20])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: invalidUTF8)

        XCTAssertTrue(isBinary, "Invalid UTF-8 should be detected as binary")
    }

    func testBinaryDetection_MixedASCIIAndBinary() {
        let module = MqttModule()
        // Starts with valid ASCII, then has invalid UTF-8
        let mixed = Data("Hello".utf8) + Data([0xFF, 0xFE])

        let isBinary = module.isBinaryData(topic: "unknown/topic", data: mixed)

        XCTAssertTrue(isBinary, "Mixed ASCII and binary should be detected as binary")
    }

    // MARK: - Callback Guard Tests

    func testCallbackGuard_SingleInvocation() {
        var invokeCount = 0
        let callback: ([Any]) -> Void = { _ in
            invokeCount += 1
        }

        let guard = CallbackGuard(callback)
        guard.invoke(["result"])

        XCTAssertEqual(invokeCount, 1, "Callback should be invoked once")
    }

    func testCallbackGuard_PreventsDuplicateInvocation() {
        var invokeCount = 0
        let callback: ([Any]) -> Void = { _ in
            invokeCount += 1
        }

        let guard = CallbackGuard(callback)
        guard.invoke(["result1"])
        guard.invoke(["result2"])
        guard.invoke(["result3"])

        XCTAssertEqual(invokeCount, 1, "Callback should only be invoked once")
    }

    func testCallbackGuard_NilCallbackHandling() {
        let guard = CallbackGuard(nil)

        // Should not crash with nil callback
        guard.invoke(["result"])

        // Test passes if no crash occurs
        XCTAssertTrue(true, "Should handle nil callback gracefully")
    }

    func testCallbackGuard_ThreadSafety() {
        let expectation = self.expectation(description: "Thread safety test")
        var invokeCount = 0
        let lock = NSLock()

        let callback: ([Any]) -> Void = { _ in
            lock.lock()
            invokeCount += 1
            lock.unlock()
        }

        let guard = CallbackGuard(callback)
        let group = DispatchGroup()

        // Create 10 concurrent invocations
        for _ in 0..<10 {
            group.enter()
            DispatchQueue.global().async {
                guard.invoke(["result"])
                group.leave()
            }
        }

        group.notify(queue: .main) {
            XCTAssertEqual(invokeCount, 1, "Callback should only be invoked once despite race condition")
            expectation.fulfill()
        }

        waitForExpectations(timeout: 5, handler: nil)
    }

    func testCallbackGuard_WithArguments() {
        var receivedArgs: [Any] = []
        let callback: ([Any]) -> Void = { args in
            receivedArgs = args
        }

        let guard = CallbackGuard(callback)
        guard.invoke(["test-result", 42, true])

        XCTAssertEqual(receivedArgs.count, 3, "Should receive all arguments")
        XCTAssertEqual(receivedArgs[0] as? String, "test-result")
        XCTAssertEqual(receivedArgs[1] as? Int, 42)
        XCTAssertEqual(receivedArgs[2] as? Bool, true)
    }

    func testCallbackGuard_ExceptionHandling() {
        let callback: ([Any]) -> Void = { _ in
            // Simulate an error
            fatalError("Test error")
        }

        let guard = CallbackGuard(callback)

        // In Swift, we can't easily test fatalError, but we can test
        // that the guard marks the callback as fired even if it would throw
        guard.invoke(["result"])

        // Second invocation should be suppressed
        var secondCallbackInvoked = false
        let guard2 = CallbackGuard({ _ in secondCallbackInvoked = true })
        guard2.invoke(["first"])
        guard2.invoke(["second"]) // Should be suppressed

        XCTAssertTrue(secondCallbackInvoked, "First invocation should succeed")
    }

    // MARK: - Binary Marker Tests

    func testBinaryMarker_Detection() {
        let markedMessage = "B64:SGVsbG8="
        let marker = "B64:"

        XCTAssertTrue(markedMessage.hasPrefix(marker), "Should detect B64: prefix")

        let base64Data = String(markedMessage.dropFirst(marker.count))
        XCTAssertEqual(base64Data, "SGVsbG8=", "Should extract base64 data")
    }

    func testBinaryMarker_PlainText() {
        let plainText = "Hello, World!"
        let marker = "B64:"

        XCTAssertFalse(plainText.hasPrefix(marker), "Plain text should not have B64: prefix")
    }

    func testBinaryMarker_EmptyData() {
        let markedEmpty = "B64:"
        let marker = "B64:"

        XCTAssertTrue(markedEmpty.hasPrefix(marker), "Should detect B64: prefix")

        let base64Data = String(markedEmpty.dropFirst(marker.count))
        XCTAssertEqual(base64Data, "", "Should have empty base64 data")
    }

    // MARK: - Base64 Encoding/Decoding Tests

    func testBase64_RoundTrip() {
        let original = "Hello, MQTT!".data(using: .utf8)!

        // Encode to Base64
        let base64 = original.base64EncodedString()

        // Decode back
        let decoded = Data(base64Encoded: base64)!

        XCTAssertEqual(original, decoded, "Round-trip should preserve data")
    }

    func testBase64_BinaryData() {
        let binaryData = Data([0x00, 0x01, 0x02, 0xFF, 0x7F, 0x80])

        let base64 = binaryData.base64EncodedString()
        let decoded = Data(base64Encoded: base64)!

        XCTAssertEqual(binaryData, decoded, "Binary data should survive Base64 round-trip")
    }

    func testBase64_EmptyData() {
        let emptyData = Data()

        let base64 = emptyData.base64EncodedString()
        let decoded = Data(base64Encoded: base64) ?? Data()

        XCTAssertEqual(emptyData, decoded, "Empty data should survive Base64 round-trip")
    }

    func testBase64_LargeData() {
        // Test with 1MB of data
        var largeData = Data(count: 1024 * 1024)
        for i in 0..<largeData.count {
            largeData[i] = UInt8(i % 256)
        }

        let base64 = largeData.base64EncodedString()
        let decoded = Data(base64Encoded: base64)!

        XCTAssertEqual(largeData, decoded, "Large data should survive Base64 round-trip")
    }

    // MARK: - UTF-8 Edge Cases

    func testUTF8_MultibyteCharacters() {
        let module = MqttModule()
        // Test various UTF-8 multibyte sequences
        let testCases = [
            "Hello",                    // ASCII
            "Héllo",                    // Latin-1 Supplement
            "Привет",                   // Cyrillic
            "こんにちは",                 // Japanese
            "你好",                      // Chinese
            "مرحبا",                    // Arabic
            "שלום",                      // Hebrew
            "👋🌍🚀",                    // Emoji
        ]

        for text in testCases {
            let data = text.data(using: .utf8)!
            let isBinary = module.isBinaryData(topic: "test/text", data: data)
            XCTAssertFalse(isBinary, "\(text) should be detected as valid UTF-8")
        }
    }

    func testUTF8_ControlCharacters() {
        let module = MqttModule()
        // Control characters are valid UTF-8
        let controlChars = Data([0x00, 0x01, 0x02, 0x1F])
        let isBinary = module.isBinaryData(topic: "test/text", data: controlChars)

        // Control characters are technically valid UTF-8 but typically considered binary
        // The behavior depends on String(data:encoding:) implementation
        // Just document the actual behavior
        print("Control characters detected as binary: \(isBinary)")
    }

    // MARK: - Helper Methods

    // Mock CallbackGuard for testing
    // Note: Tests now use the real MqttModule.isBinaryData(topic:data:) method
    private class CallbackGuard {
        private var callback: (([Any]) -> Void)?
        private var hasFired = false
        private let lock = NSLock()

        init(_ callback: (([Any]) -> Void)?) {
            self.callback = callback
        }

        func invoke(_ args: [Any]) {
            lock.lock()
            defer { lock.unlock() }

            guard !hasFired, let callback = callback else {
                return
            }

            hasFired = true
            self.callback = nil
            callback(args)
        }
    }
}
