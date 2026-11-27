package app.tether.helper

import android.media.AudioManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** An explicit self-test only. Never selects a live call audio backend from a PCM capability result. */
internal object ExynosNative {
    private external fun nativeProbe(): String
    private external fun nativeStart(rate: Int): String?
    private external fun nativeRead(out: ShortArray): Int
    private external fun nativeWrite(input: ShortArray, n: Int): Int
    private external fun nativeRouteIntact(): Boolean
    private external fun nativeClose()
    private val lock = ReentrantReadWriteLock()
    private var current: Session? = null

    /** Both directions share one transaction, restored when either direction is stopped. */
    class Session internal constructor(private val rate: Int) {
        @Volatile private var halted = false
        @Volatile var failure: String? = null
            private set
        private val frame = rate / 50
        fun intact(): Boolean = lock.read {
            !halted && current === this && nativeRouteIntact()
        }
        fun halt() { halted = true }
        fun close() {
            halted = true
            lock.write {
                if (current === this) {
                    try { nativeClose() } finally { current = null }
                }
            }
        }
        private fun failed(message: String): Int { failure = message; halted = true; return -1 }
        val capture: Capture = object : Capture {
            override val name = "exynos_ap"
            override fun read(buf: ShortArray): Int = lock.read pcmRead@{
                if (halted || current !== this@Session) return@pcmRead -1
                val n = nativeRead(buf)
                if (n < 0) failed("Native capture errno=" + (-n)) else n
            }
            override fun bufferFrames() = rate * 80 / 1000
            override fun halt() = this@Session.halt()
            override fun stop() = this@Session.close()
        }
        val inject: Inject = object : Inject {
            override val name = "exynos_ap"
            override fun write(buf: ShortArray, n: Int) {
                lock.read {
                    check(!halted && current === this@Session) { "Native route stopped" }
                    require(n in 1..buf.size)
                    var at = 0
                    while (at < n) {
                        val count = minOf(frame, n - at)
                        val part = if (at == 0 && count == n) buf else buf.copyOfRange(at, at + count)
                        val rc = nativeWrite(part, count)
                        if (rc != count) { failed("Native playback errno=" + (-rc)); error(failure ?: "Native write failed") }
                        at += count
                    }
                }
            }
            // Hardware route is a hypothesis; never claim TYPE_TELEPHONY or verified uplink.
            override fun routedTo() = "Exynos AP mixer (experimental, verify by listening)"
            override fun bufferFrames() = rate * 80 / 1000
            override fun latencyMs() = -1
            override fun halt() = this@Session.halt()
            override fun stop() = this@Session.close()
        }
    }

    fun open(rate: Int): Session = lock.write {
        check(ExynosDiagnostics.enabled() && Build.DEVICE.equals("j7velte", true)) { "J7 Core Root required" }
        check(CallAudio.audioMode() == AudioManager.MODE_IN_CALL) { "Cellular call required" }
        check(current == null) { "Native route already active" }
        loaded.getOrThrow()
        val message = nativeStart(rate)
        check(message == null) { message ?: "Native setup failed" }
        Session(rate).also { current = it }
    }

    fun failureOrRouteChanged(): String? = lock.read {
        val s = current ?: return@read null
        s.failure ?: if (!s.intact()) "Native mixer route changed or stopped" else null
    }

    private val loaded: Result<Unit> by lazy {
        runCatching {
            check(Process.myUid() == 0 && Build.DEVICE.equals("j7velte", true))
            val apk = File(System.getProperty("java.class.path") ?: error("no APK")).canonicalFile
            check(apk.path.startsWith("/data/app/") && apk.name == "base.apk")
            val lib = File(apk.parentFile, "lib/arm/libtether_exynos.so").canonicalFile
            if (lib.isFile && lib.path.startsWith(apk.parentFile!!.path + "/lib/arm/")) {
                System.load(lib.path)
            } else {
                // Some installations map native libraries directly from the verified APK.
                System.load(apk.path + "!/lib/armeabi-v7a/libtether_exynos.so")
            }
        }
    }

    private fun row(name: String, note: String) = JSONObject()
        .put("name", name).put("ok", false).put("diagnostic", true).put("note", note)

    fun rows(): JSONArray = JSONArray().apply {
        if (!ExynosDiagnostics.enabled() || !Build.DEVICE.equals("j7velte", true)) return@apply
        if (lock.read { current != null }) {
            put(row("native:ndk", "Live experimental bridge active; capability probe skipped"))
            return@apply
        }
        if (CallAudio.audioMode() != AudioManager.MODE_IN_CALL) {
            put(row("native:ndk", "not a cellular in-call mode; native probe skipped"))
            return@apply
        }
        val result = loaded.mapCatching { nativeProbe() }
        result.fold(onSuccess = { output ->
            for (line in output.lineSequence().filter { it.isNotBlank() }.take(32)) {
                put(row("native:ndk", line))
            }
            put(row("native:bridge", "Capability test only. Enable the separate experimental Exynos AP bridge in Android settings for live samples; confirm both directions by listening."))
        }, onFailure = { e ->
            put(row("native:ndk", "Native probe unavailable: " + CallAudio.describe(e)))
        })
    }
}
