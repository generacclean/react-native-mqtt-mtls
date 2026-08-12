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
}
