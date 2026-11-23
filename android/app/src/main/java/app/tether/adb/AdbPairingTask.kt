package app.tether.adb

import android.content.Context
import android.util.Log
import app.tether.R
import app.tether.TetherApp
import java.util.concurrent.atomic.AtomicBoolean

/** Shared pairing operation for the floating entry field and the notification reply action. */
object AdbPairingTask {
    private val busy = AtomicBoolean(false)

    fun submit(ctx: Context, enteredCode: String, onComplete: (Result<String>) -> Unit) {
        val appContext = ctx.applicationContext
        val code = AdbPairingCode.normalize(enteredCode)
        if (code == null) {
            onComplete(Result.failure(IllegalArgumentException(appContext.getString(R.string.adb_invalid_code))))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            onComplete(Result.failure(IllegalStateException(appContext.getString(R.string.adb_pairing_busy))))
            return
        }
        runCatching { Adb.updateNotification(appContext, appContext.getString(R.string.adb_pairing), false) }
        Thread({
            val result = runCatching { (appContext as TetherApp).adbPairAndBootstrap(code) }
            if (result.isSuccess) {
                runCatching { Adb.updateNotification(appContext, appContext.getString(R.string.adb_paired), true) }
                runCatching { PairingOverlayService.finish(appContext) }
            } else {
                val error = result.exceptionOrNull()
                Log.w(Adb.TAG, "pairing failed: " + (error?.javaClass?.simpleName ?: "unknown"))
                runCatching { Adb.updateNotification(appContext, appContext.getString(R.string.adb_failed,
                    error?.message ?: error?.javaClass?.simpleName ?: "error"), false) }
            }
            busy.set(false)
            onComplete(result)
        }, "adb-pairing").start()
    }
}
