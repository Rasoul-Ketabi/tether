// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TetherCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "TetherCore", targets: ["TetherCore"]),
        .executable(name: "tether-vectors", targets: ["tether-vectors"]),
        .executable(name: "tether-central", targets: ["tether-central"]),
    ],
    targets: [
        .target(name: "TetherCore"),
        .executableTarget(name: "tether-vectors", dependencies: ["TetherCore"]),
        // Mac test central; the embedded Info.plist carries the Bluetooth usage string TCC requires.
        .executableTarget(name: "tether-central", dependencies: ["TetherCore"], exclude: ["Info.plist"],
                          linkerSettings: [.unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                                                         "-Xlinker", "__info_plist", "-Xlinker", "Sources/tether-central/Info.plist"])]),
        .testTarget(name: "TetherCoreTests", dependencies: ["TetherCore"],
                    resources: [.copy("vectors.json")]),
    ]
)
