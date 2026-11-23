#if canImport(Network) && canImport(CoreBluetooth)
import XCTest
import CryptoKit
@testable import TetherCore

final class HybridWifiTests: XCTestCase {
    private let secret = Data((0..<32).map { UInt8($0) })
    private let nc = Data((16..<32).map { UInt8($0) })
    private let ns = Data((32..<48).map { UInt8($0) })

    func testMutualHmacAndHkdfMatchAndroid() throws {
        let key = SymmetricKey(data: secret)
        func code(_ role: String) -> String {
            let input = Data(("tether/hybrid-wifi/1/" + role).utf8) + nc + ns
            return Data(HMAC<SHA256>.authenticationCode(for: input, using: key)).hex
        }
        XCTAssertEqual(code("server"), "814f9c2436dad5f2eff63505a7a9a578440607a3f532d21ee2c7ccc1867404c8")
        XCTAssertEqual(code("client"), "9b4b311a7d1ae0312f5aef57678ddd31056e372829d97f7fe3ccda8da0fc2032")
        let derived = HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: nc + ns,
                                             info: Data("tether/hybrid-wifi/1".utf8), outputByteCount: 64)
        let material = derived.withUnsafeBytes { Data($0) }
        let androidToIos = Data(material[0..<32])
        let iosToAndroid = Data(material[32..<64])
        XCTAssertEqual(androidToIos.hex, "766b8fa5693cfd7550f70ef7e8960c2fa2bf5329bafee734783bc9714441a438")
        XCTAssertEqual(iosToAndroid.hex, "38fdc2543aad2a87a64a7d36a8a57615d77046b40a5b61458fce6171eacdd589")

        let sender = SecureSession(sendKey: androidToIos, recvKey: iosToAndroid)
        let receiver = SecureSession(sendKey: iosToAndroid, recvKey: androidToIos)
        let pcm = Data([1, 0, 1, 2, 3, 4])
        let sealed = try sender.seal(channel: Proto.channelAudio, pcm)
        let (channel, received) = try receiver.open(sealed)
        XCTAssertEqual(channel, Proto.channelAudio)
        XCTAssertEqual(received, pcm)
        XCTAssertThrowsError(try receiver.open(sealed))  // replay must fail
        let uplink = try receiver.seal(channel: Proto.channelAudio, pcm)
        XCTAssertEqual(try sender.open(uplink).1, pcm)
    }

    func testRejectsMalformedOfferBeforeConnecting() {
        let queue = DispatchQueue(label: "hybrid-test")
        let key = secret.base64URL()
        XCTAssertNil(HybridWifiClient(offer: .init(v: 99, host: "192.168.1.2", port: 9999, token: key), queue: queue))
        XCTAssertNil(HybridWifiClient(offer: .init(v: 1, host: "192.168.1.2", port: 0, token: key), queue: queue))
        XCTAssertNil(HybridWifiClient(offer: .init(v: 1, host: "192.168.1.2", port: 9999, token: "bad"), queue: queue))
        XCTAssertNotNil(HybridWifiClient(offer: .init(v: 1, host: "192.168.1.2", port: 9999, token: key), queue: queue))
    }
}
#endif
