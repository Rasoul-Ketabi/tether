import XCTest
@testable import TetherCore

final class PlayoutTests: XCTestCase {
    private func tone(_ n: Int, amp: Double = 8000) -> [Int16] { (0..<n).map { Int16(amp * sin(Double($0) * 0.2)) } }
    private func pull(_ b: PlayoutBuffer, _ n: Int) -> (real: Int, out: [Float]) {
        var out = [Float](repeating: 0, count: n)
        let r = out.withUnsafeMutableBufferPointer { b.pull(into: $0.baseAddress!, count: n) }
        return (r, out)
    }

    func testShortGapIsConcealedNotSilent() {
        let b = PlayoutBuffer(rate: 16000)
        b.push(tone(640))                 // 40 ms: primes at the 40 ms target
        _ = pull(b, 640)
        let (real, out) = pull(b, 160)    // nothing queued: a 10 ms shortfall
        XCTAssertEqual(real, 0)
        XCTAssertGreaterThan(out.map(abs).max() ?? 0, 0.05, "the gap continues the last audio, faded, instead of silence")
        XCTAssertEqual(b.underruns, 0, "a short gap is not counted as an underrun")
    }

    func testLongGapRebuffersDeeper() {
        let b = PlayoutBuffer(rate: 16000)
        b.push(tone(640)); _ = pull(b, 640)
        let before = b.target
        for _ in 0..<12 { _ = pull(b, 160) }   // 120 ms of nothing
        XCTAssertEqual(b.underruns, 1)
        XCTAssertGreaterThan(b.target, before)
        let (real, _) = pull(b, 160)
        XCTAssertEqual(real, 0, "un-primed until the target refills")
    }

    func testDriftIsDrainedInSilenceButNotInSpeech() {
        let b = PlayoutBuffer(rate: 16000)
        b.push(tone(640)); _ = pull(b, 320)
        for _ in 0..<3 { b.push(tone(320)) }                 // speech backs up: never cut
        XCTAssertEqual(b.trimmed, 0)
        b.push([Int16](repeating: 0, count: 320))           // a quiet frame: the excess silence is dropped
        XCTAssertGreaterThan(b.trimmed, 0)
    }

    func testDrainNeverDropsQueuedSpeech() {
        let b = PlayoutBuffer(rate: 16000)
        b.push(tone(640)); _ = pull(b, 320)
        for _ in 0..<3 { b.push(tone(320)) }                 // 80 ms of speech queued
        let queued = 320 + 3 * 320
        b.push([Int16](repeating: 0, count: 320))           // quiet frame over the target
        let (real, out) = pull(b, queued)
        XCTAssertEqual(real, queued, "all queued speech is still there")
        XCTAssertGreaterThan(out.suffix(320).map(abs).max() ?? 0, 0.05, "the newest speech plays, not cut by the drain")
    }

    func testAfterAGapItRefillsBeforePlaying() {
        let b = PlayoutBuffer(rate: 16000)
        b.push(tone(640)); _ = pull(b, 640)
        _ = pull(b, 160)                                    // a 10 ms gap: concealing
        b.push(tone(160))                                   // 10 ms arrives: under half the 40 ms target
        XCTAssertEqual(pull(b, 160).real, 0, "keeps concealing while the buffer refills")
        b.push(tone(320))                                   // now 30 ms queued
        XCTAssertEqual(pull(b, 160).real, 160)
    }

    func testShortGapsDeepenTheBufferAndCleanPlayoutShrinksIt() {
        let b = PlayoutBuffer(rate: 16000)
        b.push(tone(640)); _ = pull(b, 640)
        let start = b.target
        for _ in 0..<4 {                        // four short gaps, each followed by enough audio to play again
            _ = pull(b, 160)
            b.push(tone(1600)); _ = pull(b, 1600)
        }
        XCTAssertGreaterThan(b.target, start, "bursty arrival deepens the buffer")
        XCTAssertLessThanOrEqual(b.target, b.maxTarget)
        let deep = b.target
        for _ in 0..<700 { b.push(tone(160)); _ = pull(b, 160) }   // ~7 s without a gap
        XCTAssertLessThan(b.target, deep, "and it shrinks back once arrival is smooth")
    }
}
