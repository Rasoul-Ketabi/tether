package app.tether.link

import android.util.Log
import app.tether.core.Bytes
import app.tether.core.Message
import app.tether.core.Proto
import app.tether.core.RecordReader
import app.tether.core.SecureSession
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import android.os.SystemClock
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
    /** A blocked TCP writer must NEVER delay BLE call events and GATT wake notifications. */
    private data class Pending(val channel: Int, val payload: ByteArray, val queuedAt: Long)
    private val outbound = LinkedBlockingQueue<Pending>(512)
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
                        outbound.clear()
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
        output.write(magic + ns + HybridWifiProof.mac(secret, "server", nc, ns))
        output.flush()
        val proof = ByteArray(32).also { input.readFully(it) }
        require(HybridWifiProof.verify(secret, "client", nc, ns, proof)) { "unauthenticated wifi peer" }

        val (serverToClient, clientToServer) = HybridWifiProof.keys(secret, nc, ns)
        val session = SecureSession(serverToClient, clientToServer)
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
        // Encryption and socket writes happen OFF the BLE writer queue. A stalled Wi-Fi
        // socket can only back up its own bounded queue; BLE always remains responsive.
        Thread({ writeLoop(sock, session, output) }, "hybrid-wifi-tx")
            .apply { isDaemon = true }.start()
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

    private fun writeLoop(sock: Socket, session: SecureSession, out: DataOutputStream) {
        try {
            while (!closed && client === sock && !sock.isClosed) {
                val item = outbound.poll(250, TimeUnit.MILLISECONDS) ?: continue
                // Old audio hurts live speech; never play it seconds later.
                if (item.channel == Proto.CH_AUDIO &&
                    SystemClock.elapsedRealtime() - item.queuedAt > 100) continue
                val bytes = session.seal(item.channel, item.payload).encoded()
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            Log.i(TAG, "hybrid Wi-Fi TX stopped: " + e.javaClass.simpleName)
            isReady = false
            runCatching { sock.close() }
        }
    }

    /** Fast, bounded enqueue. false means the existing BLE record must be sent. */
    fun send(channel: Int, payload: ByteArray): Boolean {
        if (!isReady || closed || payload.size > 60_000) return false
        // A pressure spike must not let seconds of stale Wi-Fi voice accumulate.
        if (channel == Proto.CH_AUDIO && outbound.size > 32) return false
        val accepted = outbound.offer(Pending(channel, payload, SystemClock.elapsedRealtime()))
        if (!accepted && channel == Proto.CH_CONTROL) {
            // A split sync across Wi-Fi and BLE can reorder pages. Close the fast
            // lane so iOS deliberately re-syncs from scratch over BLE instead.
            isReady = false
            runCatching { client?.close() }
        }
        return accepted
    }

    private fun shutdownSockets() {
        isReady = false
        outbound.clear()
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
