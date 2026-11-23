import Foundation
import os

/// Fixed-size sample ring shared with a real-time audio thread: no allocation and a very short
/// critical section under an unfair lock on that thread.
public final class SampleRing: @unchecked Sendable {
    private var lock = os_unfair_lock()
    private let buf: UnsafeMutablePointer<Float>
    private let cap: Int
    private var r = 0, w = 0, count = 0

    public init(capacity: Int) {
        cap = capacity
        buf = .allocate(capacity: capacity)
        buf.initialize(repeating: 0, count: capacity)
    }
    deinit { buf.deallocate() }

    public var available: Int { os_unfair_lock_lock(&lock); defer { os_unfair_lock_unlock(&lock) }; return count }

    /// Overwrites the oldest samples when full.
    public func write(_ src: UnsafePointer<Float>, _ n: Int, stride: Int = 1) {
        os_unfair_lock_lock(&lock); defer { os_unfair_lock_unlock(&lock) }
        for i in 0..<n {
            buf[w] = src[i * stride]
            w = (w + 1) % cap
            if count == cap { r = (r + 1) % cap } else { count += 1 }
        }
    }

    @discardableResult
    public func read(into dst: UnsafeMutablePointer<Float>, _ n: Int) -> Int {
        os_unfair_lock_lock(&lock); defer { os_unfair_lock_unlock(&lock) }
        let k = min(n, count)
        for i in 0..<k { dst[i] = buf[r]; r = (r + 1) % cap }
        count -= k
        return k
    }

    public func skip(_ n: Int) {
        os_unfair_lock_lock(&lock); defer { os_unfair_lock_unlock(&lock) }
        let k = min(n, count); r = (r + k) % cap; count -= k
    }

    public func clear() { os_unfair_lock_lock(&lock); r = 0; w = 0; count = 0; os_unfair_lock_unlock(&lock) }
}

/// Playout buffer for decoded far-end audio.
/// - A short shortfall is concealed (the last 10 ms faded out over ~60 ms) instead of dropping to silence and
///   re-buffering; only ~100 ms without audio un-primes it.
/// - Clock drift and bursts are drained in quiet frames (a quiet frame is written short: inaudible) back to `target`, so the
///   delay never creeps up and is never cut in the middle of speech; a hard trim only catches a huge burst.
/// - `target` follows the spread of frame arrival times over the last ~10 s (up at once, down slowly), grows by 5 ms
///   with every concealed gap (more after a real underrun) and shrinks after ~3 s clean, never below that spread.
public final class PlayoutBuffer: @unchecked Sendable {
    private let ring: SampleRing
    private var lock = os_unfair_lock()
    public private(set) var target: Int
    public let minTarget: Int, maxTarget: Int
    private let rate: Int
    private var primed = false
    public private(set) var underruns = 0
    public private(set) var trimmed = 0
    public private(set) var concealedSamples = 0
    private var cleanPulls = 0
    // Concealment state, render thread only.
    private let last: UnsafeMutablePointer<Float>
    private let lastLen: Int
    private var concealRun = 0

    public init(rate: Double) {
        self.rate = Int(rate)
        minTarget = Int(rate * 0.03)   // 30 ms
        maxTarget = Int(rate * 0.12)   // 120 ms
        target = Int(rate * 0.04)      // start at 40 ms
        jitterFloor = Int(rate * 0.03)
        delays = [Double](repeating: 0, count: 500)
        ring = SampleRing(capacity: Int(rate))
        lastLen = Int(rate * 0.01)     // 10 ms
        last = .allocate(capacity: lastLen)
        last.initialize(repeating: 0, count: lastLen)
    }
    deinit { last.deallocate() }

    // Arrival statistics (audio queue only): each frame's delay against an ideal 20 ms clock, last ~10 s.
    private var delays: [Double]
    private var delayCount = 0, delayNext = 0, sinceStats = 0
    private var firstArrival: UInt64 = 0
    private var expectedSamples = 0
    /// The target the arrival statistics ask for (samples); `target` never shrinks below it and jumps up to it.
    /// Written on the audio queue, read for statistics.
    public private(set) var jitterFloor: Int
    /// Lost frames filled in by the decoder's concealment (pushed with `concealed`).
    public private(set) var concealedFrames = 0

    /// Audio queue (not real-time): converts outside any lock the render thread uses. `at`: when the frame arrived
    /// (uptime ns); `missing`: frames lost just before this one (they never come, so they do not count as late).
    /// `concealed`: decoder concealment standing in for a lost frame (fills its place, is no arrival).
    public func push(_ pcm: [Int16], at: UInt64 = DispatchTime.now().uptimeNanoseconds, missing: Int = 0, concealed: Bool = false) {
        if concealed { expectedSamples += pcm.count; concealedFrames += 1 } else { noteArrival(samples: pcm.count, missing: missing, at: at) }
        let f = pcm.map { Float($0) / 32768 }
        let energy = f.reduce(Float(0)) { $0 + $1 * $1 } / Float(max(1, f.count))
        os_unfair_lock_lock(&lock)
        let tgt = target
        os_unfair_lock_unlock(&lock)
        // Drift: a quiet frame is written short (only its own silence is dropped, never queued speech).
        var n = f.count
        let before = ring.available
        if energy < 1e-5 && before + n > tgt + minTarget / 3 { n -= min(before + n - tgt, n) }
        if n > 0 { f.withUnsafeBufferPointer { ring.write($0.baseAddress!, n) } }
        var live = before + n
        var skip = f.count - n
        if live > tgt + maxTarget { ring.skip(live - tgt); skip += live - tgt; live = tgt }   // a huge burst: cut now
        os_unfair_lock_lock(&lock)
        if !primed && live >= tgt { primed = true }
        trimmed += skip
        os_unfair_lock_unlock(&lock)
    }

    /// Real-time thread: fills `out`, returns how many samples were real audio.
    @discardableResult
    public func pull(into out: UnsafeMutablePointer<Float>, count: Int) -> Int {
        os_unfair_lock_lock(&lock)
        let isPrimed = primed, tgt = target
        os_unfair_lock_unlock(&lock)
        guard isPrimed else { for i in 0..<count { out[i] = 0 }; return 0 }
        // After a gap keep concealing until half the target is back: frames lost (not late) would otherwise leave
        // the buffer running on empty, and every small jitter spike would be another gap.
        let n = concealRun > 0 && ring.available < tgt / 2 ? 0 : ring.read(into: out, count)
        if n == count {
            if concealRun > 0 && n > 0 {
                // Fade back in over the first 2 ms after concealment (no click).
                let ramp = min(n, rate / 500)
                for i in 0..<ramp { out[i] *= Float(i) / Float(ramp) }
            }
            concealRun = 0
            remember(out, count)
            os_unfair_lock_lock(&lock)
            cleanPulls += 1
            // ~3 s clean: shrink, but not below what the arrival statistics ask for.
            if cleanPulls > 300 && target > max(minTarget, jitterFloor) { target = max(jitterFloor, target - minTarget / 6); cleanPulls = 0 }
            os_unfair_lock_unlock(&lock)
            return n
        }
        // Shortfall: continue the last 10 ms, fading out over ~60 ms, instead of a hard gap.
        // A new gap also deepens the buffer a little: frames over Bluetooth arrive in bursts (gaps of 60-77 ms
        // against a 40 ms target were concealed every few seconds on a real iPhone, 2026-11-22), and only a full
        // 100 ms dropout used to grow the target. It shrinks again after ~3 s without a gap.
        if concealRun == 0 {
            os_unfair_lock_lock(&lock)
            target = min(maxTarget, target + rate / 200)
            os_unfair_lock_unlock(&lock)
        }
        let fadeLen = Float(rate) * 0.06
        for i in n..<count {
            let g = max(0, 1 - Float(concealRun) / fadeLen)
            out[i] = last[(concealRun) % lastLen] * g
            concealRun += 1
        }
        os_unfair_lock_lock(&lock)
        concealedSamples += count - n
        cleanPulls = 0
        if concealRun > rate / 10 {
            // ~100 ms with nothing: really out of audio. Re-buffer a little deeper.
            primed = false
            underruns += 1
            target = min(maxTarget, target + minTarget / 3)
            concealRun = 0
        }
        os_unfair_lock_unlock(&lock)
        return n
    }

    /**
     * The delay of each frame against an ideal clock started by the first one; the spread of the last ~10 s
     * (2nd percentile to the second-latest frame, so one freak spike does not count) is how much the buffer must hold
     * so the late frames are still in time. Over Bluetooth the late frames are few and periodic (a 60-77 ms hold-up
     * every few seconds): a percentile below the top misses them, and each one was concealed. It is
     * recomputed every 0.5 s; the target follows it up at once (before the next gap is concealed, not after) and
     * down only by the slow clean-playout shrink. Clock drift moves all delays together, so it does not widen the
     * spread inside a window.
     */
    private func noteArrival(samples: Int, missing: Int, at: UInt64) {
        if firstArrival == 0 || at < firstArrival { firstArrival = at; expectedSamples = 0; delayCount = 0; delayNext = 0 }
        expectedSamples += missing * samples
        let d = Double(at - firstArrival) / 1e6 - Double(expectedSamples) * 1000 / Double(rate)
        expectedSamples += samples
        delays[delayNext] = d
        delayNext = (delayNext + 1) % delays.count
        delayCount = min(delayCount + 1, delays.count)
        sinceStats += 1
        guard sinceStats >= 25, delayCount >= 50 else { return }
        sinceStats = 0
        let sorted = delays[0..<delayCount].sorted()
        let lo = sorted[Int(Double(delayCount - 1) * 0.02)], hi = sorted[delayCount - 2]
        let floor = min(maxTarget, max(minTarget, Int((hi - lo + Self.marginMs) * Double(rate) / 1000)))
        os_unfair_lock_lock(&lock)
        jitterFloor = floor
        if target < floor { target = floor }
        os_unfair_lock_unlock(&lock)
    }

    /// Room beyond the arrival spread: the render thread takes 10 ms at a time.
    static let marginMs = 10.0

    private func remember(_ out: UnsafeMutablePointer<Float>, _ count: Int) {
        let k = min(count, lastLen)
        // Keep the newest 10 ms, in order, so concealment continues the waveform.
        if k == lastLen { last.update(from: out + (count - k), count: k) }
        else { last.update(from: last + k, count: lastLen - k); (last + (lastLen - k)).update(from: out + (count - k), count: k) }
    }

    /** Samples queued right now (the playout delay beyond the output's own). */
    public var buffered: Int { ring.available }

    public func reset() { ring.clear(); os_unfair_lock_lock(&lock); primed = false; concealRun = 0; os_unfair_lock_unlock(&lock); firstArrival = 0 }
}

