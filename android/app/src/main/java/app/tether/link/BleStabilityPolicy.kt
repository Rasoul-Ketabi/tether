package app.tether.link

/** Per-address short-session backoff. No forced link-layer parameter changes. */
internal class BleStabilityPolicy {
    private data class Entry(var start: Long = -1, var short: Int = 0, var last: Long = 0, var until: Long = 0)
    private val entries = LinkedHashMap<String, Entry>()
    @Synchronized fun started(address: String, label: String, now: Long) {
        if (label != "l2cap") return
        if (entries.size >= 64 && !entries.containsKey(address)) entries.remove(entries.keys.first())
        entries.getOrPut(address) { Entry() }.start = now
    }
    @Synchronized fun ended(address: String, label: String, now: Long): Boolean {
        if (label != "l2cap") return false
        val e = entries[address] ?: return false
        val start = e.start
        e.start = -1
        if (start < 0 || now - start >= 30_000) { e.short = 0; return false }
        if (now - e.last > 120_000) e.short = 0
        e.last = now
        if (++e.short < 3) return false
        e.short = 0; e.until = now + 300_000
        return true
    }
    @Synchronized fun preferGatt(address: String, now: Long) = (entries[address]?.until ?: 0) > now
}
