package app.tether.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Wireless debugging has no public Android SDK deep-link action. Some OEM Settings builds
 * export the WirelessDebuggingActivity alias; try that alias only as a best-effort shortcut.
 *
 * A protected or missing Activity must never block setup: fall back to the official
 * ACTION_APPLICATION_DEVELOPMENT_SETTINGS entry point.
 */
internal object WirelessDebuggingSettings {
    internal data class Target(val packageName: String, val activityName: String)

    internal fun directTargets(manufacturer: String): List<Target> {
        val aospAlias = "com.android.settings.Settings" + "$" + "WirelessDebuggingActivity"
        val targets = mutableListOf(Target("com.android.settings", aospAlias))
        val m = manufacturer.trim().lowercase(java.util.Locale.ROOT)
        if (m in setOf("oppo", "realme", "oneplus", "oplus")) {
            targets += Target("com.oplus.settings",
                "com.oplus.settings.Settings" + "$" + "WirelessDebuggingActivity")
        }
        if (m in setOf("vivo", "iqoo")) {
            targets += Target("com.vivo.settings",
                "com.vivo.settings.Settings" + "$" + "WirelessDebuggingActivity")
        }
        return targets
    }

    fun open(activity: Activity): Boolean {
        // Explicit targets only; no arbitrary user-supplied intents, fragments, or shell calls.
        for (target in directTargets(Build.MANUFACTURER)) {
            try {
                activity.startActivity(Intent().setClassName(target.packageName, target.activityName))
                return true
            } catch (_: SecurityException) {
                // The OEM protects its internal Settings activity.
            } catch (_: ActivityNotFoundException) {
                // Activity aliases differ between Android versions and manufacturers.
            } catch (_: IllegalArgumentException) {
                // Unexpected vendor Settings configuration.
            }
        }
        // Public and documented Android fallback.
        return try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
