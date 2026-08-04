package com.reactnativemqttmtls;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.facebook.react.bridge.ReactApplicationContext;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.KeyException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the keystore path resolution fix (Issue #21).
 *
 * loadSoftwareKeyStore() throws a KeyException containing the resolved
 * absolute path before any Android-crypto call, so asserting on that
 * message is enough to verify path resolution without a real keystore
 * file on disk. Uses the same reflection + mocked ReactApplicationContext
 * seam as MqttBinaryDetectionTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MqttModulePathResolutionTest {

    private static final String FILES_DIR_PATH = "/data/user/0/com.app/files";

    @Mock
    private ReactApplicationContext mockContext;

    private MqttModule mqttModule;
    private Method loadSoftwareKeyStoreMethod;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(mockContext.getFilesDir()).thenReturn(new File(FILES_DIR_PATH));

        mqttModule = new MqttModule(mockContext);

        loadSoftwareKeyStoreMethod = MqttModule.class.getDeclaredMethod(
            "loadSoftwareKeyStore", String.class, String.class, String.class);
        loadSoftwareKeyStoreMethod.setAccessible(true);
    }

    /**
     * Invokes the private loadSoftwareKeyStore method and returns the
     * KeyException message thrown for a non-existent keystore file.
     */
    private String resolveKeystoreNotFoundMessage(String keystorePath) throws Exception {
        try {
            loadSoftwareKeyStoreMethod.invoke(mqttModule, keystorePath, null, null);
            fail("Expected KeyException for a non-existent keystore file");
            return null;
        } catch (InvocationTargetException e) {
            assertTrue("Expected KeyException, got " + e.getCause(),
                e.getCause() instanceof KeyException);
            return e.getCause().getMessage();
        }
    }

    @Test
    public void testAbsolutePath_UsedVerbatim_NoFilesDirDoubling() throws Exception {
        String absolutePath = "/data/user/0/com.app/files/software_keys.p12";

        String message = resolveKeystoreNotFoundMessage(absolutePath);

        assertTrue("Absolute path should be used as-is: " + message,
            message.contains(absolutePath));
        assertFalse("filesDir must not be prepended to an absolute path: " + message,
            message.contains(FILES_DIR_PATH + FILES_DIR_PATH));
    }

    @Test
    public void testRelativePath_ResolvedUnderFilesDir() throws Exception {
        String message = resolveKeystoreNotFoundMessage("software_keys.p12");

        assertTrue("Relative path should resolve under filesDir: " + message,
            message.contains(FILES_DIR_PATH + File.separator + "software_keys.p12"));
    }

    @Test
    public void testNullPath_DefaultsToSoftwareKeystoreFileUnderFilesDir() throws Exception {
        String message = resolveKeystoreNotFoundMessage(null);

        assertTrue("Null path should default under filesDir: " + message,
            message.contains(FILES_DIR_PATH + File.separator + "software_keys.p12"));
    }

    @Test
    public void testEmptyPath_DefaultsToSoftwareKeystoreFileUnderFilesDir() throws Exception {
        String message = resolveKeystoreNotFoundMessage("");

        assertTrue("Empty path should default under filesDir: " + message,
            message.contains(FILES_DIR_PATH + File.separator + "software_keys.p12"));
    }
}
