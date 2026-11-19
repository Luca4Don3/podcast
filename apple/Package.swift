// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PodcastKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "PodcastKit", targets: ["PodcastKit"])],
    dependencies: [.package(path: "Vendor/LibSMB2")],
    targets: [
        .target(name: "PodcastKit", dependencies: [
            .product(name: "PodcastLibSMB2", package: "LibSMB2", condition: .when(platforms: [.iOS, .macOS])),
        ]),
        .testTarget(name: "PodcastKitTests", dependencies: ["PodcastKit"]),
    ]
)
