package app.tether.core

/** Small decisions of the link, kept pure so they are unit-tested (see LinkPolicyTest). */
object LinkPolicy {
    /**
     * Whether a GATT session must end when an ACL of the same central changes. iOS runs GATT over whichever
     * transport it has (classic Bluetooth while the phones are paired for tethering, LE otherwise) and moves it
     * when the other one appears or goes: the session on the old bearer is then dead even if no GATT disconnect
     * arrives for it.
     */
    fun gattPipeEnds(pipeOnLe: Boolean, aclIsLe: Boolean, aclUp: Boolean): Boolean =
        if (aclIsLe) (aclUp && !pipeOnLe) || (!aclUp && pipeOnLe) else (!aclUp && !pipeOnLe)
}

/**
 * The token a pair-mode HELLO must prove. The open pairing code's; or, for [windowMs] after a pairing completed,
 * that pairing's token, for the same central only (it may not have received our FIN and says hello in pair mode
 * again; refusing it made it forget a pairing that had succeeded).
 */
class PairingTokens(private val windowMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var last: Triple<ByteArray, ByteArray, Long>? = null

    @Synchronized fun completed(token: ByteArray, centralKey: ByteArray) { last = Triple(token, centralKey, now() + windowMs) }

    @Synchronized fun tokenFor(centralKey: ByteArray, open: ByteArray?): ByteArray? =
        open ?: last?.takeIf { it.second.contentEquals(centralKey) && now() < it.third }?.first
}
