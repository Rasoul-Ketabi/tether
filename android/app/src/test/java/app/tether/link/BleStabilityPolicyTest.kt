package app.tether.link
import org.junit.Assert.*
import org.junit.Test
class BleStabilityPolicyTest {
    @Test fun shortSessionsPreferGattTemporarily() {
        val p = BleStabilityPolicy()
        repeat(3) { i ->
            val t = i * 10_000L
            p.started("a", "l2cap", t)
            assertEquals(i == 2, p.ended("a", "l2cap", t + 1000))
        }
        assertTrue(p.preferGatt("a", 22_000))
        assertFalse(p.preferGatt("b", 22_000))
        assertFalse(p.preferGatt("a", 321_001))
    }
    @Test fun longSessionResetsShortFailures() {
        val p = BleStabilityPolicy()
        p.started("a","l2cap",0); p.ended("a","l2cap",1000)
        p.started("a","l2cap",2000); p.ended("a","l2cap",40000)
        p.started("a","l2cap",41000); assertFalse(p.ended("a","l2cap",42000))
    }
}
