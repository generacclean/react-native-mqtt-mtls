import XCTest
import Foundation
@testable import TrustValidation

/// Tests for the error text iOS sends to JS.
///
/// The Android counterpart is `MqttErrorDescriptionTest`; both exist because a staging crash report
/// carried nothing but the word "MqttException", and a `localizedDescription`-only iOS path is the
/// same trap: Security and Network framework errors describe themselves with a generic sentence and
/// keep the identifying detail in the domain, the code, and an underlying error.
final class ErrorDescriptionTests: XCTestCase {

    func testNilErrorIsReportedExplicitly() {
        // CocoaMQTT's disconnect delegate takes an optional error, and the callback still has to say
        // something when it is nil.
        XCTAssertEqual(ErrorDescription.describe(nil), "Unknown error (no error reported)")
    }

    func testDomainAndCodeAreAlwaysIncluded() {
        // The pair is what distinguishes two errors whose descriptions read alike.
        let error = NSError(domain: "NSOSStatusErrorDomain", code: -9807,
                            userInfo: [NSLocalizedDescriptionKey: "The operation couldn’t be completed."])

        let description = ErrorDescription.describe(error)

        XCTAssertTrue(description.contains("NSOSStatusErrorDomain"), description)
        XCTAssertTrue(description.contains("code=-9807"), description)
        XCTAssertTrue(description.contains("The operation couldn’t be completed."), description)
    }

    func testUnderlyingErrorChainIsFollowed() {
        let root = NSError(domain: "NSOSStatusErrorDomain", code: -25300,
                           userInfo: [NSLocalizedDescriptionKey: "The specified item could not be found in the keychain."])
        let middle = NSError(domain: "MqttModule", code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "Private key not found in Keychain",
                                       NSUnderlyingErrorKey: root])
        let outer = NSError(domain: "NSURLErrorDomain", code: -1200,
                            userInfo: [NSLocalizedDescriptionKey: "An SSL error has occurred.",
                                       NSUnderlyingErrorKey: middle])

        let description = ErrorDescription.describe(outer)

        XCTAssertTrue(description.hasPrefix("NSURLErrorDomain(code=-1200)"), description)
        XCTAssertTrue(description.contains("Private key not found in Keychain"), description)
        XCTAssertTrue(description.contains("could not be found in the keychain"), description)
        XCTAssertEqual(description.components(separatedBy: " <- caused by ").count, 3, description)
    }

    func testDeepChainIsCappedRatherThanWalkedForever() {
        // Nothing prevents an underlying-error chain from being circular, and a hang inside an error
        // path is the worst place to have one.
        var error = NSError(domain: "Level", code: 0, userInfo: [:])
        for level in 1...20 {
            error = NSError(domain: "Level", code: level, userInfo: [NSUnderlyingErrorKey: error])
        }

        let description = ErrorDescription.describe(error)

        XCTAssertEqual(description.components(separatedBy: " <- caused by ").count, 8, description)
    }

    func testSwiftErrorKeepsItsCaseName() {
        // A Swift enum bridges to an NSError whose code is only the case index, so the case name —
        // the part that says what failed — would otherwise be lost.
        let description = ErrorDescription.describe(SampleFailure.keychainItemMissing(alias: "device-key"))

        XCTAssertTrue(description.contains("keychainItemMissing"), description)
        XCTAssertTrue(description.contains("device-key"), description)
    }

    private enum SampleFailure: Error {
        case keychainItemMissing(alias: String)
    }
}
