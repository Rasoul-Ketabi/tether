package app.tether.helper

import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Read-only inventory for the legacy Exynos 7870 modem route. A PCM listing is not proof
 * that samples can be captured or injected. Never open /dev/snd or change mixer controls here.
 */
internal object ExynosDiagnostics {
    fun supported(device: String, hardware: String, board: String): Boolean =
        device.equals("j7velte", true) ||
            listOf(hardware, board).any { it.contains("7870", ignoreCase = true) }

    fun enabled(): Boolean = Process.myUid() == 0 &&
        supported(Build.DEVICE, Build.HARDWARE, Build.BOARD) &&
        Build.VERSION.SDK_INT in 30..32

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    fun describePcm(status: String?, params: String?): String {
        if (status == null) return "status unavailable"
        val state = status.lineSequence().firstOrNull { it.startsWith("state:") }
            ?.substringAfter(':')?.trim() ?: if (status.trim() == "closed") "closed" else "unknown"
        val fields = params.orEmpty().lineSequence().mapNotNull { line ->
            val key = line.substringBefore(':').trim()
            if (key in setOf("rate", "channels", "format", "period_size", "buffer_size"))
                "$key=" + line.substringAfter(':').trim()
            else null
        }.joinToString(", ")
        return "$state" + (if (fields.isNotEmpty()) "; $fields" else "") +
            (if (state.equals("closed", true)) "; not opened by this probe" else "; owned/configured by another stream; no direct access attempted")
    }

    private fun row(name: String, note: String) = JSONObject()
        .put("name", name).put("ok", false).put("note", note).put("diagnostic", true)

    fun rows(): JSONArray = JSONArray().apply {
        val root = "/proc/asound/card0"
        for (pcm in listOf("pcm2p", "pcm2c", "pcm6p", "pcm6c", "pcm3p", "pcm3c")) {
            val sub = "$root/$pcm/sub0"
            put(row("native:$pcm", describePcm(read("$sub/status"), read("$sub/hw_params"))))
        }
        val policy = read("/vendor/etc/audio_policy_configuration.xml")
        put(row("native:policy", if (policy == null) "policy unreadable" else
            "TELEPHONY_TX declared=" + policy.contains("AUDIO_DEVICE_OUT_TELEPHONY_TX") +
                "; INCALL_MUSIC declared=" + policy.contains("AUDIO_OUTPUT_FLAG_INCALL_MUSIC") +
                "; declarations are not proof of working routes"))
        put(row("native:tinyalsa", "library present=" +
            listOf("/system/lib/libtinyalsa.so", "/apex/com.android.vndk.v30/lib/libtinyalsa.so")
                .any { File(it).isFile } + "; read-only inventory, no native audio bridge"))
    }
}
