// swift-tools-version:5.9
import PackageDescription

/// A genuinely native iOS SDK: own networking (`URLSession`), own storage (`UserDefaults`), own
/// session/ping/retry-queue, own appearance/theme resolution, own WebView JS-bridge protocol. Zero
/// dependency on `:core` (Kotlin/Native) or any `.xcframework` binary — deliberately, so Swift
/// consumers get a small, debuggable, pure-Swift package with no embedded Kotlin runtime.
let package = Package(
    name: "Encatch",
    // .macOS is included alongside the SDK's real target (.iOS) purely so `swift build`/`swift test`
    // can run directly on a developer Mac without an iOS destination — Swift concurrency (`Task`,
    // `Task.sleep`) requires a minimum deployment target of macOS 10.15/iOS 13, and SwiftPM's plain
    // `swift build` compiles for the host platform by default.
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "Encatch", targets: ["Encatch"]),
    ],
    targets: [
        .target(
            name: "Encatch",
            path: "Sources/Encatch",
        ),
        .testTarget(
            name: "EncatchTests",
            dependencies: ["Encatch"],
            path: "Tests/EncatchTests",
        ),
    ],
)
