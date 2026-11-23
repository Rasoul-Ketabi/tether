#if canImport(Network) && canImport(CoreBluetooth)
import Foundation
import Network
import CryptoKit

/// The optional fast lane is subordinate to a live, authenticated BLE TetherLink.
/// Calls, pairing, wake, urgent control and failover remain on Bluetooth.
/// The token is obtained exclusively from the BLE encrypted control channel.
struct HybridWifiOffer: Decodable {
    let v: Int
    let host: String
    let port: Int
    let token: String
}

final class HybridWifiClient {
    private let queue: DispatchQueue
    private let connection: NWConnection
    private let secret: Data
    private let clientNonce: Data
    private var pending = Data()
    private var reader = RecordReader()
    private var secure: SecureSession?
    private var awaitingServer = true
    private(set) var isReady = false
    private var stopped = false

    var onControl: ((Data) -> Void)?
    var onAudio: ((Data) -> Void)?
    var onState: ((Bool) -> Void)?

    init?(offer: HybridWifiOffer, queue: DispatchQueue) {
        guard offer.v == 1, offer.port > 0, offer.port <= 65535,
              let key = Data(base64URL: offer.token), key.count == 32,
              !offer.host.isEmpty, offer.host.count < 64,
              let port = NWEndpoint.Port(rawValue: UInt16(offer.port)) else { return nil }
        // An authenticated Android host only offers an RFC1918 address. Reject
        // unexpected public/hostname destinations, never route sensitive traffic outside LAN.
        let parts = offer.host.split(separator: ".", omittingEmptySubsequences: false)
        let octets = parts.compactMap { UInt8($0) }
        guard octets.count == 4,
              octets[0] == 10 ||
              (octets[0] == 172 && (16...31).contains(octets[1])) ||
              (octets[0] == 192 && octets[1] == 168) else { return nil }
        self.queue = queue
        self.secret = key
        self.clientNonce = randomBytes(16)
        let params = NWParameters.tcp
        // Never silently route a local Wi-Fi lane over the cellular network.
        params.requiredInterfaceType = .wifi
        self.connection = NWConnection(host: NWEndpoint.Host(offer.host), port: port, using: params)
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self, !self.stopped else { return }
            switch state {
            case .ready:
                self.connection.send(content: Data([0x54, 0x48, 0x57, 0x31]) + self.clientNonce,
                                     completion: .contentProcessed { [weak self] error in
                    if error != nil { self?.stop() }
                })
            case .waiting, .failed, .cancelled:
                // A Wi-Fi path that becomes unavailable must not remain "ready"
                // while silently queuing audio on an unusable socket.
                self.stop()
            default: break
            }
        }
        connection.start(queue: queue)
        receive()
        queue.asyncAfter(deadline: .now() + 8) { [weak self] in
            guard let self, !self.isReady else { return }
            self.stop()  // no authenticated ready record in time: keep BLE
        }
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, finished, error in
            guard let self, !self.stopped else { return }
            if error != nil || finished { self.stop(); return }
            if let data, !data.isEmpty {
                self.pending.append(data)
                if self.pending.count > 256 * 1024 { self.stop(); return }
                do { try self.processInput() } catch { self.stop(); return }
            }
            self.receive()
        }
    }

    private func processInput() throws {
        if awaitingServer {
            guard pending.count >= 52 else { return }
            let hello = Data(pending.prefix(52))
            pending.removeFirst(52)
            guard hello.prefix(4) == Data([0x54, 0x48, 0x57, 0x31]) else { throw HybridError.authentication }
            let serverNonce = Data(hello.dropFirst(4).prefix(16))
            let proof = Data(hello.suffix(32))
            let serverInput = Data("tether/hybrid-wifi/1/server".utf8) + clientNonce + serverNonce
            let key = SymmetricKey(data: secret)
            guard HMAC<SHA256>.isValidAuthenticationCode(proof, authenticating: serverInput, using: key)
            else { throw HybridError.authentication }
            let clientInput = Data("tether/hybrid-wifi/1/client".utf8) + clientNonce + serverNonce
            let response = Data(HMAC<SHA256>.authenticationCode(for: clientInput, using: key))
            let derived = HKDF<SHA256>.deriveKey(inputKeyMaterial: key,
                salt: clientNonce + serverNonce, info: Data("tether/hybrid-wifi/1".utf8), outputByteCount: 64)
            let bytes = derived.withUnsafeBytes { Data($0) }
            secure = SecureSession(sendKey: Data(bytes[32..<64]), recvKey: Data(bytes[0..<32]))
            awaitingServer = false
            connection.send(content: response, completion: .contentProcessed { [weak self] error in
                if error != nil { self?.stop() }
            })
        }

        guard !awaitingServer, let secure else { return }
        reader.append(pending)
        pending.removeAll(keepingCapacity: true)
        if reader.pendingBytes > 128 * 1024 { throw HybridError.framing }
        while let record = try reader.next() {
            let (channel, payload) = try secure.open(record)
            if !isReady {
                guard channel == Proto.channelControl, let h = try? Control.header(payload), h.t == "wifi.ready"
                else { throw HybridError.authentication }
                isReady = true
                onState?(true)
                continue
            }
            switch channel {
            case Proto.channelControl: onControl?(payload)
            case Proto.channelAudio: onAudio?(payload)
            default: throw HybridError.framing
            }
        }
    }

    /// Returns false if the existing BLE channel should carry the frame instead.
    func sendAudio(_ payload: Data) -> Bool {
        guard isReady, !stopped, let secure, payload.count <= 4096 else { return false }
        guard let record = try? secure.seal(channel: Proto.channelAudio, payload) else { return false }
        connection.send(content: record.encoded(), completion: .contentProcessed { [weak self] error in
            if error != nil { self?.stop() }
        })
        return true
    }

    func stop() {
        guard !stopped else { return }
        stopped = true
        isReady = false
        secure = nil
        connection.stateUpdateHandler = nil
        connection.cancel()
        onState?(false)
    }

    private enum HybridError: Error { case authentication, framing }
}
#endif
