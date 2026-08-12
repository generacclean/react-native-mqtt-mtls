// swift-tools-version:5.7
import PackageDescription

// This package exists only to make the iOS server-trust validation testable in CI. The full
// MqttModule needs React Native and CocoaMQTT and is built by CocoaPods via the podspec; the
// trust logic is deliberately kept free of those dependencies so `swift test` can exercise it.
let package = Package(
    name: "TrustValidation",
    platforms: [.iOS(.v12), .macOS(.v12)],
    products: [
        .library(name: "TrustValidation", targets: ["TrustValidation"])
    ],
    targets: [
        .target(name: "TrustValidation", path: "ios/TrustValidation"),
        .testTarget(
            name: "TrustValidationTests",
            dependencies: ["TrustValidation"],
            path: "ios/TrustValidationTests"
        )
    ]
)
