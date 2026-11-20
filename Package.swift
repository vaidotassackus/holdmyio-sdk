// swift-tools-version: 5.9
import PackageDescription

// Hold My IO Swift SDK. Docs: https://io.holdmy.company/docs
let package = Package(
    name: "HoldMyIO",
    platforms: [.iOS(.v15), .macOS(.v12), .tvOS(.v15)],
    products: [
        .library(name: "HoldMyIO", targets: ["HoldMyIO"]),
    ],
    targets: [
        .target(
            name: "HoldMyIO",
            path: "sdk-swift/Sources/HoldMyIO",
            resources: [.copy("PrivacyInfo.xcprivacy")]
        ),
        .testTarget(
            name: "HoldMyIOTests",
            dependencies: ["HoldMyIO"],
            path: "sdk-swift/Tests/HoldMyIOTests"
        ),
    ]
)
