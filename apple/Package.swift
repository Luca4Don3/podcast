// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PodcastKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "PodcastKit", targets: ["PodcastKit"])],
    dependencies: [.package(path: "Vendor/LibSMB2")],
    targets: [
        .target(name: "PodcastNativeCache", path: "NativeCache"),
        .target(name: "PodcastKit", dependencies: [
            "PodcastNativeCache",
            .product(name: "PodcastLibSMB2", package: "LibSMB2", condition: .when(platforms: [.iOS, .macOS])),
        ]),
        .testTarget(name: "PodcastKitTests", dependencies: ["PodcastKit"]),
    ],
    cxxLanguageStandard: .cxx17
)
