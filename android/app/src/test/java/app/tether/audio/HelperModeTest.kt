package app.tether.audio

import org.junit.Assert.*
import org.junit.Test

class HelperModeTest {
    @Test fun uidMustMatchSelectedMode() {
        assertTrue(HelperMode.matches(true, 0))
        assertFalse(HelperMode.matches(true, 2000))
        assertTrue(HelperMode.matches(false, 2000))
        assertFalse(HelperMode.matches(false, 0))
        assertFalse(HelperMode.matches(false, -1))
        assertFalse(HelperMode.matches(true, -1))
    }

    @Test fun rootCommandUsesStdinModeAndQuotesApk() {
        val cmd = HelperMode.rootCommand("/data/app/a'b/base.apk", 47461)
        assertTrue(cmd.contains("id -u"))
        assertTrue(cmd.contains("a'\"'\"'b"))
        assertTrue(cmd.endsWith("serve-stdin 47461"))
        assertFalse(cmd.contains("/data/local/tmp"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPortRejected() { HelperMode.rootCommand("/data/app/base.apk", 0) }
}
