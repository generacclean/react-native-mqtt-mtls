package com.reactnativemqttmtls;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * Regression tests for CustomTrustManager.
 *
 * Verifies:
 * - Chain validation goes through CertPathValidator (PKIX), enforcing expiry, path-length,
 *   basic-constraints, and signature checks.
 * - The leaf is a TLS server certificate: a clientAuth-only certificate from the same CA is
 *   rejected even in admin mode, where neither identity pin runs.
 * - Hostname/SAN matching against the expected SNI host is enforced when configured.
 * - isAdminUser semantics: chain validation always runs; CN and hostname/SAN pinning
 *   are skipped only when expectedBrokerCN / expectedSniHost are null (admin mode).
 *
 * Fixtures are a real EC P-384 chain generated with openssl: Penguin TEST Root ->
 * Intermediate -> broker leaf (800-day validity, SANs localhost/127.0.0.1/10.0.2.2),
 * plus an expired leaf and a leaf issued for a different host's SANs. A second CA with a
 * clientAuth-only leaf and a leaf with no extendedKeyUsage extension covers the EKU check.
 *
 * Which of these tests describes production: only the admin-mode ones. The installer app passes
 * isAdminUser: true on every connection and never supplies brokerCommonName or sniHostname, so
 * expectedBrokerCN and expectedSniHost are null in the field and neither pin runs. The CN and SAN
 * tests below cover a configuration nothing ships today; testAdminUser_ForeignDeviceCert_Accepted_HostPinSkipped
 * covers every real connection. Chain validation is what production actually gains here — do not
 * read the passing host-pin tests as protection Field Pro has.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CustomTrustManagerTest {

    private static final String ROOT_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIByjCCAVCgAwIBAgIUCvp5e+1jhwMlPy80fkNyNpa3l7cwCgYIKoZIzj0EAwMw\n"
            + "HDEaMBgGA1UEAwwRUGVuZ3VpbiBURVNUIFJvb3QwHhcNMjYwNzMwMjIzMTQ4WhcN\n"
            + "MzYwNzI3MjIzMTQ4WjAcMRowGAYDVQQDDBFQZW5ndWluIFRFU1QgUm9vdDB2MBAG\n"
            + "ByqGSM49AgEGBSuBBAAiA2IABHo1LW1mTU5Q/WFbK1/kyw1QtZiDDrDjeATSn8Ez\n"
            + "YdyPZpKuqJJ18j1aVv8Inw7ehln/vAfKVjSEDAt4BvKPcP1YJaGa4Ebvhkri8sbf\n"
            + "GAoHfbaJ84gApvQiiQDElMt6b6NTMFEwHQYDVR0OBBYEFLKVNcfhYJYhGUVNIu0I\n"
            + "mHWuSV+6MB8GA1UdIwQYMBaAFLKVNcfhYJYhGUVNIu0ImHWuSV+6MA8GA1UdEwEB\n"
            + "/wQFMAMBAf8wCgYIKoZIzj0EAwMDaAAwZQIxAOU9KBgQ7WI4dOAl8guD3oON5noS\n"
            + "TMNVWKq2RAdDnUO+kKxnYmDDvAumwOexnyvSggIwMDRXDd9CsgvteLQuK9oh72Rr\n"
            + "5/ocUnKHv+otiypiZmWYaTzZV+cPlsFZNDLqKNHK\n"
            + "-----END CERTIFICATE-----\n";

    private static final String INTERMEDIATE_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIB5TCCAWugAwIBAgIUZHS/kwQnv0uJgKEj623bkWTauBQwCgYIKoZIzj0EAwMw\n"
            + "HDEaMBgGA1UEAwwRUGVuZ3VpbiBURVNUIFJvb3QwHhcNMjYwNzMwMjIzMTQ4WhcN\n"
            + "MzYwNzI3MjIzMTQ4WjAkMSIwIAYDVQQDDBlQZW5ndWluIFRFU1QgSW50ZXJtZWRp\n"
            + "YXRlMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAErDTCWwXFVF2L6m1rgO95AVDKJOkD\n"
            + "k0OxL1peFKBY/hb5+mLvueaIaNuFVegrC5jMrdo3hWMZCJjQwg2QoZbpNrwiKCpP\n"
            + "75eiM2ejmpGQdODyc2ORUQLfYURqIa1z4a5Jo2YwZDASBgNVHRMBAf8ECDAGAQH/\n"
            + "AgEAMA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQUHM/eu4x/m+Jauf8Qvfu+pxL0\n"
            + "NMwwHwYDVR0jBBgwFoAUspU1x+FgliEZRU0i7QiYda5JX7owCgYIKoZIzj0EAwMD\n"
            + "aAAwZQIwdLRZBEFfpFVFSOLW68+H47Br/XegfJr/Na7ClyzVGK94wqwbJTuEuGXT\n"
            + "w0sXxV0nAjEAnmXLXNu/PP/bmli3B0pYRsODWc3YXiG1hSKxakxz6d/E5NGPRA76\n"
            + "qP1gVQ7E3A3+\n"
            + "-----END CERTIFICATE-----\n";

    // Broker leaf: 800-day validity, SANs localhost/127.0.0.1/10.0.2.2, CN penguin-broker.local
    private static final String BROKER_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIICGDCCAZ6gAwIBAgIURuVk+C+dQXYyxWfnCjFCddBH3XYwCgYIKoZIzj0EAwMw\n"
            + "JDEiMCAGA1UEAwwZUGVuZ3VpbiBURVNUIEludGVybWVkaWF0ZTAeFw0yNjA3MzAy\n"
            + "MjMxNDhaFw0yODEwMDcyMjMxNDhaMB8xHTAbBgNVBAMMFHBlbmd1aW4tYnJva2Vy\n"
            + "LmxvY2FsMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEUeyJRIkjiiCUUy+Yig4mG03G\n"
            + "erDoQVluBihhL8EsUxMY+HGhmsMnZBHiEz2wT7fgeMY3K4R7eby8dVMqOgK4pWjl\n"
            + "2bhKMi99x/LYy2vxI70CA5CuYgHCL/AsLekWS1Mho4GVMIGSMAwGA1UdEwEB/wQC\n"
            + "MAAwCwYDVR0PBAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUFBwMBMCAGA1UdEQQZMBeC\n"
            + "CWxvY2FsaG9zdIcEfwAAAYcECgACAjAdBgNVHQ4EFgQU171C2J19At/K3IJaIxcX\n"
            + "jIkpVLswHwYDVR0jBBgwFoAUHM/eu4x/m+Jauf8Qvfu+pxL0NMwwCgYIKoZIzj0E\n"
            + "AwMDaAAwZQIwb4YI1+uMZ3KYFiPcLlkytkoQCjDakPxhAZ+geUda0fyy1oxVduLM\n"
            + "A8Q3uJsKCzYmAjEAhNlKanvD/rilK8y7FrvlpAwMEBMLnMNsQWPILPTlTAXNJwAS\n"
            + "s/CrY+bZ9m1fzFns\n"
            + "-----END CERTIFICATE-----\n";

    // Expired leaf (valid Jan 1 2024 - Jan 31 2024), same SANs as BROKER_PEM
    private static final String EXPIRED_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIICGDCCAZ6gAwIBAgIURuVk+C+dQXYyxWfnCjFCddBH3XcwCgYIKoZIzj0EAwMw\n"
            + "JDEiMCAGA1UEAwwZUGVuZ3VpbiBURVNUIEludGVybWVkaWF0ZTAeFw0yNDAxMDEw\n"
            + "NjAwMDFaFw0yNDAxMzEwNjAwMDFaMB8xHTAbBgNVBAMMFHBlbmd1aW4tYnJva2Vy\n"
            + "LmxvY2FsMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAETJqM6jY/xEiv9Kt/HeRgEIRl\n"
            + "hi+Cdk3qbvEMnlN/8OqD7gsWwcWUU6zpcOzG6hP0qEHCL/OoCr0ATGSiMQrMlhKX\n"
            + "CEscjl5FN/itH1YofDtDjCqvHFnW0dxiQ1N+HmaTo4GVMIGSMAwGA1UdEwEB/wQC\n"
            + "MAAwCwYDVR0PBAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUFBwMBMCAGA1UdEQQZMBeC\n"
            + "CWxvY2FsaG9zdIcEfwAAAYcECgACAjAdBgNVHQ4EFgQUTDAuTpUBUA/GNArWRnQQ\n"
            + "XgG9gWYwHwYDVR0jBBgwFoAUHM/eu4x/m+Jauf8Qvfu+pxL0NMwwCgYIKoZIzj0E\n"
            + "AwMDaAAwZQIxAOyxkKwmy9P8TcHIoZC4Ho5+Rh3RQZBcA/Pk8/S+ylDr9n5thcDg\n"
            + "t6P+Y1YJoDSAxQIwFXUDfm69ntfjojFKv935MWq+tPgItXXy88GwqM9DWPvREwOG\n"
            + "75fd4Fh6a/CXnxRQ\n"
            + "-----END CERTIFICATE-----\n";

    // Leaf issued for a different device: CN=other-device.local, SANs other-device.local/192.168.1.5
    private static final String OTHER_HOST_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIICGTCCAZ+gAwIBAgIURuVk+C+dQXYyxWfnCjFCddBH3XgwCgYIKoZIzj0EAwMw\n"
            + "JDEiMCAGA1UEAwwZUGVuZ3VpbiBURVNUIEludGVybWVkaWF0ZTAeFw0yNjA3MzAy\n"
            + "MjUwMDJaFw0yODEwMDcyMjUwMDJaMB0xGzAZBgNVBAMMEm90aGVyLWRldmljZS5s\n"
            + "b2NhbDB2MBAGByqGSM49AgEGBSuBBAAiA2IABAA7hg4ggdBI3rowxjEOO5MrTAGu\n"
            + "y/EP0Quz0KmfM+JAMUbR9uldNAFsQ/V/R41bOl67fQqWaxEICkfoECp6bcwpOriI\n"
            + "rbMZS4BNnM7jpkq97M+ZEaRIlphC7YzHZkSFf6OBmDCBlTAMBgNVHRMBAf8EAjAA\n"
            + "MAsGA1UdDwQEAwIFoDATBgNVHSUEDDAKBggrBgEFBQcDATAjBgNVHREEHDAaghJv\n"
            + "dGhlci1kZXZpY2UubG9jYWyHBMCoAQUwHQYDVR0OBBYEFKOs5r+XDGrzYvhTtYcf\n"
            + "M4k5iXYyMB8GA1UdIwQYMBaAFBzP3ruMf5viWrn/EL37vqcS9DTMMAoGCCqGSM49\n"
            + "BAMDA2gAMGUCMQCNX2Ke0GWO9BIHNwEC6DmlQ489NSc3XL0w/G7rEgf7EfuMTLtP\n"
            + "6V4fgm3Owdllev4CMEt+FycZUNIliBqyuef2HsOPFWvlyPnx6RJuph/SN+Aafh+N\n"
            + "KTVZ54uqfwPYu0rj4w==\n"
            + "-----END CERTIFICATE-----\n";

    // Self-signed impostor with the broker's CN but no relation to the trusted root.
    private static final String FORGED_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIB0DCCAVagAwIBAgIUXcngdQBs/lGg0DEbbzO9R/GsRnQwCgYIKoZIzj0EAwMw\n"
            + "HzEdMBsGA1UEAwwUcGVuZ3Vpbi1icm9rZXIubG9jYWwwHhcNMjYwNzMwMjIzMTQ5\n"
            + "WhcNMjcwNzMwMjIzMTQ5WjAfMR0wGwYDVQQDDBRwZW5ndWluLWJyb2tlci5sb2Nh\n"
            + "bDB2MBAGByqGSM49AgEGBSuBBAAiA2IABBEkkEkDFKtXwCFlB8dy/vCTExwkAPSt\n"
            + "e8az7swFHJf/MsIV7bnA1xvXRCnS6GNLWctMQ5333iYAk35o3eHZj4yvyFVUbC5r\n"
            + "5NqaKMgL4DxZawfMv3qm7jtOXvxC8Peed6NTMFEwHQYDVR0OBBYEFFzuS68p5TvX\n"
            + "2vgT7smi4L+yGAEMMB8GA1UdIwQYMBaAFFzuS68p5TvX2vgT7smi4L+yGAEMMA8G\n"
            + "A1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwMDaAAwZQIwP23nCIpCQtd1uojnc8d0\n"
            + "CCqypzEvMnp/D9eQ0GZ9i2JQZEYqUIdyp7zXqX4/ZvLjAjEAsjSyr7JPLfeHJXCC\n"
            + "Znze5rLgp0+w2CD/Sqd7gFKiJHkllxIQA7xZD1fmzZ8Q7A02\n"
            + "-----END CERTIFICATE-----\n";

    // A second self-signed CA, standing in for the gateway CA that issues both broker and device
    // certificates. The two leaves below chain to it and differ only in extendedKeyUsage, so the
    // EKU tests turn on that extension alone. It is separate from ROOT_PEM only because the
    // private keys of that chain were not kept, so nothing new can be issued under it.
    private static final String DEVICE_CA_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIB5DCCAWqgAwIBAgIUSaSgNMtMfU7BLfGgjGsEGxRX55swCgYIKoZIzj0EAwMw\n"
            + "ITEfMB0GA1UEAwwWUGVuZ3VpbiBURVNUIERldmljZSBDQTAeFw0yNjA4MTIxODA0\n"
            + "MTRaFw0zNjA4MDkxODA0MTRaMCExHzAdBgNVBAMMFlBlbmd1aW4gVEVTVCBEZXZp\n"
            + "Y2UgQ0EwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAAQakjihhfkV4zxS5jBKizCO/1xI\n"
            + "cuikY8sqaNkSsMylVzy8nd+8uaobuFvuFZCHRS7bLhiQyu4uQTt0jEUEVlB9Aqb3\n"
            + "un7V3E61+JcSXURH7RxXieJybJ60M0wA6TX44JGjYzBhMB0GA1UdDgQWBBRdOviK\n"
            + "hKMdbTKUZg6lTbIXWwku7zAfBgNVHSMEGDAWgBRdOviKhKMdbTKUZg6lTbIXWwku\n"
            + "7zAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAKBggqhkjOPQQDAwNo\n"
            + "ADBlAjB0YIhy54oo/WpQMgHCTDvdxUMO1NUWCQjo1HvaLit63u21zIJWLLMD1AtY\n"
            + "yu4SO/wCMQCRvspXagNjZBp5NiE52QJectOeeImfvi/aWw95wcwzR+0RrdTt0dhr\n"
            + "An+/A6IzwbQ=\n"
            + "-----END CERTIFICATE-----\n";

    // Client certificate of the kind every device already holds: same CA, same CN and SANs as the
    // broker, extendedKeyUsage = clientAuth only.
    private static final String CLIENT_AUTH_LEAF_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIICAjCCAYigAwIBAgIBETAKBggqhkjOPQQDAzAhMR8wHQYDVQQDDBZQZW5ndWlu\n"
            + "IFRFU1QgRGV2aWNlIENBMB4XDTI2MDgxMjE4MDQxNFoXDTI4MTAyMDE4MDQxNFow\n"
            + "HzEdMBsGA1UEAwwUcGVuZ3Vpbi1icm9rZXIubG9jYWwwdjAQBgcqhkjOPQIBBgUr\n"
            + "gQQAIgNiAAQIp4q3tGLaGJ1/uLWIVlxhlo35ZBw00gB+cNHVudPPqucXmMsj31VZ\n"
            + "6F7F+hvZA8gmMsz1IyfqHc0DYKVhh01sVOEMHDa0nCjdD13N5SOsHkanck1Gqnvd\n"
            + "pmZPvPyZcdWjgZUwgZIwDAYDVR0TAQH/BAIwADALBgNVHQ8EBAMCB4AwEwYDVR0l\n"
            + "BAwwCgYIKwYBBQUHAwIwIAYDVR0RBBkwF4IJbG9jYWxob3N0hwR/AAABhwQKAAIC\n"
            + "MB0GA1UdDgQWBBTCLwjxlpjUimo0Cp7L+baRRz+cTTAfBgNVHSMEGDAWgBRdOviK\n"
            + "hKMdbTKUZg6lTbIXWwku7zAKBggqhkjOPQQDAwNoADBlAjAD1IsbbH4wcov/HLqV\n"
            + "PUQ7gvWFR705uz2fNaFn/wnzFCYufKnm07LBMKbbFEG9htICMQDxd/aH4smZ0Q9y\n"
            + "qgVNU6jlp9xkBy6xKMjilfqiVZo6+YdtEqnfnD91mL1j/xOoRng=\n"
            + "-----END CERTIFICATE-----\n";

    // Same CA and SANs, no extendedKeyUsage extension at all: unrestricted, so accepted.
    private static final String NO_EKU_LEAF_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIB6zCCAXGgAwIBAgIBEjAKBggqhkjOPQQDAzAhMR8wHQYDVQQDDBZQZW5ndWlu\n"
            + "IFRFU1QgRGV2aWNlIENBMB4XDTI2MDgxMjE4MDQxNFoXDTI4MTAyMDE4MDQxNFow\n"
            + "HzEdMBsGA1UEAwwUcGVuZ3Vpbi1icm9rZXIubG9jYWwwdjAQBgcqhkjOPQIBBgUr\n"
            + "gQQAIgNiAAS5HANlVmZRi5rOe5y+7MfWAl5pi8FgiL2r3a0btQrtgcl8YOQCTxWv\n"
            + "QHgg0T4FRmbymAw96uXPWvaYUWty4Ub7UJL5Kj2mXOU5RQlcXx7sY0qnEYTNFsl5\n"
            + "AE+hl0LSbiujfzB9MAwGA1UdEwEB/wQCMAAwCwYDVR0PBAQDAgWgMCAGA1UdEQQZ\n"
            + "MBeCCWxvY2FsaG9zdIcEfwAAAYcECgACAjAdBgNVHQ4EFgQUfGoq70Fuzs60e5RI\n"
            + "K7ssjQyRflIwHwYDVR0jBBgwFoAUXTr4ioSjHW0ylGYOpU2yF1sJLu8wCgYIKoZI\n"
            + "zj0EAwMDaAAwZQIxANCgsyc2G1klHRRu2aimQkDyJQRI0IsdnRoDNNqqb+1h9Brr\n"
            + "qizl5buVvB4joASefAIwQ5OwQlHuexmbgxu4FJgAk69KIeOS5rZQIWiFzBQ0vSO+\n"
            + "+TQHM1BITlZk4PHP7+II\n"
            + "-----END CERTIFICATE-----\n";

    private static X509Certificate cert(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    private static KeyStore trustStoreWith(X509Certificate... cas) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        for (int i = 0; i < cas.length; i++) {
            ks.setCertificateEntry("ca-" + i, cas[i]);
        }
        return ks;
    }

    /** Constructs the private CustomTrustManager(KeyStore, String, String) via reflection. */
    private static Object newTrustManager(KeyStore trustStore, String expectedBrokerCN, String expectedSniHost)
            throws Exception {
        Class<?> clazz = Class.forName("com.reactnativemqttmtls.MqttModule$CustomTrustManager");
        Constructor<?> ctor = clazz.getDeclaredConstructor(KeyStore.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(trustStore, expectedBrokerCN, expectedSniHost);
    }

    private static void checkServerTrusted(Object trustManager, X509Certificate[] chain) throws Exception {
        Method method = trustManager.getClass().getDeclaredMethod(
                "checkServerTrusted", X509Certificate[].class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(trustManager, chain, "ECDHE_ECDSA");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof CertificateException) {
                throw (CertificateException) e.getCause();
            }
            throw e;
        }
    }

    // ========================================================================
    // Chain validation via CertPathValidator (replaces manual signature loops)
    // ========================================================================

    @Test
    public void testValidChain_TrustedByRootAnchor_Accepted() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        Object tm = newTrustManager(trustStoreWith(root), null, null);

        // Admin mode (both null): chain validation must still run and accept a valid chain,
        // even though the leaf's 800-day validity would violate Apple/CA-Browser system caps.
        checkServerTrusted(tm, new X509Certificate[] { broker, intermediate });
    }

    @Test
    public void testValidChain_TrustedByIntermediateAnchor_Accepted() throws Exception {
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        Object tm = newTrustManager(trustStoreWith(intermediate), null, null);

        checkServerTrusted(tm, new X509Certificate[] { broker });
    }

    @Test(expected = CertificateException.class)
    public void testExpiredCert_Rejected() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate expired = cert(EXPIRED_PEM);

        Object tm = newTrustManager(trustStoreWith(root), null, null);

        // An expired cert with a valid signature chain must be rejected — CertPathValidator
        // enforces expiry.
        checkServerTrusted(tm, new X509Certificate[] { expired, intermediate });
    }

    @Test(expected = CertificateException.class)
    public void testForgedSelfSignedCert_Rejected() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate forged = cert(FORGED_PEM);

        Object tm = newTrustManager(trustStoreWith(root), null, null);

        checkServerTrusted(tm, new X509Certificate[] { forged });
    }

    @Test(expected = CertificateException.class)
    public void testEmptyChain_Rejected() throws Exception {
        // Hits the chain == null || chain.length == 0 guard at the top of checkServerTrusted,
        // before validateCertificateChain is reached. See testChainOfOnlyAnchor_Rejected for the
        // separate empty-path guard inside the validator.
        X509Certificate root = cert(ROOT_PEM);
        Object tm = newTrustManager(trustStoreWith(root), null, null);

        checkServerTrusted(tm, new X509Certificate[0]);
    }

    @Test
    public void testChainOfOnlyAnchor_Rejected() throws Exception {
        // A server presenting nothing but our own trust anchor leaves no leaf to validate: PKIX
        // needs the path to stop just below the anchor, so stripping the anchor empties the path.
        // Asserting on the message proves this reaches the validator's own guard rather than the
        // length check at the top of checkServerTrusted.
        X509Certificate root = cert(ROOT_PEM);
        Object tm = newTrustManager(trustStoreWith(root), null, null);

        try {
            checkServerTrusted(tm, new X509Certificate[] { root });
            fail("A chain containing only the trust anchor must be rejected");
        } catch (CertificateException e) {
            assertTrue("Expected the no-leaf-certificate guard, got: " + e.getMessage(),
                    e.getMessage().contains("no leaf certificate"));
        }
    }

    // ========================================================================
    // Extended key usage — the leaf must be a TLS server certificate.
    // Runs unconditionally, like chain validation, not as an identity pin.
    // ========================================================================

    @Test
    public void testClientCertificateFromSameCA_Rejected() throws Exception {
        // The attack PKIX alone does not stop: a device's own client certificate, validly issued by
        // the trusted CA with the broker's CN and SANs, presented as the broker. Admin mode (both
        // pins null) is what production runs, so the EKU check is the only thing standing here.
        X509Certificate ca = cert(DEVICE_CA_PEM);
        X509Certificate clientLeaf = cert(CLIENT_AUTH_LEAF_PEM);

        Object tm = newTrustManager(trustStoreWith(ca), null, null);

        try {
            checkServerTrusted(tm, new X509Certificate[] { clientLeaf });
            fail("A clientAuth-only certificate must not be accepted as the broker");
        } catch (CertificateException e) {
            assertTrue("Message should name TLS server authentication: " + e.getMessage(),
                    e.getMessage().contains("not valid for TLS server authentication"));
        }
    }

    @Test
    public void testServerCertificateWithNoEKUExtension_Accepted() throws Exception {
        // A leaf with no extendedKeyUsage extension is unrestricted. iOS accepts it too, so
        // rejecting it here would break brokers that still connect on iOS. Pinned deliberately:
        // tightening this is a behaviour change, not a bug fix.
        X509Certificate ca = cert(DEVICE_CA_PEM);
        X509Certificate noEkuLeaf = cert(NO_EKU_LEAF_PEM);

        Object tm = newTrustManager(trustStoreWith(ca), null, null);

        checkServerTrusted(tm, new X509Certificate[] { noEkuLeaf });
    }

    @Test
    public void testBrokerCertificateWithServerAuthEKU_Accepted() throws Exception {
        // Companion to the rejection above: the broker fixture carries serverAuth, so the new
        // check passes it. Without this case, deleting the check would still leave a green suite
        // for every other reason a valid chain is accepted.
        X509Certificate ca = cert(DEVICE_CA_PEM);
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        Object tm = newTrustManager(trustStoreWith(root, ca), null, null);

        checkServerTrusted(tm, new X509Certificate[] { broker, intermediate });
    }

    // ========================================================================
    // SNI host/SAN matching — gated by isAdminUser via expectedSniHost
    // ========================================================================

    @Test
    public void testMatchingHost_NonAdmin_Accepted() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        Object tm = newTrustManager(trustStoreWith(root), null, "localhost");

        checkServerTrusted(tm, new X509Certificate[] { broker, intermediate });
    }

    @Test(expected = CertificateException.class)
    public void testMismatchedHost_NonAdmin_Rejected() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        // Cert chain-validates fine and is trusted by our CA, but its SANs
        // (localhost/127.0.0.1/10.0.2.2) don't include "some-other-broker.local" — a cert
        // validly issued by a trusted CA for one host must not be accepted for another.
        Object tm = newTrustManager(trustStoreWith(root), null, "some-other-broker.local");

        checkServerTrusted(tm, new X509Certificate[] { broker, intermediate });
    }

    @Test
    public void testMatchingIPv4SAN_NonAdmin_Accepted() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        // getSubjectAlternativeNames() returns iPAddress (type 7) SANs as a String on the
        // standard provider (matching MqttModule's certificateMatchesHost comment); this
        // exercises that path directly.
        Object tm = newTrustManager(trustStoreWith(root), null, "127.0.0.1");

        checkServerTrusted(tm, new X509Certificate[] { broker, intermediate });
    }

    @Test
    public void testMatchingSecondIPv4SAN_NonAdmin_Accepted() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        Object tm = newTrustManager(trustStoreWith(root), null, "10.0.2.2");

        checkServerTrusted(tm, new X509Certificate[] { broker, intermediate });
    }

    @Test(expected = CertificateException.class)
    public void testMismatchedIPSAN_NonAdmin_Rejected() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        Object tm = newTrustManager(trustStoreWith(root), null, "192.168.1.5");

        checkServerTrusted(tm, new X509Certificate[] { broker, intermediate });
    }

    @Test
    public void testCertificateWithNoSANExtension_ReportsMissingExtension_NotMismatch() throws Exception {
        // Three different failures used to collapse into "SAN does not match SNI host": a real
        // mismatch, an unparseable extension, and no extension at all. Only the first justifies
        // that message. ROOT_PEM is a CA with no subjectAltName, so it exercises the third case;
        // calling the matcher directly keeps chain validation out of the way.
        X509Certificate root = cert(ROOT_PEM);
        Object tm = newTrustManager(trustStoreWith(root), null, "localhost");

        Method matches = tm.getClass().getDeclaredMethod(
                "certificateMatchesHost", X509Certificate.class, String.class);
        matches.setAccessible(true);
        try {
            matches.invoke(tm, root, "localhost");
            fail("A certificate with no subjectAltName extension must not be reported as a mismatch");
        } catch (InvocationTargetException e) {
            assertTrue("Expected CertificateException, got " + e.getCause(),
                    e.getCause() instanceof CertificateException);
            assertTrue("Message should name the missing extension: " + e.getCause().getMessage(),
                    e.getCause().getMessage().contains("no subjectAltName extension"));
        }
    }

    @Test(expected = CertificateException.class)
    public void testDifferentDeviceCert_SANMismatch_Rejected() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate otherHost = cert(OTHER_HOST_PEM);

        // otherHost is validly signed by our trusted CA but was issued for a different
        // device's SANs — must be rejected when dialing "localhost".
        Object tm = newTrustManager(trustStoreWith(root), null, "localhost");

        checkServerTrusted(tm, new X509Certificate[] { otherHost, intermediate });
    }

    @Test
    public void testAdminUser_ForeignDeviceCert_Accepted_HostPinSkipped() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate otherHost = cert(OTHER_HOST_PEM);

        // Admin mode: expectedSniHost is null, so the SAN pin is skipped. This is the accepted
        // residual risk for admin/fleet connections — a certificate validly issued for a
        // different device is accepted as long as it chains to a trusted CA. Chain validation
        // (expiry, path, signature) still runs and would reject anything not CA-issued.
        Object tm = newTrustManager(trustStoreWith(root), null, null);

        checkServerTrusted(tm, new X509Certificate[] { otherHost, intermediate });
    }

    // ========================================================================
    // CN pinning still works as before (pre-existing behavior, guarded here
    // against regressions introduced while adding chain + SAN validation)
    // ========================================================================

    @Test
    public void testMatchingCN_NonAdmin_Accepted() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        Object tm = newTrustManager(trustStoreWith(root), "penguin-broker.local", null);

        checkServerTrusted(tm, new X509Certificate[] { broker, intermediate });
    }

    // ========================================================================
    // RFC 2253 DN parsing behind the CN pin
    // ========================================================================

    /** Invokes the static package-private CN parser on CustomTrustManager. */
    private static String cnFromDn(String dn) throws Exception {
        Class<?> clazz = Class.forName("com.reactnativemqttmtls.MqttModule$CustomTrustManager");
        Method method = clazz.getDeclaredMethod("cnFromDn", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, dn);
    }

    @Test
    public void testCnFromDn_EscapedCommaInValue_NotTruncated() throws Exception {
        // dn.split(",") returns "CN=Acme\" here, which would fail the CN pin against the real
        // subject. RFC 2253 escapes a comma inside a value, so only unescaped commas separate RDNs.
        assertEquals("Acme, Inc", cnFromDn("CN=Acme\\, Inc,O=Acme,C=US"));
    }

    @Test
    public void testCnFromDn_PlainValueAndOrdering() throws Exception {
        assertEquals("penguin-broker.local", cnFromDn("CN=penguin-broker.local"));
        assertEquals("penguin-broker.local", cnFromDn("O=Generac,CN=penguin-broker.local,C=US"));
        // An O value that merely contains "CN=" must not be mistaken for the CN attribute.
        assertEquals("real", cnFromDn("O=not a CN=decoy,CN=real"));
    }

    @Test
    public void testCnFromDn_NoCNAttribute_ReturnsNull() throws Exception {
        assertNull(cnFromDn("O=Generac,C=US"));
        assertNull(cnFromDn(null));
    }

    @Test
    public void testExtractCN_MatchesFixtureSubject() throws Exception {
        // Guards the parser against the real fixture, not just synthetic strings.
        assertEquals("penguin-broker.local", cnFromDn(
                cert(BROKER_PEM).getSubjectX500Principal().getName()));
    }

    @Test(expected = CertificateException.class)
    public void testMismatchedCN_NonAdmin_Rejected() throws Exception {
        X509Certificate root = cert(ROOT_PEM);
        X509Certificate intermediate = cert(INTERMEDIATE_PEM);
        X509Certificate broker = cert(BROKER_PEM);

        Object tm = newTrustManager(trustStoreWith(root), "some-other-device.local", null);

        checkServerTrusted(tm, new X509Certificate[] { broker, intermediate });
    }
}
