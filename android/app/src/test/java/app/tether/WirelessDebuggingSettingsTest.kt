package app.tether.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessDebuggingSettingsTest {
    @Test fun commonPlatformSettingsShortcutIsFirst() {
        val targets = WirelessDebuggingSettings.directTargets("samsung")
        assertEquals(1, targets.size)
        assertEquals("com.android.settings", targets[0].packageName)
        assertEquals("com.android.settings.Settings" + "$" + "WirelessDebuggingActivity", targets[0].activityName)
    }

    @Test fun oemSpecificAliasesAreOnlyAttemptedForKnownVendors() {
        val oppo = WirelessDebuggingSettings.directTargets("OPPO")
        assertEquals(2, oppo.size)
        assertEquals("com.oplus.settings", oppo.last().packageName)
        val vivo = WirelessDebuggingSettings.directTargets("vivo")
        assertEquals("com.vivo.settings", vivo.last().packageName)
        assertTrue(WirelessDebuggingSettings.directTargets("other").none { it.packageName != "com.android.settings" })
    }
}
