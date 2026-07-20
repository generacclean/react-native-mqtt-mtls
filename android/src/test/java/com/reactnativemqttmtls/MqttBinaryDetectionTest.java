package com.reactnativemqttmtls;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.facebook.react.bridge.ReactApplicationContext;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Comprehensive tests for binary detection in MqttModule
 * Tests topic-based detection and UTF-8 heuristic fallback
 *
 * This addresses PR #4 reviewer concerns about:
 * - Topic-based deterministic detection
 * - ASCII protobuf misclassification
 * - Hex vs binary vs string vs Uint8Array handling
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MqttBinaryDetectionTest {

    @Mock
    private ReactApplicationContext mockContext;

    private MqttModule mqttModule;
    private Method isBinaryDataMethod;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        mqttModule = new MqttModule(mockContext);

        // Access private isBinaryData method via reflection
        isBinaryDataMethod = MqttModule.class.getDeclaredMethod(
            "isBinaryData", String.class, byte[].class);
        isBinaryDataMethod.setAccessible(true);
    }

    /**
     * Helper to call the private isBinaryData(topic, payload) method
     */
    private boolean isBinaryData(String topic, byte[] payload) throws Exception {
        return (boolean) isBinaryDataMethod.invoke(mqttModule, topic, payload);
    }

    // ========================================================================
    // Topic-Based Detection Tests (Deterministic)
    // ========================================================================

    @Test
    public void testTopicDetection_ProtobufTopic_AlwaysBinary() throws Exception {
        // ASCII content that would pass UTF-8 check
        byte[] asciiPayload = "ABC123".getBytes(StandardCharsets.UTF_8);

        assertTrue("Protobuf topics should always be binary, regardless of content",
            isBinaryData("device/proto/list", asciiPayload));
        assertTrue(isBinaryData("device/12345/proto/config", asciiPayload));
        assertTrue(isBinaryData("remote/proto/status", asciiPayload));
    }

    @Test
    public void testTopicDetection_DeviceTopic_AlwaysBinary() throws Exception {
        byte[] asciiPayload = "DEVICE-001".getBytes(StandardCharsets.UTF_8);

        assertTrue("Device topics should be binary (protobuf device lists)",
            isBinaryData("remote/device/list", asciiPayload));
        assertTrue(isBinaryData("remote/device/12345", asciiPayload));
        assertTrue(isBinaryData("penguin/device/info", asciiPayload));
    }

    @Test
    public void testTopicDetection_FirmwareTopic_AlwaysBinary() throws Exception {
        byte[] asciiPayload = "v1.2.3".getBytes(StandardCharsets.UTF_8);

        assertTrue("Firmware topics should be binary",
            isBinaryData("device/firmware/update", asciiPayload));
        assertTrue(isBinaryData("ota/firmware", asciiPayload));
        assertTrue(isBinaryData("penguin/firmware/version", asciiPayload));
    }

    @Test
    public void testTopicDetection_RmaTopic_AlwaysBinary() throws Exception {
        byte[] asciiPayload = "SWAP123".getBytes(StandardCharsets.UTF_8);

        assertTrue("RMA topics should be binary (protobuf messages)",
            isBinaryData("device/rma/swap", asciiPayload));
        assertTrue(isBinaryData("remote/rma/request", asciiPayload));
    }

    @Test
    public void testTopicDetection_AssemblyTopic_AlwaysBinary() throws Exception {
        byte[] asciiPayload = "ASSY001".getBytes(StandardCharsets.UTF_8);

        assertTrue("Assembly topics should be binary",
            isBinaryData("device/assembly/info", asciiPayload));
        assertTrue(isBinaryData("penguin/installed/hardware", asciiPayload));
    }

    @Test
    public void testTopicDetection_StatusTopic_AlwaysText() throws Exception {
        // Binary content that would fail UTF-8 check
        byte[] binaryPayload = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};

        assertFalse("Status topics should always be text (JSON)",
            isBinaryData("device/status", binaryPayload));
        assertFalse(isBinaryData("penguin/status/health", binaryPayload));
    }

    @Test
    public void testTopicDetection_JsonTopic_AlwaysText() throws Exception {
        byte[] binaryPayload = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};

        assertFalse("JSON topics should always be text",
            isBinaryData("device/json/config", binaryPayload));
        assertFalse(isBinaryData("remote/json/response", binaryPayload));
    }

    @Test
    public void testTopicDetection_ConfigTopic_AlwaysText() throws Exception {
        byte[] binaryPayload = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};

        assertFalse("Config topics should be text (JSON configuration)",
            isBinaryData("device/config", binaryPayload));
        assertFalse(isBinaryData("penguin/config/network", binaryPayload));
    }

    @Test
    public void testTopicDetection_CommandTopic_AlwaysText() throws Exception {
        byte[] binaryPayload = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};

        assertFalse("Command topics should be text",
            isBinaryData("device/command/execute", binaryPayload));
    }

    // ========================================================================
    // Topic Collision Tests (PR #4 Reviewer Concern)
    // ========================================================================

    @Test
    public void testTopicCollision_DeviceBeforeStatus() throws Exception {
        // "/device" pattern matches before "/status" pattern
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);

        // This topic contains both "/device" and "/status"
        // Should be classified as BINARY because "/device" check runs first
        assertTrue("Binary patterns take precedence over text patterns",
            isBinaryData("penguin/device/status", payload));
    }

    @Test
    public void testTopicCollision_ProtoInPath() throws Exception {
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);

        // Contains "/proto/" in the path
        assertTrue("Proto pattern should match substring in path",
            isBinaryData("remote/proto/device/list", payload));
    }

    @Test
    public void testTopicCollision_ConfigWithDevice() throws Exception {
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);

        // Contains both "/device" (binary) and "/config" (text)
        // Binary check runs first, so it's classified as binary
        assertTrue("Device pattern matches before config",
            isBinaryData("penguin/device/config", payload));
    }

    // ========================================================================
    // UTF-8 Heuristic Fallback Tests (Unknown Topics)
    // ========================================================================

    @Test
    public void testHeuristicFallback_UnknownTopic_ValidUTF8_IsText() throws Exception {
        String text = "Hello, World!";
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);

        // Unknown topic falls back to UTF-8 heuristic
        assertFalse("Unknown topic with valid UTF-8 should be text",
            isBinaryData("unknown/topic/path", payload));
    }

    @Test
    public void testHeuristicFallback_UnknownTopic_InvalidUTF8_IsBinary() throws Exception {
        // Invalid UTF-8 sequence
        byte[] payload = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};

        assertTrue("Unknown topic with invalid UTF-8 should be binary",
            isBinaryData("unknown/topic/path", payload));
    }

    @Test
    public void testHeuristicFallback_NullTopic_UsesUTF8Check() throws Exception {
        byte[] validUTF8 = "text".getBytes(StandardCharsets.UTF_8);
        byte[] invalidUTF8 = {(byte) 0xFF, (byte) 0xFE};

        assertFalse("Null topic with valid UTF-8 should be text",
            isBinaryData(null, validUTF8));
        assertTrue("Null topic with invalid UTF-8 should be binary",
            isBinaryData(null, invalidUTF8));
    }

    // ========================================================================
    // ASCII Protobuf Misclassification Tests (PR #4 Core Bug)
    // ========================================================================

    @Test
    public void testASCIIProtobuf_DeviceListWithSerialNumbers() throws Exception {
        // Simulates small protobuf with ASCII serial numbers
        // Field tags 1, 2, 3 (all < 0x80) + ASCII string "ABC123"
        byte[] asciiProtobuf = {
            0x0a, 0x06, 'A', 'B', 'C', '1', '2', '3',  // Field 1: string "ABC123"
            0x12, 0x06, 'D', 'E', 'F', '4', '5', '6'   // Field 2: string "DEF456"
        };

        // WITHOUT topic-based detection, this would be misclassified as text
        // WITH topic-based detection, it's correctly identified as binary
        assertTrue("ASCII protobuf on device topic should be binary",
            isBinaryData("device/proto/list", asciiProtobuf));
    }

    @Test
    public void testASCIIProtobuf_UnknownTopic_Misclassified() throws Exception {
        // Same ASCII protobuf but on unknown topic
        byte[] asciiProtobuf = {
            0x0a, 0x06, 'A', 'B', 'C', '1', '2', '3'
        };

        // This demonstrates the UTF-8 heuristic limitation
        // All bytes are valid UTF-8, so it's misclassified as text
        assertFalse("ASCII protobuf on unknown topic falls victim to UTF-8 heuristic",
            isBinaryData("unknown/custom/topic", asciiProtobuf));
    }

    // ========================================================================
    // Encoding Format Tests (Hex vs Binary vs String vs Uint8Array)
    // ========================================================================

    @Test
    public void testEncoding_HexString_AfterDecoding() throws Exception {
        // In practice, hex strings are decoded before this method is called
        String hexString = "48656c6c6f"; // "Hello" in hex

        // Hex string itself is valid UTF-8 text
        byte[] hexBytes = hexString.getBytes(StandardCharsets.UTF_8);
        assertFalse("Hex string (not yet decoded) is text",
            isBinaryData("unknown/topic", hexBytes));

        // After hex decoding (not done by this method, but by caller)
        byte[] decoded = hexStringToBytes(hexString);
        assertFalse("Decoded 'Hello' is valid UTF-8 text",
            isBinaryData("unknown/topic", decoded));
    }

    @Test
    public void testEncoding_Base64String_BeforeDecoding() throws Exception {
        // Base64 string before decoding
        String base64 = "SGVsbG8="; // "Hello" in Base64

        byte[] base64Bytes = base64.getBytes(StandardCharsets.UTF_8);
        assertFalse("Base64 string (not yet decoded) is valid UTF-8 text",
            isBinaryData("unknown/topic", base64Bytes));
    }

    @Test
    public void testEncoding_RawBinaryBytes() throws Exception {
        // Raw binary bytes (simulating Uint8Array from JS)
        byte[] binaryBytes = {0x00, 0x01, 0x02, (byte) 0xFF, 0x7F, (byte) 0x80};

        assertTrue("Raw binary bytes should be detected as binary",
            isBinaryData("unknown/topic", binaryBytes));
    }

    @Test
    public void testEncoding_UTF8StringBytes() throws Exception {
        // UTF-8 encoded string bytes (simulating JS TextEncoder)
        String text = "Hello, MQTT!";
        byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);

        assertFalse("UTF-8 string bytes should be text",
            isBinaryData("unknown/topic", utf8Bytes));
    }

    @Test
    public void testEncoding_ProtobufVarint_HighBit() throws Exception {
        // Protobuf varint with high bit set (> 0x80)
        // This is the case where UTF-8 heuristic works correctly
        byte[] varintProtobuf = {
            0x08, (byte) 0xD0, 0x0F  // Field 1: varint 2000
        };

        assertTrue("Protobuf with high-bit varint is invalid UTF-8, detected as binary",
            isBinaryData("unknown/topic", varintProtobuf));
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testEdgeCase_EmptyPayload() throws Exception {
        byte[] empty = new byte[0];

        // Empty payload is technically valid UTF-8
        assertFalse("Empty payload on unknown topic is text",
            isBinaryData("unknown/topic", empty));

        // Even on binary topic, empty is... well, empty (but marked as binary by topic)
        assertTrue("Empty payload on binary topic is binary by topic rule",
            isBinaryData("device/proto/list", empty));
    }

    @Test
    public void testEdgeCase_NullBytes() throws Exception {
        byte[] nullBytes = {0x00, 0x00, 0x00};

        // Null bytes are technically valid UTF-8 (CharsetDecoder accepts them)
        assertFalse("Null bytes are valid UTF-8 on unknown topic",
            isBinaryData("unknown/topic", nullBytes));
    }

    @Test
    public void testEdgeCase_ControlCharacters() throws Exception {
        // Control characters (0-31) are valid UTF-8
        byte[] controlChars = {0x01, 0x02, 0x03, 0x1F};

        assertFalse("Control characters are valid UTF-8",
            isBinaryData("unknown/topic", controlChars));
    }

    @Test
    public void testEdgeCase_AllASCIIRange() throws Exception {
        // All bytes in ASCII range (0-127) are valid UTF-8
        byte[] asciiRange = new byte[128];
        for (int i = 0; i < 128; i++) {
            asciiRange[i] = (byte) i;
        }

        assertFalse("All ASCII range bytes are valid UTF-8",
            isBinaryData("unknown/topic", asciiRange));
    }

    @Test
    public void testEdgeCase_JSONWithUnicode() throws Exception {
        // JSON with Unicode characters
        String json = "{\"name\":\"José\",\"emoji\":\"😀\"}";
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        assertFalse("JSON with Unicode should be text on unknown topic",
            isBinaryData("unknown/topic", jsonBytes));

        // Even though it's valid UTF-8, status topic forces text
        assertFalse("JSON on status topic is text",
            isBinaryData("device/status", jsonBytes));
    }

    @Test
    public void testEdgeCase_MixedValidAndInvalid() throws Exception {
        // Starts with valid UTF-8, then has invalid bytes
        byte[] mixed = "Hello".getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[mixed.length + 2];
        System.arraycopy(mixed, 0, result, 0, mixed.length);
        result[mixed.length] = (byte) 0xFF;
        result[mixed.length + 1] = (byte) 0xFE;

        assertTrue("Mixed valid and invalid UTF-8 should be binary",
            isBinaryData("unknown/topic", result));
    }

    // ========================================================================
    // Real-World Scenarios from installer-app
    // ========================================================================

    @Test
    public void testRealWorld_NetworkConfigProtobuf() throws Exception {
        // Network config response with ASCII SSID
        byte[] networkConfig = {
            0x0a, 0x09, 'M', 'y', 'W', 'i', 'F', 'i', '-', '5', 'G'  // SSID
        };

        // NOTE: This topic contains "/config" which is classified as text by topic pattern.
        // This is a known collision - the topic pattern takes precedence.
        // In production, if network config uses protobuf, the topic should be:
        // "remote/network/proto/config" or similar to avoid collision.
        assertFalse("Config topics are classified as text (collision with text pattern)",
            isBinaryData("remote/network/config/get/accepted", networkConfig));

        // This would work correctly with proper topic naming:
        assertTrue("Proto topics correctly classified as binary",
            isBinaryData("remote/network/proto/config/get/accepted", networkConfig));
    }

    @Test
    public void testRealWorld_DeviceListWithASCIISerials() throws Exception {
        // Device list with ASCII serial numbers (the PR #4 bug case)
        byte[] deviceList = {
            0x0a, 0x07, 'P', 'E', 'N', '-', '0', '0', '1',  // Serial 1
            0x0a, 0x07, 'P', 'E', 'N', '-', '0', '0', '2'   // Serial 2
        };

        assertTrue("Device list with ASCII serials should be binary via topic",
            isBinaryData("device/proto/list", deviceList));
    }

    @Test
    public void testRealWorld_RMASwapResponse() throws Exception {
        // RMA swap response with device IDs
        byte[] rmaResponse = {
            0x08, 0x01,  // success = true
            0x12, 0x08, 'D', 'E', 'V', '-', 'N', 'E', 'W', '1'
        };

        assertTrue("RMA swap response should be binary",
            isBinaryData("device/rma/swap/response", rmaResponse));
    }

    @Test
    public void testRealWorld_StatusJSON() throws Exception {
        // System status JSON
        String statusJson = "{\"status\":\"online\",\"uptime\":12345}";
        byte[] statusBytes = statusJson.getBytes(StandardCharsets.UTF_8);

        assertFalse("Status JSON should be text",
            isBinaryData("device/status", statusBytes));
    }

    @Test
    public void testRealWorld_FirmwareUpdateBinary() throws Exception {
        // Firmware chunk (binary data)
        byte[] firmwareChunk = new byte[256];
        for (int i = 0; i < 256; i++) {
            firmwareChunk[i] = (byte) i;
        }

        assertTrue("Firmware binary should be detected on firmware topic",
            isBinaryData("device/firmware/update/chunk", firmwareChunk));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Convert hex string to byte array (simulates hex decoding)
     */
    private byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
}
