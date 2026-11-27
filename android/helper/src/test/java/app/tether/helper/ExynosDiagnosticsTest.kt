package app.tether.helper

import org.junit.Assert.*
import org.junit.Test

class ExynosDiagnosticsTest {
    @Test fun recognizesTargetWithoutMatchingEverySamsung() {
        assertTrue(ExynosDiagnostics.supported("j7velte", "", ""))
        assertTrue(ExynosDiagnostics.supported("other", "samsungexynos7870", ""))
        assertFalse(ExynosDiagnostics.supported("a73xq", "qcom", ""))
    }

    @Test fun describesOccupiedPcmWithoutLeakingOwnerPid() {
        val note = ExynosDiagnostics.describePcm(
            "state: RUNNING\nowner_pid : 7940\n",
            "format: S16_LE\nchannels: 2\nrate: 8000 (8000/1)\nperiod_size: 1024\n")
        assertTrue(note.contains("RUNNING"))
        assertTrue(note.contains("rate=8000"))
        assertTrue(note.contains("channels=2"))
        assertTrue(note.contains("no direct access attempted"))
        assertFalse(note.contains("7940"))
    }

    @Test fun closedAndUnavailableAreNotSuccess() {
        assertTrue(ExynosDiagnostics.describePcm("closed", "closed").startsWith("closed"))
        assertEquals("status unavailable", ExynosDiagnostics.describePcm(null, null))
    }
}
