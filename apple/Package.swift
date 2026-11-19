// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PodcastKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "PodcastKit", targets: ["PodcastKit"])],
    targets: [
        .target(name: "PodcastKit"),
        .testTarget(name: "PodcastKitTests", dependencies: ["PodcastKit"]),
    ]
)
