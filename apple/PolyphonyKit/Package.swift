// swift-tools-version: 6.0
import PackageDescription

// PolyphonyKit: the Apple apps' Swift layer (see apple/README.md). No AppKit/UIKit and no third-party
// dependencies, so it builds and tests on Linux too (apple/PolyphonyKit/scripts/ci.sh).
let package = Package(
    name: "PolyphonyKit",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "PolyphonyKit", targets: ["PolyphonyKit"]),
    ],
    targets: [
        .target(name: "PolyphonyKit"),
        .testTarget(
            name: "PolyphonyKitTests",
            dependencies: ["PolyphonyKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
