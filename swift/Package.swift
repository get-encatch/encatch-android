// swift-tools-version:5.9
import PackageDescription

/// Wraps the Kotlin/Native `EncatchCore.xcframework` (built from `../core`) as a binary target,
/// with `Encatch` providing an idiomatic Swift façade + native SwiftUI/UIKit form UI on top.
///
/// Local development: run `./gradlew :core:assembleEncatchCoreDebugXCFramework` in the repo root
/// before resolving/building this package — the binary target below points at that build output,
/// which isn't committed to git (see `core/build.gradle.kts` for the XCFramework task names).
let package = Package(
    name: "Encatch",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "Encatch", targets: ["Encatch"]),
    ],
    targets: [
        .binaryTarget(
            name: "EncatchCore",
            path: "../core/build/XCFrameworks/debug/EncatchCore.xcframework",
        ),
        .target(
            name: "Encatch",
            dependencies: ["EncatchCore"],
            path: "Sources/Encatch",
        ),
        .testTarget(
            name: "EncatchTests",
            dependencies: ["Encatch"],
            path: "Tests/EncatchTests",
        ),
    ],
)
