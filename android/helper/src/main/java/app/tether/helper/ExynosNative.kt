package app.tether.helper

import android.media.AudioManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An explicit self-test only. Never selects a live call audio backend from a PCM capability result. */
internal object ExynosNative {
    private external fun nativeProbe(): String
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
        if (CallAudio.audioMode() != AudioManager.MODE_IN_CALL) {
            put(row("native:ndk", "not a cellular in-call mode; native probe skipped"))
            return@apply
        }
        val result = loaded.mapCatching { nativeProbe() }
        result.fold(onSuccess = { output ->
            for (line in output.lineSequence().filter { it.isNotBlank() }.take(32)) {
                put(row("native:ndk", line))
            }
            put(row("native:bridge", "Not enabled: independent modem RX/TX sample routes are not proven. No mixer writes, stream start or audio transfer."))
        }, onFailure = { e ->
            put(row("native:ndk", "Native probe unavailable: " + CallAudio.describe(e)))
        })
    }
}
