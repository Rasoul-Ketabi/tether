package app.tether.link

import app.tether.core.P256
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pure, cross-platform test-vector-compatible authentication for the optional LAN lane. */
internal object HybridWifiProof {
    const val CONTEXT = "tether/hybrid-wifi/1"

    fun mac(secret: ByteArray, role: String, nc: ByteArray, ns: ByteArray): ByteArray {
        require(secret.size == 32 && nc.size == 16 && ns.size == 16)
        require(role == "client" || role == "server")
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal((CONTEXT + "/" + role).toByteArray(Charsets.UTF_8) + nc + ns)
        }
    }

    fun verify(secret: ByteArray, role: String, nc: ByteArray, ns: ByteArray, proof: ByteArray): Boolean =
        proof.size == 32 && MessageDigest.isEqual(mac(secret, role, nc, ns), proof)

    /** First key Android → iPhone, second iPhone → Android. */
    fun keys(secret: ByteArray, nc: ByteArray, ns: ByteArray): Pair<ByteArray, ByteArray> {
        require(secret.size == 32 && nc.size == 16 && ns.size == 16)
        val material = P256.hkdf(nc + ns, secret, CONTEXT.toByteArray(Charsets.UTF_8), 64)
        return material.copyOfRange(0, 32) to material.copyOfRange(32, 64)
    }
}
