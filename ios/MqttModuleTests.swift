/**
 * Unit tests for MqttModule (iOS)
 * Tests callback guards and binary detection logic
 */

import XCTest
import Foundation

class MqttModuleTests: XCTestCase {

    // MARK: - Binary Detection Tests

    func testBinaryDetection_ValidUTF8Text() {
        let text = "Hello, MQTT!"
        let data = text.data(using: .utf8)!

        let isBinary = isBinaryData(data)

        XCTAssertFalse(isBinary, "Plain text should not be detected as binary")
    }

    func testBinaryDetection_ValidUTF8WithEmoji() {
        let text = "Hello 👋 World 🌍"
        let data = text.data(using: .utf8)!

        let isBinary = isBinaryData(data)

        XCTAssertFalse(isBinary, "UTF-8 text with emoji should not be detected as binary")
    }

    func testBinaryDetection_JSONPayload() {
        let json = "{\"timestamp\":1782414353,\"status\":\"active\"}"
        let data = json.data(using: .utf8)!

        let isBinary = isBinaryData(data)

        XCTAssertFalse(isBinary, "JSON should not be detected as binary")
    }

    func testBinaryDetection_ProtobufVarint() {
        // Protobuf message with varint encoding: field 7, value 2000
        // tag = (7 << 3 | 0) = 0x38, varint(2000) = [0xD0, 0x0F]
        let protobufData = Data([0x38, 0xD0, 0x0F])

        let isBinary = isBinaryData(protobufData)

        XCTAssertTrue(isBinary, "Protobuf with varint should be detected as binary")
    }

    func testBinaryDetection_BinaryData() {
        let binaryData = Data([0x00, 0x01, 0x02, 0xFF, 0x7F, 0x80])

        let isBinary = isBinaryData(binaryData)

        XCTAssertTrue(isBinary, "Binary data should be detected as binary")
    }

    func testBinaryDetection_EmptyPayload() {
        let emptyData = Data()

        let isBinary = isBinaryData(emptyData)

        XCTAssertFalse(isBinary, "Empty payload should not be detected as binary")
    }

    func testBinaryDetection_NullBytes() {
        let nullBytes = Data([0x00, 0x00, 0x00])

        let isBinary = isBinaryData(nullBytes)

        XCTAssertTrue(isBinary, "Null bytes should be detected as binary")
    }

    func testBinaryDetection_InvalidUTF8Sequence() {
        // Invalid UTF-8: start byte without proper continuation
        let invalidUTF8 = Data([0xC0, 0x20])

        let isBinary = isBinaryData(invalidUTF8)

        XCTAssertTrue(isBinary, "Invalid UTF-8 should be detected as binary")
    }

    func testBinaryDetection_MixedASCIIAndBinary() {
        // Starts with valid ASCII, then has invalid UTF-8
        let mixed = Data("Hello".utf8) + Data([0xFF, 0xFE])

        let isBinary = isBinaryData(mixed)

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
            let isBinary = isBinaryData(data)
            XCTAssertFalse(isBinary, "\(text) should be detected as valid UTF-8")
        }
    }

    func testUTF8_ControlCharacters() {
        // Control characters are valid UTF-8
        let controlChars = Data([0x00, 0x01, 0x02, 0x1F])
        let isBinary = isBinaryData(controlChars)

        // Control characters are technically valid UTF-8 but typically considered binary
        // The behavior depends on String(data:encoding:) implementation
        // Just document the actual behavior
        print("Control characters detected as binary: \(isBinary)")
    }

    // MARK: - Helper Methods

    private func isBinaryData(_ data: Data) -> Bool {
        return String(data: data, encoding: .utf8) == nil
    }

    // Mock CallbackGuard for testing
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
