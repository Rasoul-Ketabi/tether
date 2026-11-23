package app.tether.client.calls

/**
 * Playout buffer for decoded far-end audio, ported from TetherCore's PlayoutBuffer (same numbers, same tests):
 * - a short shortfall is concealed (the last 10 ms faded out over ~60 ms) instead of dropping to silence and
 *   re-buffering; only ~100 ms without audio un-primes it;
 * - clock drift and bursts are drained in quiet frames (a quiet frame is written short: inaudible) back to [target],
 *   so the delay never creeps up and is never cut in the middle of speech; a hard trim only catches a huge burst;
 * - [target] grows by 5 ms with every concealed gap (more after a real underrun) and shrinks after ~3 s clean.
 * [push] runs on the link / decode thread, [pull] on the playback thread.
 */
class PlayoutBuffer(rate: Int) {
    private val rate = rate
    val minTarget = rate * 3 / 100     // 30 ms
    val maxTarget = rate * 12 / 100    // 120 ms
    @Volatile var target = rate * 4 / 100; private set   // start at 40 ms
    private val ring = FloatArray(rate)   // 1 s
    private var r = 0; private var w = 0; private var count = 0
    private val lock = Any()
    private var primed = false
    @Volatile var underruns = 0; private set
    @Volatile var trimmed = 0; private set
    @Volatile var concealedSamples = 0; private set
    private var cleanPulls = 0
    // Concealment state, playback thread only.
    private val lastLen = rate / 100       // 10 ms
    private val last = FloatArray(lastLen)
    private var concealRun = 0

    val available: Int get() = synchronized(lock) { count }

    private fun writeLocked(src: FloatArray, n: Int) {
        for (i in 0 until n) {
            ring[w] = src[i]
            w = (w + 1) % ring.size
            if (count == ring.size) r = (r + 1) % ring.size else count++
        }
    }

    private fun readLocked(dst: FloatArray, n: Int): Int {
        val k = minOf(n, count)
        for (i in 0 until k) { dst[i] = ring[r]; r = (r + 1) % ring.size }
        count -= k
        return k
    }

    private fun skipLocked(n: Int) { val k = minOf(n, count); r = (r + k) % ring.size; count -= k }

    fun push(pcm: ShortArray) {
        val f = FloatArray(pcm.size) { pcm[it] / 32768f }
        var energy = 0f
        for (x in f) energy += x * x
        energy /= maxOf(1, f.size)
        synchronized(lock) {
            val tgt = target
            // Drift: a quiet frame is written short (only its own silence is dropped, never queued speech).
            var n = f.size
            val before = count
            if (energy < 1e-5f && before + n > tgt + minTarget / 3) n -= minOf(before + n - tgt, n)
            if (n > 0) writeLocked(f, n)
            var live = before + n
            var skip = f.size - n
            if (live > tgt + maxTarget) { skipLocked(live - tgt); skip += live - tgt; live = tgt }   // a huge burst: cut now
            if (!primed && live >= tgt) primed = true
            trimmed += skip
        }
    }

    /** Fills [out] (count samples); returns how many were real audio. */
    fun pull(out: FloatArray, n: Int = out.size): Int {
        val isPrimed: Boolean; val tgt: Int
        synchronized(lock) { isPrimed = primed; tgt = target }
        if (!isPrimed) { java.util.Arrays.fill(out, 0, n, 0f); return 0 }
        // After a gap keep concealing until half the target is back: frames lost (not late) would otherwise leave
        // the buffer running on empty, and every small jitter spike would be another gap.
        val got = synchronized(lock) { if (concealRun > 0 && count < tgt / 2) 0 else readLocked(out, n) }
        if (got == n) {
            if (concealRun > 0 && got > 0) {
                // Fade back in over the first 2 ms after concealment (no click).
                val ramp = minOf(got, rate / 500)
                for (i in 0 until ramp) out[i] *= i.toFloat() / ramp
            }
            concealRun = 0
            remember(out, n)
            synchronized(lock) {
                cleanPulls++
                if (cleanPulls > 300 && target > minTarget) { target -= minTarget / 6; cleanPulls = 0 }   // ~3 s clean: shrink
            }
            return got
        }
        // Shortfall: continue the last 10 ms, fading out over ~60 ms, instead of a hard gap. A new gap also deepens
        // the buffer a little (frames over Bluetooth arrive in bursts); it shrinks again after ~3 s without a gap.
        if (concealRun == 0) synchronized(lock) { target = minOf(maxTarget, target + rate / 200) }
        val fadeLen = rate * 0.06f
        for (i in got until n) {
            val g = maxOf(0f, 1 - concealRun / fadeLen)
            out[i] = last[concealRun % lastLen] * g
            concealRun++
        }
        synchronized(lock) {
            concealedSamples += n - got
            cleanPulls = 0
            if (concealRun > rate / 10) {
                // ~100 ms with nothing: really out of audio. Re-buffer a little deeper.
                primed = false
                underruns++
                target = minOf(maxTarget, target + minTarget / 3)
                concealRun = 0
            }
        }
        return got
    }

    private fun remember(out: FloatArray, n: Int) {
        val k = minOf(n, lastLen)
        // Keep the newest 10 ms, in order, so concealment continues the waveform.
        if (k == lastLen) System.arraycopy(out, n - k, last, 0, k)
        else { System.arraycopy(last, k, last, 0, lastLen - k); System.arraycopy(out, n - k, last, lastLen - k, k) }
    }

    fun reset() = synchronized(lock) { r = 0; w = 0; count = 0; primed = false; concealRun = 0 }
}

/**
 * What this client does with a call state for a call it already hung up (the Android phone may still send states that
 * were on their way): drop them, ask again if the Android phone still reports the call 2 s later, forget after an end.
 * Port of TetherCore's HangUpGuard.
 */
class HangUpGuard {
    enum class Verdict { PASS, DROP, DROP_AND_RESEND }
    private val hungUp = HashMap<String, Long>()

    fun hungUp(cid: String, at: Long = System.currentTimeMillis()) { hungUp[cid] = at }
    fun reset() = hungUp.clear()
    operator fun contains(cid: String) = cid in hungUp

    fun check(cid: String, ended: Boolean, now: Long = System.currentTimeMillis()): Verdict {
        val t = hungUp[cid] ?: return Verdict.PASS             // any other call, including every new one
        if (ended) { hungUp.remove(cid); return Verdict.PASS }   // the end we wanted: let it close what is left
        val age = now - t
        if (age > 30_000) { hungUp.remove(cid); return Verdict.DROP }   // give up asking; never resurrect it
        if (age > 2_000) { hungUp[cid] = now; return Verdict.DROP_AND_RESEND }
        return Verdict.DROP
    }
}
