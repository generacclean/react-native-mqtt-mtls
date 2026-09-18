import Foundation

/// Turns an `Error` into one diagnostic line, following the underlying-error chain.
///
/// This exists because `localizedDescription` alone is not enough to tell two failures apart once
/// they reach a crash report. The Security and Network frameworks hand back errors whose description
/// is a generic sentence — "The operation couldn't be completed" — with the identifying detail in the
/// domain, the code, and an `NSUnderlyingErrorKey` one or more levels down. A TLS rejection, a
/// missing Keychain identity, and an unreachable host all read alike if only the top-level
/// description is reported.
///
/// Lives alongside `TrustValidator`, free of React Native and CocoaMQTT, so `swift test` can cover it;
/// see `Package.swift`.
enum ErrorDescription {

    /// Levels of `NSUnderlyingErrorKey` to follow. A cap rather than a visited-set because `Error` is
    /// not necessarily a reference type, so there is nothing stable to compare identities on; a
    /// chain this deep has long since named its cause anyway.
    private static let maxDepth = 8

    /// - Parameter error: The error to describe, or nil.
    /// - Returns: The chain, outermost first, joined by " <- caused by ".
    static func describe(_ error: Error?) -> String {
        guard let error = error else {
            return "Unknown error (no error reported)"
        }

        var levels: [String] = []
        var current: Error? = error
        while let level = current, levels.count < maxDepth {
            levels.append(describeOne(level))
            current = (level as NSError).userInfo[NSUnderlyingErrorKey] as? Error
        }
        return levels.joined(separator: " <- caused by ")
    }

    private static func describeOne(_ error: Error) -> String {
        let nsError = error as NSError
        var text = "\(nsError.domain)(code=\(nsError.code))"

        let localized = nsError.localizedDescription
        if !localized.isEmpty {
            text += ": \(localized)"
        }

        // A Swift enum or struct error bridges to NSError with a synthesized domain and a code that
        // is only its case index, so the case name — the part that says what went wrong — is lost.
        // Reflection recovers it. A genuine NSError is a class and already said everything above.
        if Mirror(reflecting: error).displayStyle != .class {
            text += " [\(String(describing: error))]"
        }

        return text
    }
}
