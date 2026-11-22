// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "oripane-host",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "oripane-host", targets: ["oripane-host"]),
    ],
    targets: [
        .target(name: "CVirtualDisplay", linkerSettings: [.linkedFramework("CoreGraphics")]),
        .target(name: "OripaneCore"),
        .executableTarget(
            name: "oripane-host",
            dependencies: ["OripaneCore", "CVirtualDisplay"],
            linkerSettings: [
                .linkedFramework("ScreenCaptureKit"),
                .linkedFramework("VideoToolbox"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("Network"),
            ]
        ),
        .testTarget(name: "OripaneCoreTests", dependencies: ["OripaneCore"]),
    ],
    // Swift 6 strict concurrency fights the callback-queue APIs (SCStream, VTCompressionSession,
    // Network.framework) this host is built from; each component owns one serial queue instead.
    swiftLanguageModes: [.v5]
)
