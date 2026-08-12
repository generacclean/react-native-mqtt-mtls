package com.reactnativemqttmtls;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
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
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for keystore path resolution (Issue #21) and for the
 * no-backup storage move in react-native-ecc-csr 1.4.0+.
 *
 * loadSoftwareKeyStore() throws a KeyException containing the resolved
 * absolute path before any Android-crypto call, so asserting on that
 * message is enough to verify path resolution without a real keystore
 * file on disk. Uses the same reflection + mocked ReactApplicationContext
 * seam as MqttBinaryDetectionTest, with real temp directories standing in
 * for filesDir and noBackupFilesDir so existence checks are meaningful.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MqttModulePathResolutionTest {

    private static final String KEYSTORE_NAME = "software_keys.p12";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Mock
    private ReactApplicationContext mockContext;

    private File filesDir;
    private File noBackupDir;
    private MqttModule mqttModule;
    private Method loadSoftwareKeyStoreMethod;
    private Method resolveKeystoreFileMethod;
    private Method keystoreRootsMethod;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        filesDir = tempFolder.newFolder("files");
        noBackupDir = tempFolder.newFolder("no_backup");
        when(mockContext.getFilesDir()).thenReturn(filesDir);
        when(mockContext.getNoBackupFilesDir()).thenReturn(noBackupDir);

        mqttModule = new MqttModule(mockContext);

        loadSoftwareKeyStoreMethod = MqttModule.class.getDeclaredMethod(
            "loadSoftwareKeyStore", String.class, String.class, String.class);
        loadSoftwareKeyStoreMethod.setAccessible(true);

        resolveKeystoreFileMethod = MqttModule.class.getDeclaredMethod(
            "resolveKeystoreFile", String.class, List.class);
        resolveKeystoreFileMethod.setAccessible(true);

        keystoreRootsMethod = MqttModule.class.getDeclaredMethod("keystoreRoots");
        keystoreRootsMethod.setAccessible(true);
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

    @SuppressWarnings("unchecked")
    private File resolve(String keystorePath) throws Exception {
        List<File> roots = (List<File>) keystoreRootsMethod.invoke(mqttModule);
        return (File) resolveKeystoreFileMethod.invoke(mqttModule, keystorePath, roots);
    }

    @Test
    public void testAbsolutePath_UsedVerbatim_NoFilesDirDoubling() throws Exception {
        String absolutePath = new File(filesDir, KEYSTORE_NAME).getAbsolutePath();

        String message = resolveKeystoreNotFoundMessage(absolutePath);

        assertTrue("Absolute path should be used as-is: " + message,
            message.contains(absolutePath));
        assertFalse("filesDir must not be prepended to an absolute path: " + message,
            message.contains(filesDir.getAbsolutePath() + filesDir.getAbsolutePath()));
    }

    @Test
    public void testRelativePath_ResolvedUnderNoBackupDir() throws Exception {
        String message = resolveKeystoreNotFoundMessage(KEYSTORE_NAME);

        assertTrue("Relative path should resolve under no_backup: " + message,
            message.contains(new File(noBackupDir, KEYSTORE_NAME).getAbsolutePath()));
    }

    @Test
    public void testNullPath_DefaultsToSoftwareKeystoreFileUnderNoBackupDir() throws Exception {
        String message = resolveKeystoreNotFoundMessage(null);

        assertTrue("Null path should default under no_backup: " + message,
            message.contains(new File(noBackupDir, KEYSTORE_NAME).getAbsolutePath()));
    }

    @Test
    public void testEmptyPath_DefaultsToSoftwareKeystoreFileUnderNoBackupDir() throws Exception {
        String message = resolveKeystoreNotFoundMessage("");

        assertTrue("Empty path should default under no_backup: " + message,
            message.contains(new File(noBackupDir, KEYSTORE_NAME).getAbsolutePath()));
    }

    @Test
    public void testRelativePath_ResolvesToLegacyFilesDir_WhenNotYetMigrated() throws Exception {
        File legacy = new File(filesDir, KEYSTORE_NAME);
        assertTrue("Precondition: legacy keystore file created", legacy.createNewFile());

        assertEquals("A device that has not run the ecc-csr migration must still resolve to files/",
            legacy.getAbsolutePath(), resolve(KEYSTORE_NAME).getAbsolutePath());
    }

    @Test
    public void testRelativePath_PrefersNoBackupDir_WhenBothExist() throws Exception {
        assertTrue(new File(filesDir, KEYSTORE_NAME).createNewFile());
        File migrated = new File(noBackupDir, KEYSTORE_NAME);
        assertTrue(migrated.createNewFile());

        assertEquals("no_backup must win over a stale copy in files/",
            migrated.getAbsolutePath(), resolve(KEYSTORE_NAME).getAbsolutePath());
    }

    @Test
    public void testStaleAbsoluteFilesDirPath_FallsBackToMigratedNoBackupFile() throws Exception {
        // The installer persists keystorePath across launches, so right after upgrading ecc-csr it
        // hands us a files/ path for a keystore that has already been moved to no_backup/.
        File migrated = new File(noBackupDir, KEYSTORE_NAME);
        assertTrue(migrated.createNewFile());
        String stalePath = new File(filesDir, KEYSTORE_NAME).getAbsolutePath();

        assertEquals("A stale files/ path must fall back to the migrated no_backup/ keystore",
            migrated.getAbsolutePath(), resolve(stalePath).getAbsolutePath());
    }

    @Test
    public void testNoBackupDirUnavailable_ResolvesUnderFilesDir() throws Exception {
        // keystoreRoots() skips a null getNoBackupFilesDir(). Context#getNoBackupFilesDir is
        // declared @Nullable, so on a device where it returns null the resolution and the
        // containment check both have to fall back to filesDir instead of throwing.
        when(mockContext.getNoBackupFilesDir()).thenReturn(null);
        File legacy = new File(filesDir, KEYSTORE_NAME);
        assertTrue("Precondition: keystore file created under files/", legacy.createNewFile());

        assertEquals("A null no-backup dir must leave filesDir as the resolution root",
            legacy.getAbsolutePath(), resolve(KEYSTORE_NAME).getAbsolutePath());
    }

    @Test
    public void testAbsolutePath_OutsideFilesDir_Rejected() throws Exception {
        String outsidePath = new File(tempFolder.getRoot(), "evil.p12").getAbsolutePath();

        try {
            loadSoftwareKeyStoreMethod.invoke(mqttModule, outsidePath, null, null);
            fail("Expected KeyException for a keystore path outside app-private storage");
        } catch (InvocationTargetException e) {
            assertTrue("Expected KeyException, got " + e.getCause(),
                e.getCause() instanceof KeyException);
            assertTrue("Message should mention app-private storage: " + e.getCause().getMessage(),
                e.getCause().getMessage().contains("app-private storage"));
        }
    }

    @Test
    public void testTraversalOutOfNoBackupDir_Rejected() throws Exception {
        try {
            loadSoftwareKeyStoreMethod.invoke(mqttModule, "../evil.p12", null, null);
            fail("Expected KeyException for a path escaping app-private storage via ..");
        } catch (InvocationTargetException e) {
            assertTrue("Expected KeyException, got " + e.getCause(),
                e.getCause() instanceof KeyException);
            assertTrue("Message should mention app-private storage: " + e.getCause().getMessage(),
                e.getCause().getMessage().contains("app-private storage"));
        }
    }
}
