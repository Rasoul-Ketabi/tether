package app.tether.audio

/** Local selection is verified against the authenticated HELLO, not against a settings label. */
internal object HelperMode {
    fun matches(root: Boolean, uid: Int): Boolean = uid == (if (root) 0 else 2000)

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /** The token is read by the helper from stdin. Never write privileged logs into the shell-writable tmp directory. */
    fun rootCommand(apk: String, port: Int): String {
        require(port in 1..65535)
        return "PATH=/system/bin:/system/xbin; export PATH; " +
            "test \"\$(id -u)\" = 0 || exit 1; " +
            "CLASSPATH=" + quote(apk) +
            " exec /system/bin/app_process / app.tether.helper.HelperMain serve-stdin $port"
    }
}
