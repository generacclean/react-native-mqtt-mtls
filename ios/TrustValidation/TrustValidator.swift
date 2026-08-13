import Foundation
import Security
import os.log

/// Server-certificate trust validation for the MQTT TLS handshake.
///
/// This sits apart from `MqttModule` and depends only on Foundation and Security, so it compiles
/// without React Native or CocoaMQTT. That is what lets `swift test` exercise it directly — see
/// `Package.swift` and `TrustValidatorTests.swift`. `MqttModule`'s `didReceive trust:` delegate is
/// a thin wrapper over `evaluate(trust:expectedCN:anchors:log:)`.
enum TrustValidator {

    /// Validates a server's TLS trust object. Chain validation against the app-provided anchors is
    /// always required; CN pinning against `expectedCN` is skipped only when `expectedCN` is
    /// nil/empty (admin users, who talk to many brokers and have no single CN to pin against).
    ///
    /// - Parameters:
    ///   - trust: The `SecTrust` object presented by the TLS handshake.
    ///   - expectedCN: The known device CN to pin against, or nil/empty to skip CN pinning.
    ///   - anchors: The app-provided root CA certificate(s) to validate the chain against.
    ///   - log: Destination for the validation trace.
    /// - Returns: true if the server should be trusted.
    static func evaluate(trust: SecTrust,
                         expectedCN: String?,
                         anchors: [SecCertificate],
                         log: OSLog) -> Bool {
        // STEP 1: Validate the server's certificate chain against our app-provided root CA(s).
        // This runs unconditionally, admin or not — an unset CN only ever skips the pin below.
        // The policy carries no built-in hostname check (nil host): CN pinning below is our
        // hostname-equivalent control, and it must stay skippable for admin users.
        guard !anchors.isEmpty else {
            os_log("  ✗ No trusted root CA certificates configured — rejecting", log: log, type: .error)
            return false
        }

        let policy = SecPolicyCreateSSL(true, nil)

        // Every one of these setters has to take effect before the evaluation below means
        // anything, so their OSStatus is checked rather than discarded. Discarding them fails
        // open: if SecTrustSetAnchorCertificatesOnly does not apply, SecTrustEvaluateWithError
        // falls back to the system root store and accepts any publicly-issued certificate.
        let policyStatus = SecTrustSetPolicies(trust, policy)
        let anchorStatus = SecTrustSetAnchorCertificates(trust, anchors as CFArray)
        let anchorsOnlyStatus = SecTrustSetAnchorCertificatesOnly(trust, true)

        guard policyStatus == errSecSuccess,
              anchorStatus == errSecSuccess,
              anchorsOnlyStatus == errSecSuccess else {
            os_log("  ✗ Could not restrict trust to app-provided anchors — rejecting (policy: %d, anchors: %d, anchorsOnly: %d)",
                   log: log, type: .error, policyStatus, anchorStatus, anchorsOnlyStatus)
            return false
        }

        var trustError: CFError?
        guard SecTrustEvaluateWithError(trust, &trustError) else {
            os_log("  ✗ Certificate chain validation FAILED: %{public}@", log: log, type: .error,
                   (trustError as Error?)?.localizedDescription ?? "unknown error")
            return false
        }
        os_log("  ✓ Certificate chain validated against app-provided anchor(s)", log: log, type: .info)

        // STEP 2: CN pinning — skipped when no expected CN is configured (admin users).
        guard let expectedCN, !expectedCN.isEmpty else {
            os_log("  - No expected CN configured — CN pin skipped", log: log, type: .info)
            return true
        }

        guard let serverCert = leafCertificate(from: trust) else {
            os_log("  ✗ Cannot retrieve server certificate", log: log, type: .error)
            return false
        }

        guard let actualCN = commonName(from: serverCert, log: log) else {
            os_log("  ✗ Cannot extract CN from server certificate", log: log, type: .error)
            return false
        }

        guard actualCN == expectedCN else {
            os_log("  ✗ CN MISMATCH! Expected: %{public}@, Actual: %{public}@",
                   log: log, type: .error, expectedCN, actualCN)
            return false
        }
        os_log("  ✓ CN matches: %{public}@", log: log, type: .info, actualCN)
        return true
    }

    /// Retrieves the leaf certificate from a trust object.
    /// `SecTrustGetCertificateAtIndex` is deprecated as of iOS 15 in favor of
    /// `SecTrustCopyCertificateChain`; this keeps the iOS 12 minimum deployment target
    /// (per the podspec) working while preferring the modern API where available.
    static func leafCertificate(from trust: SecTrust) -> SecCertificate? {
        if #available(iOS 15.0, macOS 12.0, *) {
            guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] else {
                return nil
            }
            return chain.first
        } else {
            return SecTrustGetCertificateAtIndex(trust, 0)
        }
    }

    /// Extracts the subject CN from a certificate, preferring the subject summary and falling back
    /// to `SecCertificateCopyCommonName`.
    static func commonName(from certificate: SecCertificate, log: OSLog) -> String? {
        if let cn = commonNameFromSubjectSummary(certificate, log: log) {
            return cn
        }

        var copiedName: CFString?
        let status = SecCertificateCopyCommonName(certificate, &copiedName)
        if status == errSecSuccess, let cn = copiedName as String? {
            os_log("              ✓ CN via SecCertificateCopyCommonName: %{public}@", log: log, type: .info, cn)
            return cn
        }

        os_log("              ✗ Failed to extract CN", log: log, type: .error)
        return nil
    }

    /// The subject summary is a display string, so its shape varies by certificate: a bare CN for
    /// simple subjects, otherwise comma- or slash-separated RDNs.
    private static func commonNameFromSubjectSummary(_ certificate: SecCertificate, log: OSLog) -> String? {
        guard let summary = SecCertificateCopySubjectSummary(certificate) as String? else {
            return nil
        }

        if !summary.contains("=") && !summary.contains(",") {
            os_log("              ✓ CN (simple): %{public}@", log: log, type: .info, summary)
            return summary
        }

        for separator in [",", "/"] {
            for component in summary.components(separatedBy: separator) {
                let trimmed = component.trimmingCharacters(in: .whitespaces)
                if trimmed.lowercased().hasPrefix("cn=") {
                    let cn = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                    os_log("              ✓ CN (parsed): %{public}@", log: log, type: .info, cn)
                    return cn
                }
            }
        }

        return nil
    }
}
