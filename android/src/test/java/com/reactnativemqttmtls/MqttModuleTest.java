package com.reactnativemqttmtls;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MqttModule
 * Tests callback guards and binary detection logic
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MqttModuleTest {

    @Mock
    private ReactApplicationContext mockContext;

    @Mock
    private Callback mockCallback;

    private MqttModule mqttModule;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        mqttModule = new MqttModule(mockContext);
    }

    /**
     * Binary Detection Tests
     */

    @Test
    public void testBinaryDetection_ValidUTF8Text() {
        String text = "Hello, MQTT!";
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);

        boolean isBinary = isBinaryData(payload);

        assertFalse("Plain text should not be detected as binary", isBinary);
    }

    @Test
    public void testBinaryDetection_ValidUTF8WithEmoji() {
        String text = "Hello 👋 World 🌍";
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);

        boolean isBinary = isBinaryData(payload);

        assertFalse("UTF-8 text with emoji should not be detected as binary", isBinary);
    }

    @Test
    public void testBinaryDetection_JSONPayload() {
        String json = "{\"timestamp\":1782414353,\"status\":\"active\"}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);

        boolean isBinary = isBinaryData(payload);

        assertFalse("JSON should not be detected as binary", isBinary);
    }

    @Test
    public void testBinaryDetection_ProtobufVarint() {
        // Protobuf message with varint encoding: field 7, value 2000
        // tag = (7 << 3 | 0) = 0x38, varint(2000) = [0xD0, 0x0F]
        byte[] protobufPayload = {0x38, (byte) 0xD0, 0x0F};

        boolean isBinary = isBinaryData(protobufPayload);

        assertTrue("Protobuf with varint should be detected as binary", isBinary);
    }

    @Test
    public void testBinaryDetection_BinaryData() {
        byte[] binaryData = {0x00, 0x01, 0x02, (byte) 0xFF, 0x7F, (byte) 0x80};

        boolean isBinary = isBinaryData(binaryData);

        assertTrue("Binary data should be detected as binary", isBinary);
    }

    @Test
    public void testBinaryDetection_EmptyPayload() {
        byte[] emptyPayload = new byte[0];

        boolean isBinary = isBinaryData(emptyPayload);

        assertFalse("Empty payload should not be detected as binary", isBinary);
    }

    @Test
    public void testBinaryDetection_NullBytes() {
        byte[] nullBytes = {0x00, 0x00, 0x00};

        boolean isBinary = isBinaryData(nullBytes);

        // Null bytes are technically valid UTF-8, so CharsetDecoder accepts them
        // In practice, MQTT messages with null bytes are likely binary data
        assertFalse("Null bytes are valid UTF-8 (CharsetDecoder behavior)", isBinary);
    }

    @Test
    public void testBinaryDetection_InvalidUTF8Sequence() {
        // Invalid UTF-8: start byte without continuation
        byte[] invalidUTF8 = {(byte) 0xC0, 0x20}; // Invalid 2-byte sequence

        boolean isBinary = isBinaryData(invalidUTF8);

        assertTrue("Invalid UTF-8 should be detected as binary", isBinary);
    }

    @Test
    public void testBinaryDetection_MixedASCIIAndBinary() {
        // Starts with valid ASCII, then has invalid UTF-8
        byte[] mixed = {'H', 'e', 'l', 'l', 'o', (byte) 0xFF, (byte) 0xFE};

        boolean isBinary = isBinaryData(mixed);

        assertTrue("Mixed ASCII and binary should be detected as binary", isBinary);
    }

    @Test
    public void testBinaryDetection_AllASCIIRange() {
        // All bytes in valid ASCII range (0-127)
        byte[] asciiData = new byte[128];
        for (int i = 0; i < 128; i++) {
            asciiData[i] = (byte) i;
        }

        boolean isBinary = isBinaryData(asciiData);

        // Control characters (0-31) are valid UTF-8, so CharsetDecoder accepts them
        // This documents the actual CharsetDecoder behavior
        assertFalse("Control characters are valid UTF-8 (CharsetDecoder behavior)", isBinary);
    }

    /**
     * Callback Guard Tests
     */

    @Test
    public void testCallbackGuard_SingleInvocation() {
        final AtomicInteger invokeCount = new AtomicInteger(0);
        final AtomicBoolean fired = new AtomicBoolean(false);

        Callback testCallback = new Callback() {
            @Override
            public void invoke(Object... args) {
                invokeCount.incrementAndGet();
            }
        };

        safeInvoke(testCallback, fired, "result");

        assertEquals("Callback should be invoked once", 1, invokeCount.get());
        assertTrue("Fired flag should be set", fired.get());
    }

    @Test
    public void testCallbackGuard_PreventsDuplicateInvocation() {
        final AtomicInteger invokeCount = new AtomicInteger(0);
        final AtomicBoolean fired = new AtomicBoolean(false);

        Callback testCallback = new Callback() {
            @Override
            public void invoke(Object... args) {
                invokeCount.incrementAndGet();
            }
        };

        // Try to invoke multiple times
        safeInvoke(testCallback, fired, "result1");
        safeInvoke(testCallback, fired, "result2");
        safeInvoke(testCallback, fired, "result3");

        assertEquals("Callback should only be invoked once", 1, invokeCount.get());
        assertTrue("Fired flag should be set", fired.get());
    }

    @Test
    public void testCallbackGuard_NullCallbackHandling() {
        final AtomicBoolean fired = new AtomicBoolean(false);

        // Should not throw exception with null callback
        safeInvoke(null, fired, "result");

        assertFalse("Fired flag should not be set for null callback", fired.get());
    }

    @Test
    public void testCallbackGuard_ThreadSafety() throws InterruptedException {
        final AtomicInteger invokeCount = new AtomicInteger(0);
        final AtomicBoolean fired = new AtomicBoolean(false);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(10);

        Callback testCallback = new Callback() {
            @Override
            public void invoke(Object... args) {
                invokeCount.incrementAndGet();
            }
        };

        // Create 10 threads that all try to invoke the callback
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    safeInvoke(testCallback, fired, "result");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Release all threads at once
        startLatch.countDown();

        // Wait for all threads to complete
        assertTrue("All threads should complete", doneLatch.await(5, TimeUnit.SECONDS));

        assertEquals("Callback should only be invoked once despite race condition",
                     1, invokeCount.get());
        assertTrue("Fired flag should be set", fired.get());
    }

    @Test
    public void testCallbackGuard_ExceptionHandling() {
        final AtomicBoolean fired = new AtomicBoolean(false);

        Callback throwingCallback = new Callback() {
            @Override
            public void invoke(Object... args) {
                throw new RuntimeException("Test exception");
            }
        };

        // Should not throw exception, should be caught internally
        safeInvoke(throwingCallback, fired, "result");

        assertTrue("Fired flag should be set even when callback throws", fired.get());
    }

    @Test
    public void testCallbackGuard_WithArguments() {
        final AtomicInteger invokeCount = new AtomicInteger(0);
        final AtomicBoolean fired = new AtomicBoolean(false);
        final Object[] receivedArgs = new Object[1];

        Callback testCallback = new Callback() {
            @Override
            public void invoke(Object... args) {
                invokeCount.incrementAndGet();
                if (args.length > 0) {
                    receivedArgs[0] = args[0];
                }
            }
        };

        safeInvoke(testCallback, fired, "test-result", 42, true);

        assertEquals("Callback should be invoked once", 1, invokeCount.get());
        assertEquals("First argument should be passed", "test-result", receivedArgs[0]);
    }

    /**
     * B64 Marker Tests
     */

    @Test
    public void testBinaryMarker_Detection() {
        String markedMessage = "B64:SGVsbG8=";

        assertTrue("Should detect B64: prefix", markedMessage.startsWith("B64:"));

        String base64Data = markedMessage.substring(4);
        assertEquals("Should extract base64 data", "SGVsbG8=", base64Data);
    }

    @Test
    public void testBinaryMarker_PlainText() {
        String plainText = "Hello, World!";

        assertFalse("Plain text should not have B64: prefix", plainText.startsWith("B64:"));
    }

    @Test
    public void testBinaryMarker_EmptyData() {
        String markedEmpty = "B64:";

        assertTrue("Should detect B64: prefix", markedEmpty.startsWith("B64:"));

        String base64Data = markedEmpty.substring(4);
        assertEquals("Should have empty base64 data", "", base64Data);
    }

    /**
     * Base64 Encoding/Decoding Tests
     */

    @Test
    public void testBase64_RoundTrip() {
        byte[] original = "Hello, MQTT!".getBytes(StandardCharsets.UTF_8);

        // Encode to Base64
        String base64 = android.util.Base64.encodeToString(original, android.util.Base64.NO_WRAP);

        // Decode back
        byte[] decoded = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);

        assertArrayEquals("Round-trip should preserve data", original, decoded);
    }

    @Test
    public void testBase64_BinaryData() {
        byte[] binaryData = {0x00, 0x01, 0x02, (byte) 0xFF, 0x7F, (byte) 0x80};

        String base64 = android.util.Base64.encodeToString(binaryData, android.util.Base64.NO_WRAP);
        byte[] decoded = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);

        assertArrayEquals("Binary data should survive Base64 round-trip", binaryData, decoded);
    }

    /**
     * Helper methods (duplicated from MqttModule for testing)
     */

    private boolean isBinaryData(byte[] payload) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(ByteBuffer.wrap(payload));
            return false;
        } catch (CharacterCodingException e) {
            return true;
        }
    }

    private void safeInvoke(Callback callback, AtomicBoolean fired, Object... args) {
        if (callback == null) {
            return;
        }
        if (fired.compareAndSet(false, true)) {
            try {
                callback.invoke(args);
            } catch (Exception e) {
                // Log error (in real implementation)
            }
        }
    }
}
