import XCTest
import Security
import os.log
@testable import TrustValidation

/// Tests for the iOS server-trust validation path.
///
/// Fixtures are a real EC P-384 chain generated with openssl: Penguin TEST Root -> Intermediate ->
/// broker leaf (800-day validity, SANs localhost/127.0.0.1/10.0.2.2), plus a self-signed "forged"
/// cert carrying the correct CN but no relation to the root — the MITM impostor.
final class TrustValidatorTests: XCTestCase {

    private static let log = OSLog(subsystem: "com.neurio.generachome.tests", category: "TrustValidator")

    private static let rootPEM = """
    -----BEGIN CERTIFICATE-----
    MIIByjCCAVCgAwIBAgIUCvp5e+1jhwMlPy80fkNyNpa3l7cwCgYIKoZIzj0EAwMw
    HDEaMBgGA1UEAwwRUGVuZ3VpbiBURVNUIFJvb3QwHhcNMjYwNzMwMjIzMTQ4WhcN
    MzYwNzI3MjIzMTQ4WjAcMRowGAYDVQQDDBFQZW5ndWluIFRFU1QgUm9vdDB2MBAG
    ByqGSM49AgEGBSuBBAAiA2IABHo1LW1mTU5Q/WFbK1/kyw1QtZiDDrDjeATSn8Ez
    YdyPZpKuqJJ18j1aVv8Inw7ehln/vAfKVjSEDAt4BvKPcP1YJaGa4Ebvhkri8sbf
    GAoHfbaJ84gApvQiiQDElMt6b6NTMFEwHQYDVR0OBBYEFLKVNcfhYJYhGUVNIu0I
    mHWuSV+6MB8GA1UdIwQYMBaAFLKVNcfhYJYhGUVNIu0ImHWuSV+6MA8GA1UdEwEB
    /wQFMAMBAf8wCgYIKoZIzj0EAwMDaAAwZQIxAOU9KBgQ7WI4dOAl8guD3oON5noS
    TMNVWKq2RAdDnUO+kKxnYmDDvAumwOexnyvSggIwMDRXDd9CsgvteLQuK9oh72Rr
    5/ocUnKHv+otiypiZmWYaTzZV+cPlsFZNDLqKNHK
    -----END CERTIFICATE-----
    """

    private static let intermediatePEM = """
    -----BEGIN CERTIFICATE-----
    MIIB5TCCAWugAwIBAgIUZHS/kwQnv0uJgKEj623bkWTauBQwCgYIKoZIzj0EAwMw
    HDEaMBgGA1UEAwwRUGVuZ3VpbiBURVNUIFJvb3QwHhcNMjYwNzMwMjIzMTQ4WhcN
    MzYwNzI3MjIzMTQ4WjAkMSIwIAYDVQQDDBlQZW5ndWluIFRFU1QgSW50ZXJtZWRp
    YXRlMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAErDTCWwXFVF2L6m1rgO95AVDKJOkD
    k0OxL1peFKBY/hb5+mLvueaIaNuFVegrC5jMrdo3hWMZCJjQwg2QoZbpNrwiKCpP
    75eiM2ejmpGQdODyc2ORUQLfYURqIa1z4a5Jo2YwZDASBgNVHRMBAf8ECDAGAQH/
    AgEAMA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQUHM/eu4x/m+Jauf8Qvfu+pxL0
    NMwwHwYDVR0jBBgwFoAUspU1x+FgliEZRU0i7QiYda5JX7owCgYIKoZIzj0EAwMD
    aAAwZQIwdLRZBEFfpFVFSOLW68+H47Br/XegfJr/Na7ClyzVGK94wqwbJTuEuGXT
    w0sXxV0nAjEAnmXLXNu/PP/bmli3B0pYRsODWc3YXiG1hSKxakxz6d/E5NGPRA76
    qP1gVQ7E3A3+
    -----END CERTIFICATE-----
    """

    // Broker leaf: 800-day validity (> Apple's 398-day system-root cap), SANs
    // localhost/127.0.0.1/10.0.2.2, signed by the intermediate above.
    private static let brokerPEM = """
    -----BEGIN CERTIFICATE-----
    MIICGDCCAZ6gAwIBAgIURuVk+C+dQXYyxWfnCjFCddBH3XYwCgYIKoZIzj0EAwMw
    JDEiMCAGA1UEAwwZUGVuZ3VpbiBURVNUIEludGVybWVkaWF0ZTAeFw0yNjA3MzAy
    MjMxNDhaFw0yODEwMDcyMjMxNDhaMB8xHTAbBgNVBAMMFHBlbmd1aW4tYnJva2Vy
    LmxvY2FsMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEUeyJRIkjiiCUUy+Yig4mG03G
    erDoQVluBihhL8EsUxMY+HGhmsMnZBHiEz2wT7fgeMY3K4R7eby8dVMqOgK4pWjl
    2bhKMi99x/LYy2vxI70CA5CuYgHCL/AsLekWS1Mho4GVMIGSMAwGA1UdEwEB/wQC
    MAAwCwYDVR0PBAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUFBwMBMCAGA1UdEQQZMBeC
    CWxvY2FsaG9zdIcEfwAAAYcECgACAjAdBgNVHQ4EFgQU171C2J19At/K3IJaIxcX
    jIkpVLswHwYDVR0jBBgwFoAUHM/eu4x/m+Jauf8Qvfu+pxL0NMwwCgYIKoZIzj0E
    AwMDaAAwZQIwb4YI1+uMZ3KYFiPcLlkytkoQCjDakPxhAZ+geUda0fyy1oxVduLM
    A8Q3uJsKCzYmAjEAhNlKanvD/rilK8y7FrvlpAwMEBMLnMNsQWPILPTlTAXNJwAS
    s/CrY+bZ9m1fzFns
    -----END CERTIFICATE-----
    """

    // Self-signed impostor with the SAME CN as the broker but no relation to the
    // trusted root — models an attacker on the gateway Wi-Fi presenting a fake cert.
    private static let forgedPEM = """
    -----BEGIN CERTIFICATE-----
    MIIB0DCCAVagAwIBAgIUXcngdQBs/lGg0DEbbzO9R/GsRnQwCgYIKoZIzj0EAwMw
    HzEdMBsGA1UEAwwUcGVuZ3Vpbi1icm9rZXIubG9jYWwwHhcNMjYwNzMwMjIzMTQ5
    WhcNMjcwNzMwMjIzMTQ5WjAfMR0wGwYDVQQDDBRwZW5ndWluLWJyb2tlci5sb2Nh
    bDB2MBAGByqGSM49AgEGBSuBBAAiA2IABBEkkEkDFKtXwCFlB8dy/vCTExwkAPSt
    e8az7swFHJf/MsIV7bnA1xvXRCnS6GNLWctMQ5333iYAk35o3eHZj4yvyFVUbC5r
    5NqaKMgL4DxZawfMv3qm7jtOXvxC8Peed6NTMFEwHQYDVR0OBBYEFFzuS68p5TvX
    2vgT7smi4L+yGAEMMB8GA1UdIwQYMBaAFFzuS68p5TvX2vgT7smi4L+yGAEMMA8G
    A1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwMDaAAwZQIwP23nCIpCQtd1uojnc8d0
    CCqypzEvMnp/D9eQ0GZ9i2JQZEYqUIdyp7zXqX4/ZvLjAjEAsjSyr7JPLfeHJXCC
    Znze5rLgp0+w2CD/Sqd7gFKiJHkllxIQA7xZD1fmzZ8Q7A02
    -----END CERTIFICATE-----
    """

    // Expired leaf (valid Jan 1 2024 - Jan 31 2024, already expired), same SANs as brokerPEM.
    private static let expiredPEM = """
    -----BEGIN CERTIFICATE-----
    MIICGDCCAZ6gAwIBAgIURuVk+C+dQXYyxWfnCjFCddBH3XcwCgYIKoZIzj0EAwMw
    JDEiMCAGA1UEAwwZUGVuZ3VpbiBURVNUIEludGVybWVkaWF0ZTAeFw0yNDAxMDEw
    NjAwMDFaFw0yNDAxMzEwNjAwMDFaMB8xHTAbBgNVBAMMFHBlbmd1aW4tYnJva2Vy
    LmxvY2FsMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAETJqM6jY/xEiv9Kt/HeRgEIRl
    hi+Cdk3qbvEMnlN/8OqD7gsWwcWUU6zpcOzG6hP0qEHCL/OoCr0ATGSiMQrMlhKX
    CEscjl5FN/itH1YofDtDjCqvHFnW0dxiQ1N+HmaTo4GVMIGSMAwGA1UdEwEB/wQC
    MAAwCwYDVR0PBAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUFBwMBMCAGA1UdEQQZMBeC
    CWxvY2FsaG9zdIcEfwAAAYcECgACAjAdBgNVHQ4EFgQUTDAuTpUBUA/GNArWRnQQ
    XgG9gWYwHwYDVR0jBBgwFoAUHM/eu4x/m+Jauf8Qvfu+pxL0NMwwCgYIKoZIzj0E
    AwMDaAAwZQIxAOyxkKwmy9P8TcHIoZC4Ho5+Rh3RQZBcA/Pk8/S+ylDr9n5thcDg
    t6P+Y1YJoDSAxQIwFXUDfm69ntfjojFKv935MWq+tPgItXXy88GwqM9DWPvREwOG
    75fd4Fh6a/CXnxRQ
    -----END CERTIFICATE-----
    """

    // A second self-signed CA, standing in for the gateway CA that issues both broker and device
    // certificates. The four leaves below are identical apart from extendedKeyUsage, so the EKU
    // tests turn on that extension alone. They pin what Apple's SSL policy does with each value,
    // which is what CustomTrustManager.requireTlsServerCertificate matches on Android.
    private static let deviceCAPEM = """
    -----BEGIN CERTIFICATE-----
    MIIB4zCCAWqgAwIBAgIUezpkTnymbnI0nMn3hJ0aXJYdtvAwCgYIKoZIzj0EAwMw
    ITEfMB0GA1UEAwwWUGVuZ3VpbiBURVNUIERldmljZSBDQTAeFw0yNjA4MTIyMDIz
    MDNaFw0zNjA4MDkyMDIzMDNaMCExHzAdBgNVBAMMFlBlbmd1aW4gVEVTVCBEZXZp
    Y2UgQ0EwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAAShKAZtDy2f2sS5KD2VZXEnnM9Z
    pdifOSjy0JUnkrgrC91SJhBrfJt+U99jLoE+bbeUbPX/h1/BhxuckgMSTWMJ3iBJ
    6Fkgbyfu7cl3tlKqAmB0XIH23rXM0HeCWvuuQKmjYzBhMB8GA1UdIwQYMBaAFCLK
    LP4JgC1HWvIV0esVb82E0YLQMB0GA1UdDgQWBBQiyiz+CYAtR1ryFdHrFW/NhNGC
    0DAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAKBggqhkjOPQQDAwNn
    ADBkAjBu3lIe91/7oO39TOqlpfF09xUnTpiNOe4GNAjow56oguT4EiC9MM7jsb44
    PjV5e8oCMHkqQGnp48ROsurG8hr9qfR/Tt9wRTLml7k6XY2OtLLBqfXKcExj7iSJ
    iINa3pXDHw==
    -----END CERTIFICATE-----
    """

    // extendedKeyUsage = serverAuth: the control. If this one failed, a rejection below could be
    // caused by anything in the fixture rather than by the EKU value.
    private static let deviceServerAuthLeafPEM = """
    -----BEGIN CERTIFICATE-----
    MIICAjCCAYigAwIBAgIBETAKBggqhkjOPQQDAzAhMR8wHQYDVQQDDBZQZW5ndWlu
    IFRFU1QgRGV2aWNlIENBMB4XDTI2MDgxMjIwMjMwM1oXDTI4MTAyMDIwMjMwM1ow
    HzEdMBsGA1UEAwwUcGVuZ3Vpbi1icm9rZXIubG9jYWwwdjAQBgcqhkjOPQIBBgUr
    gQQAIgNiAARqe6mgKHgBbj/N2TCpgpFKGPDiKIy2a7Kixr+k2YXnfxlWG6pAbRft
    kxEF/pYSG0RzJ7N41Y13GZp8PJ6kZcV7sviSwsW31McUd3VX4sdyr4wR+y4bndgB
    MRW0IAczXLSjgZUwgZIwDAYDVR0TAQH/BAIwADALBgNVHQ8EBAMCBaAwEwYDVR0l
    BAwwCgYIKwYBBQUHAwEwIAYDVR0RBBkwF4IJbG9jYWxob3N0hwR/AAABhwQKAAIC
    MB0GA1UdDgQWBBRdPrv1HbVEy7tJvWZGkoqCaB0z6TAfBgNVHSMEGDAWgBQiyiz+
    CYAtR1ryFdHrFW/NhNGC0DAKBggqhkjOPQQDAwNoADBlAjEA2KCxLYaDmntG5BpA
    LHSk5PASsMKNhY7vAkEmcHGUFjGS5DXW6lODPDhckkJJk49bAjBF6OsNt/7pNycM
    3C3u/J+901Aex4PH1c5L03HbVT52fKkqb3WsrGiclIrsTs21mOQ=
    -----END CERTIFICATE-----
    """

    // extendedKeyUsage = clientAuth: the certificate every device already holds.
    private static let deviceClientAuthLeafPEM = """
    -----BEGIN CERTIFICATE-----
    MIICAzCCAYigAwIBAgIBEjAKBggqhkjOPQQDAzAhMR8wHQYDVQQDDBZQZW5ndWlu
    IFRFU1QgRGV2aWNlIENBMB4XDTI2MDgxMjIwMjMwM1oXDTI4MTAyMDIwMjMwM1ow
    HzEdMBsGA1UEAwwUcGVuZ3Vpbi1icm9rZXIubG9jYWwwdjAQBgcqhkjOPQIBBgUr
    gQQAIgNiAAQp6Mizi1Vmnkg1HdUjEggOIHu2BokL9q91/TR0F7Hoog1p8hT5mtIe
    W+vKVpl9EkL8fgZFOJFVIfNUKrhK7M79dOLQPQpT/2KLCrerW3HeKqKra2A5T7jy
    HF1XN3WU8kGjgZUwgZIwDAYDVR0TAQH/BAIwADALBgNVHQ8EBAMCBaAwEwYDVR0l
    BAwwCgYIKwYBBQUHAwIwIAYDVR0RBBkwF4IJbG9jYWxob3N0hwR/AAABhwQKAAIC
    MB0GA1UdDgQWBBQhaaVAN4Vhm4jdlVVdweWEVPZVNTAfBgNVHSMEGDAWgBQiyiz+
    CYAtR1ryFdHrFW/NhNGC0DAKBggqhkjOPQQDAwNpADBmAjEA8slft2p5beajn0io
    fGR4kF6X+MIgEfnfIw7eQ37Qp+iKuU7wau5FOC9MJf81b1EKAjEA5OyFt4pE89Yw
    NZR3GBzugcLd81J/4i2f7hyVWDA3I61/pF86+0jB1xvbGiI62zbC
    -----END CERTIFICATE-----
    """

    // No extendedKeyUsage extension at all.
    private static let deviceNoEkuLeafPEM = """
    -----BEGIN CERTIFICATE-----
    MIIB6zCCAXGgAwIBAgIBEzAKBggqhkjOPQQDAzAhMR8wHQYDVQQDDBZQZW5ndWlu
    IFRFU1QgRGV2aWNlIENBMB4XDTI2MDgxMjIwMjMwM1oXDTI4MTAyMDIwMjMwM1ow
    HzEdMBsGA1UEAwwUcGVuZ3Vpbi1icm9rZXIubG9jYWwwdjAQBgcqhkjOPQIBBgUr
    gQQAIgNiAAS5Qe6SYuoJ+ZYP754lBw1vzqwP6gzIlP1fxDoHZqK+ywcXxJX5ZPpm
    y3Ao3AY0C9TqHWvn0N3S4OcLJgDVgRE6SM9lPOT2iJztf9M+0FpjAbxSmFFawlRS
    eWDmFyAzJBujfzB9MAwGA1UdEwEB/wQCMAAwCwYDVR0PBAQDAgWgMCAGA1UdEQQZ
    MBeCCWxvY2FsaG9zdIcEfwAAAYcECgACAjAdBgNVHQ4EFgQUphse2hrlXfLOOEdf
    wupvevTRhwYwHwYDVR0jBBgwFoAUIsos/gmALUda8hXR6xVvzYTRgtAwCgYIKoZI
    zj0EAwMDaAAwZQIxAKsZrKX751oi6jDa4k86KMrE+xkV3LNQpImdLkcTAcIfnJKW
    bNbLzDxci71E+fLPiwIwWSXLM2m9iOIDrUmzheNY1UfvyvkODm2kcKCV8Reyd6uo
    O/RhZTyWyRvSn4X1/jB0
    -----END CERTIFICATE-----
    """

    // extendedKeyUsage = anyExtendedKeyUsage (2.5.29.37.0) alone.
    private static let deviceAnyEkuLeafPEM = """
    -----BEGIN CERTIFICATE-----
    MIIB/TCCAYSgAwIBAgIBFDAKBggqhkjOPQQDAzAhMR8wHQYDVQQDDBZQZW5ndWlu
    IFRFU1QgRGV2aWNlIENBMB4XDTI2MDgxMjIwMjMwM1oXDTI4MTAyMDIwMjMwM1ow
    HzEdMBsGA1UEAwwUcGVuZ3Vpbi1icm9rZXIubG9jYWwwdjAQBgcqhkjOPQIBBgUr
    gQQAIgNiAARoIuFbEdo0Ei1gu7BMFps727AbxOZTSpCQhwmBncuKlMAlRQh4wl4m
    cOoApsu5lMlK7J9wn7Q910m6j4YpOFRjsttINJwmK1qXntsfCq84UWBlXNC0hCzu
    UQ7Xo4wLeF2jgZEwgY4wDAYDVR0TAQH/BAIwADALBgNVHQ8EBAMCBaAwDwYDVR0l
    BAgwBgYEVR0lADAgBgNVHREEGTAXgglsb2NhbGhvc3SHBH8AAAGHBAoAAgIwHQYD
    VR0OBBYEFKdrEWYs9BRumjvT6T86GTUPTSdkMB8GA1UdIwQYMBaAFCLKLP4JgC1H
    WvIV0esVb82E0YLQMAoGCCqGSM49BAMDA2cAMGQCMBG+InM8sC/OnW3FdtYxhXc+
    jvHv1Mu2HQgD2ly4G/FFc01bGjgDOqGcAy7mN3GqcgIwUNI9tPgxx4lNhEjJkfif
    0uIaRXba54JBEK011qKc/u+hqdKppkdnCKYtCHeQ9bUg
    -----END CERTIFICATE-----
    """

    private static func certificate(fromPEM pem: String) -> SecCertificate {
        let base64 = pem
            .replacingOccurrences(of: "-----BEGIN CERTIFICATE-----", with: "")
            .replacingOccurrences(of: "-----END CERTIFICATE-----", with: "")
            .replacingOccurrences(of: "\n", with: "")
        guard let data = Data(base64Encoded: base64),
              let cert = SecCertificateCreateWithData(nil, data as CFData) else {
            fatalError("Failed to parse test fixture certificate")
        }
        return cert
    }

    /// Builds a SecTrust object presenting `leafAndChain` as the server's certificate chain,
    /// mirroring what CocoaMQTT hands the delegate during a real TLS handshake.
    private static func makeTrust(presenting leafAndChain: [SecCertificate]) -> SecTrust {
        var trust: SecTrust?
        let policy = SecPolicyCreateSSL(true, nil)
        let status = SecTrustCreateWithCertificates(leafAndChain as CFArray, policy, &trust)
        precondition(status == errSecSuccess, "Failed to create SecTrust fixture")
        return trust!
    }

    private func evaluate(_ trust: SecTrust, expectedCN: String?, anchors: [SecCertificate]) -> Bool {
        return TrustValidator.evaluate(trust: trust, expectedCN: expectedCN, anchors: anchors, log: Self.log)
    }

    func testValidChainWithRootAnchor_NoCNPin_Trusted() {
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [broker, intermediate])

        // No CN configured, so only chain validation applies. An 800-day leaf (which violates
        // Apple's 398-day cap for system-trusted roots) must still validate against our own
        // app-provided anchor, since system roots are excluded.
        let result = evaluate(trust, expectedCN: nil, anchors: [root])

        XCTAssertTrue(result, "Valid chain to app-provided root anchor should be trusted, even with >398-day leaf")
    }

    func testValidChainWithIntermediateAnchor_Trusted() {
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)

        let trust = Self.makeTrust(presenting: [broker])

        let result = evaluate(trust, expectedCN: nil, anchors: [intermediate])

        XCTAssertTrue(result, "Anchoring directly on the intermediate should also validate")
    }

    func testForgedSelfSignedCert_Rejected() {
        let forged = Self.certificate(fromPEM: Self.forgedPEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [forged])

        // A self-signed cert presenting the broker's CN but not chaining to our trusted root
        // (e.g. an attacker on the gateway Wi-Fi) must be rejected whether or not a CN is pinned.
        let result = evaluate(trust, expectedCN: nil, anchors: [root])

        XCTAssertFalse(result, "Self-signed impostor cert not chaining to our root must be rejected")
    }

    func testForgedCert_RejectedEvenWithCNPinning() {
        let forged = Self.certificate(fromPEM: Self.forgedPEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [forged])

        // Even though the forged cert's CN matches exactly, chain validation must fail first and
        // reject before the CN comparison is reached.
        let result = evaluate(trust, expectedCN: "penguin-broker.local", anchors: [root])

        XCTAssertFalse(result, "Chain validation must reject the impostor regardless of CN pinning")
    }

    func testExpiredCert_Rejected() {
        let expired = Self.certificate(fromPEM: Self.expiredPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [expired, intermediate])

        // SecTrustEvaluateWithError enforces expiry, so this must be rejected even though the cert
        // is validly signed by our trusted root.
        let result = evaluate(trust, expectedCN: nil, anchors: [root])

        XCTAssertFalse(result, "Expired cert must be rejected by SecTrustEvaluateWithError")
    }

    func testNoAnchorsConfigured_Rejected() {
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)

        let trust = Self.makeTrust(presenting: [broker, intermediate])

        let result = evaluate(trust, expectedCN: nil, anchors: [])

        XCTAssertFalse(result, "Missing trusted anchors must fail closed, never accept-any")
    }

    func testValidChainWithMatchingCN_Trusted() {
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [broker, intermediate])

        let result = evaluate(trust, expectedCN: "penguin-broker.local", anchors: [root])

        XCTAssertTrue(result, "Valid chain + matching CN pin should be trusted")
    }

    func testValidChainWithMismatchedCN_Rejected() {
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)
        let root = Self.certificate(fromPEM: Self.rootPEM)

        let trust = Self.makeTrust(presenting: [broker, intermediate])

        let result = evaluate(trust, expectedCN: "some-other-device.local", anchors: [root])

        XCTAssertFalse(result, "Valid chain but CN pin mismatch must still be rejected")
    }

    /// A leaf that chains to one anchor must not be accepted when a different, unrelated anchor is
    /// the only one configured. This is the anchor restriction itself, independent of the leaf being
    /// forged: the broker leaf here is genuine, it just does not chain to the anchor on offer.
    ///
    /// Note on coverage: the `SecTrustSetAnchorCertificatesOnly` status check in `evaluate` cannot be
    /// exercised from here. Every fixture in this file chains to a private test CA, so the system
    /// root store could never satisfy them and its exclusion makes no observable difference. Proving
    /// that path needs a genuinely publicly-issued certificate, which would expire and turn this
    /// suite into a false green. The check is a fail-closed guard, kept for the case where the
    /// setter reports an error at runtime.
    func testGenuineLeafWithUnrelatedAnchor_Rejected() {
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)
        let unrelatedAnchor = Self.certificate(fromPEM: Self.forgedPEM)

        let trust = Self.makeTrust(presenting: [broker, intermediate])

        let result = evaluate(trust, expectedCN: nil, anchors: [unrelatedAnchor])

        XCTAssertFalse(result, "A chain that does not reach a configured anchor must be rejected")
    }

    func testLeafCertificate_ReturnsPresentedLeaf() {
        let broker = Self.certificate(fromPEM: Self.brokerPEM)
        let intermediate = Self.certificate(fromPEM: Self.intermediatePEM)
        let trust = Self.makeTrust(presenting: [broker, intermediate])

        let leaf = TrustValidator.leafCertificate(from: trust)

        XCTAssertEqual(leaf, broker, "The leaf must be the first presented certificate, not the issuer")
    }

    func testCommonName_ExtractedFromLeaf() {
        let broker = Self.certificate(fromPEM: Self.brokerPEM)

        let cn = TrustValidator.commonName(from: broker, log: Self.log)

        XCTAssertEqual(cn, "penguin-broker.local", "CN extraction must read the subject CN")
    }

    // MARK: - Extended key usage: what Apple's SSL policy accepts

    // These four cases document the behaviour CustomTrustManager.requireTlsServerCertificate is
    // written to match on Android. SecPolicyCreateSSL(true, nil) requires the leaf to assert
    // id-kp-serverAuth explicitly: a leaf with no EKU extension and a leaf asserting only
    // anyExtendedKeyUsage are both refused with "Extended key usage does not match certificate
    // usage", so neither is a broker the iOS build can reach.

    func testServerAuthLeaf_Trusted() {
        let leaf = Self.certificate(fromPEM: Self.deviceServerAuthLeafPEM)
        let ca = Self.certificate(fromPEM: Self.deviceCAPEM)

        let trust = Self.makeTrust(presenting: [leaf])

        XCTAssertTrue(evaluate(trust, expectedCN: nil, anchors: [ca]),
                      "A serverAuth leaf under the configured anchor must be trusted — this is the control the three rejections below are measured against")
    }

    func testClientAuthOnlyLeaf_Rejected() {
        let leaf = Self.certificate(fromPEM: Self.deviceClientAuthLeafPEM)
        let ca = Self.certificate(fromPEM: Self.deviceCAPEM)

        let trust = Self.makeTrust(presenting: [leaf])

        XCTAssertFalse(evaluate(trust, expectedCN: nil, anchors: [ca]),
                       "A device's own clientAuth certificate must not be accepted as the broker")
    }

    func testLeafWithNoExtendedKeyUsage_Rejected() {
        let leaf = Self.certificate(fromPEM: Self.deviceNoEkuLeafPEM)
        let ca = Self.certificate(fromPEM: Self.deviceCAPEM)

        let trust = Self.makeTrust(presenting: [leaf])

        XCTAssertFalse(evaluate(trust, expectedCN: nil, anchors: [ca]),
                       "Apple's SSL policy requires an explicit serverAuth EKU: absent EKU is not treated as unrestricted")
    }

    func testLeafWithAnyExtendedKeyUsage_Rejected() {
        let leaf = Self.certificate(fromPEM: Self.deviceAnyEkuLeafPEM)
        let ca = Self.certificate(fromPEM: Self.deviceCAPEM)

        let trust = Self.makeTrust(presenting: [leaf])

        XCTAssertFalse(evaluate(trust, expectedCN: nil, anchors: [ca]),
                       "anyExtendedKeyUsage is not a substitute for serverAuth under Apple's SSL policy")
    }

}
