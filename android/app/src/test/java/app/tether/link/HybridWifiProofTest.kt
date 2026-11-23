package app.tether.link

import app.tether.core.Bytes
import app.tether.core.Proto
import app.tether.core.SecureSession
import app.tether.core.SessionException
import app.tether.core.Record
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared vectors: same labels/salt/key orientation must be implemented on Swift and Kotlin. */
class HybridWifiProofTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val nc = ByteArray(16) { (it + 16).toByte() }
    private val ns = ByteArray(16) { (it + 32).toByte() }

    @Test fun serverAndClientHmacVectors() {
        assertEquals("814f9c2436dad5f2eff63505a7a9a578440607a3f532d21ee2c7ccc1867404c8",
            Bytes.hex(HybridWifiProof.mac(secret, "server", nc, ns)))
        assertEquals("9b4b311a7d1ae0312f5aef57678ddd31056e372829d97f7fe3ccda8da0fc2032",
            Bytes.hex(HybridWifiProof.mac(secret, "client", nc, ns)))
        val genuine = HybridWifiProof.mac(secret, "client", nc, ns)
        assertTrue(HybridWifiProof.verify(secret, "client", nc, ns, genuine))
        assertFalse(HybridWifiProof.verify(secret, "server", nc, ns, genuine))
        assertFalse(HybridWifiProof.verify(secret, "client", nc, ns, genuine.dropLast(1).toByteArray()))
        assertFalse(HybridWifiProof.verify(secret, "client", nc, ns.copyOf().apply { this[0] = 0 }, genuine))
    }

    @Test fun distinctDirectionalKeysAndGcmRoundTrip() {
        val (aToI, iToA) = HybridWifiProof.keys(secret, nc, ns)
        assertEquals("766b8fa5693cfd7550f70ef7e8960c2fa2bf5329bafee734783bc9714441a438", Bytes.hex(aToI))
        assertEquals("38fdc2543aad2a87a64a7d36a8a57615d77046b40a5b61458fce6171eacdd589", Bytes.hex(iToA))
        val android = SecureSession(aToI, iToA)
        val iphone = SecureSession(iToA, aToI)
        val pcm = byteArrayOf(1, 0, 1, 2, 3, 4)
        val sealed = android.seal(Proto.CH_AUDIO, pcm)
        val (channel, actual) = iphone.open(sealed)
        assertEquals(Proto.CH_AUDIO, channel)
        assertArrayEquals(pcm, actual)
        try {
            iphone.open(sealed)
            throw AssertionError("replay accepted")
        } catch (_: SessionException) { }
        val uplink = iphone.seal(Proto.CH_AUDIO, pcm)
        assertArrayEquals(pcm, android.open(uplink).second)
    }
}
