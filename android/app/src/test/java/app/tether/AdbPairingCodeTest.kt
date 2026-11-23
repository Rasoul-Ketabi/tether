package app.tether

import app.tether.adb.AdbPairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbPairingCodeTest {
    @Test fun acceptsExactlySixAsciiDigits() {
        assertEquals("012345", AdbPairingCode.normalize("012345"))
        assertEquals("000000", AdbPairingCode.normalize("000000"))
        assertNull(AdbPairingCode.normalize("12345"))
        assertNull(AdbPairingCode.normalize("1234567"))
    }

    @Test fun normalizesPersianAndArabicIndicDigits() {
        assertEquals("123456", AdbPairingCode.normalize("۱۲۳۴۵۶"))
        assertEquals("123456", AdbPairingCode.normalize("١٢٣٤٥٦"))
        assertEquals("123456", AdbPairingCode.normalize("1۲٣4۵٦"))
    }

    @Test fun refusesNonDigitsAndWhitespaceRatherThanSilentlyFilteringThem() {
        for (value in listOf("123 56", "123x56", "12345\n", " 123456", "123456 ", "１２３４５６", "")) {
            assertNull(value, AdbPairingCode.normalize(value))
        }
    }
}
