package com.reactnativemqttmtls;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;

import info.mqtt.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
     * Teardown Tests
     *
     * MqttService caches one MqttConnection per "serverURI:clientId:packageName" handle and only
     * drops it on disconnect(). close() leaves the entry behind pointing at a closed client, so with
     * a reused clientId every later connect fails instantly with "Client is closed" (32111) for the
     * life of the process. Teardown must therefore always disconnect and never close.
     */

    @Test
    public void testTeardown_AlwaysDisconnectsAndNeverCloses() throws Exception {
        // Deliberately no isConnected() stub: teardown must not consult it. A not-connected client
        // is exactly the ungraceful-drop case that used to take the close-only path.
        MqttAndroidClient mockClient = mock(MqttAndroidClient.class);
        setClient(mockClient);

        cleanupConnection();

        verify(mockClient).disconnect(0L);
        verify(mockClient, never()).close();
        verify(mockClient).unregisterResources();
        verify(mockClient, never()).isConnected();
        assertNull("Client reference should be cleared", getClient());
    }

    @Test
    public void testTeardown_ReleasesResourcesWhenDisconnectThrows() throws Exception {
        MqttAndroidClient mockClient = mock(MqttAndroidClient.class);
        when(mockClient.disconnect(anyLong())).thenThrow(new IllegalStateException("service not bound"));
        setClient(mockClient);

        cleanupConnection();

        verify(mockClient).unregisterResources();
        assertNull("Client reference should be cleared even when disconnect fails", getClient());
    }

    @Test
    public void testTeardown_NoClientIsNotAnError() throws Exception {
        setClient(null);

        cleanupConnection();

        assertNull(getClient());
    }

    @Test
    public void testTeardown_KeepsCurrentClientWhenALaterOneReplacedIt() throws Exception {
        MqttAndroidClient oldClient = mock(MqttAndroidClient.class);
        MqttAndroidClient currentClient = mock(MqttAndroidClient.class);
        setClient(currentClient);

        // A late disconnect callback from the previous client must not tear down its replacement
        releaseClientResources(oldClient);

        verify(oldClient).unregisterResources();
        assertSame("Current client should survive the old client's callback",
                   currentClient, getClient());
    }

    @Test
    public void testInvalidate_TearsDownClient() throws Exception {
        MqttAndroidClient mockClient = mock(MqttAndroidClient.class);
        setClient(mockClient);

        // React Native destroying the module (a JS reload, for instance) is the one teardown the app
        // cannot request itself, and skipping it leaks the receiver, the binding and the handle.
        mqttModule.invalidate();

        verify(mockClient).disconnect(0L);
        verify(mockClient).unregisterResources();
        verify(mockClient, never()).close();
        assertNull("Client reference should be cleared", getClient());
    }

    @Test
    public void testInvalidate_IsIdempotent() throws Exception {
        MqttAndroidClient mockClient = mock(MqttAndroidClient.class);
        setClient(mockClient);

        // Teardown must be idempotent whatever calls it. It has to be: on the 0.71 this module
        // compiles against, BaseJavaModule.invalidate() still delegates to
        // onCatalystInstanceDestroy(), so both hooks fire for one module destruction. (On 0.83
        // invalidate() is empty and only the first fires — the assertion holds either way.)
        mqttModule.invalidate();
        mqttModule.onCatalystInstanceDestroy();

        verify(mockClient, times(1)).disconnect(0L);
        verify(mockClient, times(1)).unregisterResources();
    }

    @Test
    public void testTeardown_StaleClientDoesNotIssueDisconnectOnceReplaced() throws Exception {
        MqttAndroidClient failedClient = mock(MqttAndroidClient.class);
        MqttAndroidClient replacementClient = mock(MqttAndroidClient.class);
        setClient(replacementClient);

        // What a connect onFailure does: it names the client its own attempt built, which by then may
        // not be the current one. disconnect() carries nothing but the handle string, and the service
        // resolves that against whichever MqttConnection is cached under it — the replacement's, since
        // a reused clientId and broker URL produce the same string. So a stale client must not issue
        // it. unregisterResources() is per-instance and still has to run, or this attempt leaks its
        // receiver and its binding.
        cleanupConnection(failedClient);

        verify(failedClient, never()).disconnect(anyLong());
        verify(failedClient).unregisterResources();
        verifyNoInteractions(replacementClient);
        assertSame("Replacement client should survive the failed attempt's teardown",
                   replacementClient, getClient());
    }

    /**
     * disconnect() Tests
     *
     * This is the entry point the app actually calls — MQTTManagerMtls disconnects before every
     * connect — so an ungraceful drop reaches the fix through the not-connected branch here rather
     * than through the connect onFailure eviction.
     */

    @Test
    public void testDisconnect_NotConnectedClientIsStillDisconnected() throws Exception {
        MqttAndroidClient mockClient = mock(MqttAndroidClient.class);
        when(mockClient.isConnected()).thenReturn(false);
        setClient(mockClient);

        Callback success = mock(Callback.class);
        Callback error = mock(Callback.class);

        mqttModule.disconnect(success, error);

        verify(mockClient).disconnect(0L);
        verify(mockClient, never()).close();
        verify(mockClient).unregisterResources();
        assertNull("Client reference should be cleared", getClient());
        verify(success).invoke("Disconnected successfully");
        verify(error, never()).invoke(any());
    }

    @Test
    public void testDisconnect_ConnectedClientReleasesResourcesOnSuccess() throws Exception {
        MqttAndroidClient mockClient = mock(MqttAndroidClient.class);
        when(mockClient.isConnected()).thenReturn(true);
        setClient(mockClient);

        Callback success = mock(Callback.class);
        Callback error = mock(Callback.class);

        mqttModule.disconnect(success, error);

        ArgumentCaptor<IMqttActionListener> listener = ArgumentCaptor.forClass(IMqttActionListener.class);
        verify(mockClient).disconnect(any(), listener.capture());
        verify(mockClient, never()).close();
        verifyNoInteractions(success, error);

        listener.getValue().onSuccess(null);

        verify(mockClient).unregisterResources();
        assertNull("Client reference should be cleared", getClient());
        verify(success).invoke("Disconnected successfully");
        verify(error, never()).invoke(any());
    }

    @Test
    public void testDisconnect_ReleasesResourcesWhenBrokerDisconnectFails() throws Exception {
        MqttAndroidClient mockClient = mock(MqttAndroidClient.class);
        when(mockClient.isConnected()).thenReturn(true);
        setClient(mockClient);

        Callback success = mock(Callback.class);
        Callback error = mock(Callback.class);

        mqttModule.disconnect(success, error);

        ArgumentCaptor<IMqttActionListener> listener = ArgumentCaptor.forClass(IMqttActionListener.class);
        verify(mockClient).disconnect(any(), listener.capture());

        // The handle is evicted whether or not the broker acknowledged, so the client is finished
        // with either way and its receiver and binding have to be released.
        listener.getValue().onFailure(null, new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT));

        verify(mockClient).unregisterResources();
        assertNull("Client reference should be cleared", getClient());
        verify(error).invoke(contains("Disconnect failed"));
        verify(success, never()).invoke(any());
    }

    @Test
    public void testDisconnect_ReleasesResourcesWhenDisconnectThrows() throws Exception {
        MqttAndroidClient mockClient = mock(MqttAndroidClient.class);
        when(mockClient.isConnected()).thenReturn(true);
        when(mockClient.disconnect(any(), any(IMqttActionListener.class)))
                .thenThrow(new IllegalStateException("service not bound"));
        setClient(mockClient);

        Callback success = mock(Callback.class);
        Callback error = mock(Callback.class);

        mqttModule.disconnect(success, error);

        verify(mockClient).unregisterResources();
        verify(mockClient, never()).close();
        assertNull("Client reference should be cleared", getClient());
        verify(error).invoke(contains("Disconnect failed"));
        verify(success, never()).invoke(any());
    }

    @Test
    public void testDisconnect_NoClientIsNotAnError() throws Exception {
        setClient(null);

        Callback success = mock(Callback.class);
        Callback error = mock(Callback.class);

        mqttModule.disconnect(success, error);

        verify(success).invoke("No active connection");
        verify(error, never()).invoke(any());
    }

    /**
     * Unusable Client Detection Tests
     */

    @Test
    public void testUnusableClientFailure_ClosedClient() throws Exception {
        assertTrue("A closed client can never be reconnected",
                   isUnusableClientFailure(new MqttException(MqttException.REASON_CODE_CLIENT_CLOSED)));
    }

    @Test
    public void testUnusableClientFailure_AlreadyConnectedClient() throws Exception {
        assertTrue("An already-connected cached client cannot be reconnected",
                   isUnusableClientFailure(new MqttException(MqttException.REASON_CODE_CLIENT_CONNECTED)));
    }

    @Test
    public void testUnusableClientFailure_DisconnectingClient() throws Exception {
        // Transient in plain Paho, permanent here: MqttService.disconnect() drops the map entry as
        // soon as it is called, so a handle reporting DISCONNECTING is already orphaned.
        assertTrue("A disconnecting client's handle is already orphaned",
                   isUnusableClientFailure(new MqttException(MqttException.REASON_CODE_CLIENT_DISCONNECTING)));
    }

    @Test
    public void testUnusableClientFailure_ConnectInProgress() throws Exception {
        assertFalse("A connect already in progress resolves on its own and must not be evicted",
                    isUnusableClientFailure(new MqttException(MqttException.REASON_CODE_CONNECT_IN_PROGRESS)));
    }

    @Test
    public void testUnusableClientFailure_UnreachableBroker() throws Exception {
        assertFalse("An unreachable broker is worth retrying against the same client",
                    isUnusableClientFailure(new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR)));
    }

    @Test
    public void testUnusableClientFailure_NonMqttException() throws Exception {
        assertFalse(isUnusableClientFailure(new RuntimeException("boom")));
        assertFalse(isUnusableClientFailure(null));
    }

    /**
     * Reflection helpers — the teardown logic under test is private module state
     */

    private void setClient(MqttAndroidClient value) throws Exception {
        Field field = MqttModule.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(mqttModule, value);
    }

    private Object getClient() throws Exception {
        Field field = MqttModule.class.getDeclaredField("client");
        field.setAccessible(true);
        return field.get(mqttModule);
    }

    private void cleanupConnection() throws Exception {
        Method method = MqttModule.class.getDeclaredMethod("cleanupConnection");
        method.setAccessible(true);
        method.invoke(mqttModule);
    }

    private void cleanupConnection(MqttAndroidClient target) throws Exception {
        Method method = MqttModule.class.getDeclaredMethod("cleanupConnection", MqttAndroidClient.class);
        method.setAccessible(true);
        method.invoke(mqttModule, target);
    }

    private void releaseClientResources(MqttAndroidClient disconnectedClient) throws Exception {
        Method method = MqttModule.class.getDeclaredMethod("releaseClientResources", MqttAndroidClient.class);
        method.setAccessible(true);
        method.invoke(mqttModule, disconnectedClient);
    }

    private boolean isUnusableClientFailure(Throwable exception) throws Exception {
        Method method = MqttModule.class.getDeclaredMethod("isUnusableClientFailure", Throwable.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, exception);
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
