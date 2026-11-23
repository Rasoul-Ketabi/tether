package app.tether.link

import android.util.Log
import app.tether.core.Bytes
import app.tether.core.Message
import app.tether.core.P256
import app.tether.core.Proto
import app.tether.core.RecordReader
import app.tether.core.SecureSession
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Optional, authenticated high-throughput LAN lane for ONE existing BLE session.
 *
 * The 32-byte one-time secret is announced ONLY inside the established, encrypted BLE
 * session. The TCP listener is bound to a private Wi-Fi/hotspot address, never to
 * 0.0.0.0 or a cellular/VPN interface. This lane is never a separate Tether client:
 * CallKit, call ownership, pairing, BLE wake and urgent control remain on BLE.
 *
 * Each TCP connection uses fresh random nonces, a mutual HMAC-SHA256 challenge and
 * fresh AES-256-GCM send/receive keys derived by HKDF. The existing SecureSession
 * record framing and strict anti-replay counters are reused without sharing the BLE
 * session's keys or counters.
 */
internal class HybridWifiServer(private val onAudio: (ByteArray) -> Unit) {
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val lock = Any()
    private val writeLock = Any()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var stream: DataOutputStream? = null
    @Volatile private var crypto: SecureSession? = null
    @Volatile var isReady = false; private set
    @Volatile private var closed = false
    private var boundIp: Inet4Address? = null

    fun offer(): JSONObject? {
        synchronized(lock) {
            if (closed) return null
            val addr = availableAddress() ?: return null
            if (listener == null || listener?.isClosed == true || addr != boundIp) {
                shutdownSockets()
                try {
                    val server = ServerSocket(0, 2, addr).apply { reuseAddress = true }
                    boundIp = addr
                    listener = server
                    Thread({ acceptLoop(server) }, "hybrid-wifi-accept").apply { isDaemon = true }.start()
                } catch (e: Exception) {
                    Log.i(TAG, "hybrid wifi listener unavailable: " + e.message)
                    return null
                }
            }
            return JSONObject()
                .put("v", 1)
                .put("host", addr.hostAddress)
                .put("port", listener!!.localPort)
                .put("token", Bytes.b64url(secret))
        }
    }

    /** Only accept private IPv4 addresses on actual Wi-Fi and hotspot interfaces. */
    private fun availableAddress(): Inet4Address? {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrNull() ?: return null
        return interfaces.filter { nic ->
            val name = nic.name.lowercase()
            (name.startsWith("wlan") || name.startsWith("swlan") ||
             name.startsWith("ap") || name.startsWith("wifi")) &&
                runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false)
        }.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }.firstNotNullOfOrNull { nic ->
            Collections.list(nic.inetAddresses).filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!closed && !server.isClosed) {
            val incoming = try { server.accept() } catch (_: Exception) { break }
            if (client != null) {
                runCatching { incoming.close() }
                continue
            }
            try {
                handle(incoming)
            } catch (e: Exception) {
                Log.i(TAG, "hybrid wifi connection ended: " + e.javaClass.simpleName)
            } finally {
                synchronized(writeLock) {
                    if (client === incoming) {
                        crypto = null
                        stream = null
                        isReady = false
                        client = null
                    }
                }
                runCatching { incoming.close() }
            }
        }
    }

    private fun handle(sock: Socket) {
        // Before authentication a peer can consume no more than three seconds.
        sock.soTimeout = 3_000
        sock.tcpNoDelay = true
        val input = DataInputStream(sock.getInputStream())
        val output = DataOutputStream(sock.getOutputStream())
        val magic = byteArrayOf(0x54, 0x48, 0x57, 0x31) // THW1
        val head = ByteArray(20).also { input.readFully(it) }
        require(head.copyOfRange(0, 4).contentEquals(magic)) { "bad wifi hello" }
        val nc = head.copyOfRange(4, 20)
        val ns = ByteArray(16).also { SecureRandom().nextBytes(it) }
        output.write(magic + ns + mac("server", nc, ns))
        output.flush()
        val proof = ByteArray(32).also { input.readFully(it) }
        require(MessageDigest.isEqual(proof, mac("client", nc, ns))) { "unauthenticated wifi peer" }

        val okm = P256.hkdf(nc + ns, secret, "tether/hybrid-wifi/1".toByteArray(Charsets.UTF_8), 64)
        val session = SecureSession(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
        synchronized(writeLock) {
            if (closed) return
            client = sock
            stream = output
            crypto = session
            // Authentication is not complete at the iPhone until it verifies this sealed record.
            output.write(session.seal(Proto.CH_CONTROL, Message("wifi.ready").toJson()).encoded())
            output.flush()
            isReady = true
        }
        sock.soTimeout = 0
        val reader = RecordReader()
        val buf = ByteArray(8192)
        while (!closed && client === sock) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            reader.append(buf, 0, n)
            require(reader.pendingBytes <= 128 * 1024) { "wifi buffer limit" }
            while (true) {
                val record = reader.next() ?: break
                val (channel, data) = session.open(record)
                // The client deliberately sends ONLY call audio via Wi-Fi. All privileged
                // call/SMS/net commands continue over the verified BLE control session.
                if (channel == Proto.CH_AUDIO && data.size in 3..4096) onAudio(data)
                else throw IllegalArgumentException("unsupported wifi inbound channel")
            }
        }
    }

    private fun mac(role: String, nc: ByteArray, ns: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(("tether/hybrid-wifi/1/" + role).toByteArray(Charsets.UTF_8) + nc + ns)
        }

    /** false means that the original BLE record should be sent instead. */
    fun send(channel: Int, payload: ByteArray): Boolean = synchronized(writeLock) {
        val s = crypto ?: return@synchronized false
        val out = stream ?: return@synchronized false
        if (!isReady || payload.size > 60_000) return@synchronized false
        try {
            out.write(s.seal(channel, payload).encoded())
            out.flush()
            true
        } catch (e: Exception) {
            Log.i(TAG, "wifi lane failed, reverting to BLE: " + e.message)
            isReady = false
            runCatching { client?.close() }
            false
        }
    }

    private fun shutdownSockets() {
        isReady = false
        runCatching { client?.close() }
        runCatching { listener?.close() }
        client = null
        stream = null
        crypto = null
        listener = null
    }

    fun close() {
        synchronized(lock) {
            closed = true
            shutdownSockets()
            secret.fill(0)
        }
    }

    companion object { private const val TAG = "Tether" }
}
