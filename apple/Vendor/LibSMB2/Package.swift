// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LibSMB2",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "PodcastLibSMB2", type: .dynamic, targets: ["PodcastLibSMB2"])],
    targets: [.target(name: "PodcastLibSMB2", path: "Upstream",
                      exclude: ["COPYING", "LICENCE-LGPL-2.1.txt", "NOTICE-libdcerpc.txt"],
                      sources: ["lib"], publicHeadersPath: "module",
                      cSettings: [.headerSearchPath("include"), .headerSearchPath("include/apple"),
                                  .headerSearchPath("include/smb2"), .headerSearchPath("Upstream/include"),
                                  .headerSearchPath("Upstream/include/apple"), .headerSearchPath("Upstream/include/smb2"),
                                  .define("HAVE_CONFIG_H", to: "1"),
                                  .define("_U_", to: "__attribute__((unused))")])]
)
