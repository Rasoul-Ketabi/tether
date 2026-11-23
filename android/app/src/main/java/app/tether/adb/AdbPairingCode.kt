package app.tether.adb

/** Validate one complete ADB pairing code. No code is written to persistent storage or logs. */
object AdbPairingCode {
    fun normalize(input: String): String? {
        if (input.length != 6) return null
        val digits = CharArray(6)
        for (i in input.indices) {
            val ch = input[i]
            digits[i] = when (ch) {
                in '0'..'9' -> ch
                in '\u06f0'..'\u06f9' -> '0' + (ch - '\u06f0') // Persian digits
                in '\u0660'..'\u0669' -> '0' + (ch - '\u0660') // Arabic-Indic digits
                else -> return null
            }
        }
        return String(digits)
    }
}
