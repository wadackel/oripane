import CryptoKit
import Foundation
import Testing
@testable import OripaneCore

@Suite struct CryptoTests {
    @Test func rfc7748SharedSecret() throws {
        // RFC 7748 §6.1
        let alice = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(hex: "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!)
        let bobPub = Data(hex: "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")!
        let shared = try Handshake.sharedSecret(privateKey: alice, peer: bobPub)
        #expect(shared.hex == "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
    }

    @Test func pairingAgreesOnBothSides() throws {
        let h = Curve25519.KeyAgreement.PrivateKey()
        let c = Curve25519.KeyAgreement.PrivateKey()
        let hp = h.publicKey.rawRepresentation, cp = c.publicKey.rawRepresentation
        let k1 = Handshake.pairKey(shared: try Handshake.sharedSecret(privateKey: h, peer: cp), hostPub: hp, clientPub: cp)
        let k2 = Handshake.pairKey(shared: try Handshake.sharedSecret(privateKey: c, peer: hp), hostPub: hp, clientPub: cp)
        #expect(k1 == k2)
        #expect(Handshake.sas(hostPub: hp, clientPub: cp).count == 6)
    }

    @Test func proofsVerifyOnlyForTheirRole() {
        let key = Data(repeating: 7, count: 32), cn = Data(repeating: 1, count: 32), hn = Data(repeating: 2, count: 32)
        let p = Handshake.proof(pairKey: key, role: "host", clientNonce: cn, hostNonce: hn)
        #expect(Handshake.verify(proof: p, pairKey: key, role: "host", clientNonce: cn, hostNonce: hn))
        #expect(!Handshake.verify(proof: p, pairKey: key, role: "client", clientNonce: cn, hostNonce: hn))
    }

    @Test func sealedMediaRejectsTamperedHeader() throws {
        let cipher = SessionCipher(key: Data(repeating: 9, count: 32))
        var packet = try MediaCodec.seal(MediaHeader(type: .input, sessionId: 1, seq: 5), plaintext: Data([1, 2, 3]), cipher: cipher, dir: .mediaToHost)
        let (h, plain) = try MediaCodec.open(packet, cipher: cipher, dir: .mediaToHost)
        #expect(h.seq == 5 && plain == Data([1, 2, 3]))
        packet[packet.startIndex + 13] ^= 1 // seq
        #expect(throws: WireError.authentication) { try MediaCodec.open(packet, cipher: cipher, dir: .mediaToHost) }
    }

    @Test func controlCountersStayInStep() throws {
        var host = ControlCipher(sessionKey: Data(repeating: 3, count: 32), isHost: true)
        var client = ControlCipher(sessionKey: Data(repeating: 3, count: 32), isHost: false)
        for i in 0..<3 {
            let msg = Data("m\(i)".utf8)
            #expect(try client.open(try host.seal(msg)) == msg)
            #expect(try host.open(try client.seal(msg)) == msg)
        }
    }
}

@Suite struct FECTests {
    func frame(_ n: Int) -> Data { Data((0..<n).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) }) }

    func split(_ packets: [FramePacket]) -> (fragments: [FramePacket], parities: [FramePacket]) {
        (packets.filter { if case .fragment = $0 { return true }; return false },
         packets.filter { if case .parity = $0 { return true }; return false })
    }

    @Test func wholeFrameRoundTrips() {
        let data = frame(10_000)
        var r = Reassembler()
        var out: Reassembler.Frame?
        for p in Packetizer(groupSize: 4).packets(frame: data, frameId: 1, keyframe: false, captureUs: 1, hostUs: 2) {
            out = r.add(p) ?? out
        }
        #expect(out?.data == data)
        #expect(out?.recovered == false)
    }

    @Test func oneLossPerGroupIsRepaired() {
        let data = frame(10_000) // 10 fragments, the last one short
        let packets = Packetizer(groupSize: 4).packets(frame: data, frameId: 3, keyframe: true, captureUs: 1, hostUs: 2)
        for lost in [0, 5, 9] {
            var r = Reassembler()
            var out: Reassembler.Frame?
            var fragIndex = -1
            for p in packets {
                if case .fragment = p { fragIndex += 1; if fragIndex == lost { continue } }
                out = r.add(p) ?? out
            }
            #expect(out?.data == data, "lost fragment \(lost)")
            #expect(out?.recovered == true)
        }
    }

    @Test func twoLossesInOneGroupAreNotRepaired() {
        let data = frame(5_000)
        var r = Reassembler()
        var fragIndex = -1
        var out: Reassembler.Frame?
        for p in Packetizer(groupSize: 8).packets(frame: data, frameId: 1, keyframe: false, captureUs: 1, hostUs: 2) {
            if case .fragment = p { fragIndex += 1; if fragIndex < 2 { continue } }
            out = r.add(p) ?? out
        }
        #expect(out == nil)
    }

    @Test func newerFrameDropsOlderIncompleteOne() {
        var r = Reassembler()
        let a = Packetizer().packets(frame: frame(3000), frameId: 1, keyframe: false, captureUs: 1, hostUs: 1)
        let b = Packetizer().packets(frame: frame(500), frameId: 2, keyframe: false, captureUs: 2, hostUs: 1)
        _ = r.add(a[0])
        #expect(r.add(b[0])?.info.frameId == 2)
        #expect(r.framesDropped == 1)
        #expect(r.add(a[1]) == nil) // stale
    }

    @Test func wholyLostFrameCountsAsDropped() {
        var r = Reassembler()
        for id: UInt32 in [1, 3] {
            for p in Packetizer().packets(frame: frame(500), frameId: id, keyframe: false, captureUs: 1, hostUs: 1) { _ = r.add(p) }
        }
        #expect(r.framesDropped == 1)
    }

    @Test func singleFragmentFrameSurvivesLosingItsFragment() {
        let data = frame(300)
        let packets = Packetizer().packets(frame: data, frameId: 9, keyframe: false, captureUs: 1, hostUs: 1)
        #expect(packets.count == 2)
        var r = Reassembler()
        #expect(r.add(packets[1])?.data == data)
    }

    @Test func frameIdsWrap() {
        #expect(Reassembler.isNewer(0, than: UInt32.max))
        #expect(!Reassembler.isNewer(UInt32.max, than: 0))
    }
}

@Suite struct WireTests {
    @Test func annexBConversionPrependsParameterSets() throws {
        let avcc = Data([0, 0, 0, 2, 0x65, 0xAA, 0, 0, 0, 1, 0x41])
        let out = try AnnexB.fromAVCC(avcc, parameterSets: [Data([0x67, 1]), Data([0x68, 2])])
        #expect(out == Data([0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 0xAA, 0, 0, 0, 1, 0x41]))
    }

    @Test func controlFramesSplitAcrossReads() throws {
        var fb = FrameBuffer()
        let a = FrameBuffer.frame(Data("hello".utf8)), b = FrameBuffer.frame(Data("world!".utf8))
        let all = a + b
        fb.append(all.prefix(3))
        #expect(try fb.next() == nil)
        fb.append(all.dropFirst(3))
        #expect(try fb.next() == Data("hello".utf8))
        #expect(try fb.next() == Data("world!".utf8))
        #expect(try fb.next() == nil)
    }

    @Test func inputAndFeedbackRoundTrip() throws {
        let events = [SequencedInput(seq: 1, event: .pointer(action: .drag, x: 0.1, y: 0.9)), SequencedInput(seq: 2, event: .scroll(dx: 1, dy: -2))]
        #expect(try InputPacket.decode(InputPacket.encode(events)) == events)
        let fb = Feedback(lastFrameId: 1, packetsReceived: 2, packetsLost: 3, framesRecovered: 4, framesDropped: 5, decodeUs: 6, totalUs: 7)
        #expect(try Feedback.decode(fb.encoded) == fb)
    }

    @Test func vectorsAreDeterministic() throws {
        let a = Json.encode(try Vectors.generate()), b = Json.encode(try Vectors.generate())
        #expect(a == b)
    }
}

@Suite struct TailnetTests {
    @Test func onlyUtunAddressesInTheTailscaleRangeCount() {
        let found = Tailnet.addresses([
            (name: "en0", ipv4: "192.168.1.11"),
            (name: "en0", ipv4: "100.100.1.1"),
            (name: "utun3", ipv4: "100.101.102.103"),
            (name: "utun4", ipv4: "192.168.9.1"),
            (name: "utun5", ipv4: "100.128.0.1"),
            (name: "utun6", ipv4: "100.64.0.1"),
            (name: "utun7", ipv4: "100.101.102.103"),
        ])
        #expect(found == ["100.101.102.103", "100.64.0.1"])
    }

    @Test func malformedAddressesAreIgnored() {
        #expect(Tailnet.addresses([(name: "utun1", ipv4: "100.64"), (name: "utun1", ipv4: "100.300.0.1"), (name: "utun1", ipv4: "")]).isEmpty)
    }
}

@Suite struct RateTests {
    func clean(_ n: UInt32 = 100) -> Feedback { Feedback(lastFrameId: 0, packetsReceived: n, packetsLost: 0, framesRecovered: 0, framesDropped: 0, decodeUs: 0, totalUs: 0) }
    func lossy() -> Feedback { Feedback(lastFrameId: 0, packetsReceived: 80, packetsLost: 20, framesRecovered: 0, framesDropped: 0, decodeUs: 0, totalUs: 0) }

    @Test func feedbackCountersThatOverflowDoNotStopTheHost() {
        let r = RateController(initial: 10_000_000, now: 0)
        let fb = Feedback(lastFrameId: 0, packetsReceived: 0x8000_0000, packetsLost: 0x8000_0001, framesRecovered: 0, framesDropped: 0, decodeUs: 0, totalUs: 0)
        #expect(r.update(fb, now: 1) == 8_000_000)
    }

    @Test func loweringTheCeilingDropsToItAndUpdatesStayBelow() {
        let r = RateController(initial: 16_000_000, now: 0)
        #expect(r.setCeiling(4_000_000, now: 1_000) == 4_000_000)
        var now: UInt64 = 1_000
        for _ in 0..<10 {
            now += 3_500_000
            if let next = r.update(clean(), now: now) { #expect(next <= 4_000_000) }
        }
        #expect(r.current == 4_000_000)
    }

    @Test func loweringTheCeilingAboveTheCurrentRateReturnsNothing() {
        let r = RateController(initial: 16_000_000, now: 0)
        _ = r.update(lossy(), now: 10)
        #expect(r.current == 12_800_000)
        #expect(r.setCeiling(14_000_000, now: 20) == nil)
        #expect(r.current == 12_800_000)
    }

    @Test func raisingTheCeilingClimbsATenthOfItEveryThreeCleanSeconds() {
        let r = RateController(initial: 4_000_000, now: 0)
        #expect(r.setCeiling(16_000_000, now: 1_000_000) == nil)
        #expect(r.update(clean(), now: 3_000_000) == nil)
        #expect(r.update(clean(), now: 3_000_001) == 5_600_000)
        #expect(r.update(clean(), now: 6_000_001) == nil)
        #expect(r.update(clean(), now: 6_000_002) == 7_200_000)
    }

    @Test func lossBacksOffButNotBelowTheFloor() {
        let r = RateController(initial: 2_200_000, now: 0)
        #expect(r.update(lossy(), now: 1) == 2_000_000)
        #expect(r.update(lossy(), now: 2) == nil)
    }

    @Test func limitsBelowTheFloorAreRaisedAndNonPositiveMeansNone() {
        #expect(BitrateLimit.normalize(0) == 0)
        #expect(BitrateLimit.normalize(-5) == 0)
        #expect(BitrateLimit.normalize(1) == 2_000_000)
        #expect(BitrateLimit.normalize(4_000_000) == 4_000_000)
        #expect(BitrateLimit.ceiling(unlimited: 16_000_000, limit: 0) == 16_000_000)
        #expect(BitrateLimit.ceiling(unlimited: 16_000_000, limit: 4_000_000) == 4_000_000)
        #expect(BitrateLimit.ceiling(unlimited: 3_000_000, limit: 4_000_000) == 3_000_000)
    }
}

@Suite struct ClickTests {
    @Test func continuedDownsCountUpToThree() {
        var c = ClickCounter()
        #expect(c.down(continued: false) == 1)
        #expect(c.down(continued: true) == 2)
        #expect(c.down(continued: true) == 3)
        #expect(c.down(continued: true) == 3)
        #expect(c.down(continued: false) == 1)
    }

    @Test func aRightClickEndsTheSeries() {
        var c = ClickCounter()
        _ = c.down(continued: false)
        c.rightClick()
        #expect(c.down(continued: true) == 1)
    }

    @Test func continuedWithoutAPreviousClickIsTheFirst() {
        var c = ClickCounter()
        #expect(c.down(continued: true) == 1)
    }

    @Test func actionFourRoundTrips() throws {
        let events = [SequencedInput(seq: 3, event: .pointer(action: .downContinued, x: 0.5, y: 0.25))]
        #expect(try InputPacket.decode(InputPacket.encode(events)) == events)
    }
}

@Suite struct DisplayTests {
    @Test func omittedFieldsKeepTodaysBehaviour() {
        let r = DisplayRequest.resolve(["w": 100], forcedScale: nil, forcedFps: nil)
        #expect(r.scale == 1.0 && r.fps == 60 && r.uiScale == 2.0)
        #expect(r.requestedScale == nil && r.requestedFps == nil && r.requestedUiScale == nil)
        #expect(!r.scaleForced && !r.fpsForced)
    }

    @Test func integersWrittenForWholeNumbersAreRead() throws {
        let m = try Json.decode(Data(#"{"scale":1,"fps":30,"uiScale":2}"#.utf8))
        let r = DisplayRequest.resolve(m, forcedScale: nil, forcedFps: nil)
        #expect(r.scale == 1.0 && r.fps == 30 && r.uiScale == 2.0)
        let half = DisplayRequest.resolve(try Json.decode(Data(#"{"scale":0.75,"uiScale":1.5}"#.utf8)), forcedScale: nil, forcedFps: nil)
        #expect(half.scale == 0.75 && half.uiScale == 1.5)
    }

    @Test func valuesOutsideTheirRangesAreClamped() throws {
        let m = try Json.decode(Data(#"{"scale":3,"fps":240.4,"uiScale":1}"#.utf8))
        let r = DisplayRequest.resolve(m, forcedScale: nil, forcedFps: nil)
        #expect(r.scale == 1.0 && r.fps == 120 && r.uiScale == 1.5)
        let low = DisplayRequest.resolve(try Json.decode(Data(#"{"scale":0.1,"fps":0.2,"uiScale":5}"#.utf8)), forcedScale: nil, forcedFps: nil)
        #expect(low.scale == 0.25 && low.fps == 1 && low.uiScale == 2.0)
        #expect(DisplayRequest.resolve(["fps": 29.6], forcedScale: nil, forcedFps: nil).fps == 30)
    }

    @Test func hugeFpsValuesAreClampedWithoutTrapping() throws {
        #expect(DisplayRequest.resolve(try Json.decode(Data(#"{"fps":1e19}"#.utf8)), forcedScale: nil, forcedFps: nil).fps == 120)
        #expect(DisplayRequest.resolve(try Json.decode(Data(#"{"fps":-1e19}"#.utf8)), forcedScale: nil, forcedFps: nil).fps == 1)
        #expect(DisplayRequest.resolve(["fps": Double.infinity, "scale": Double.nan], forcedScale: nil, forcedFps: nil) == DisplayRequest.resolve([:], forcedScale: nil, forcedFps: nil))
    }

    @Test func theDensityIsClampedWithoutTrapping() throws {
        #expect(DisplayRequest.resolve(try Json.decode(Data(#"{"dpi":9223372036854775807}"#.utf8)), forcedScale: nil, forcedFps: nil).dpi == 1000)
        #expect(DisplayRequest.resolve(try Json.decode(Data(#"{"dpi":0}"#.utf8)), forcedScale: nil, forcedFps: nil).dpi == 72)
        #expect(DisplayRequest.resolve(try Json.decode(Data(#"{"dpi":420}"#.utf8)), forcedScale: nil, forcedFps: nil).dpi == 420)
        #expect(DisplayRequest.resolve([:], forcedScale: nil, forcedFps: nil).dpi == 420)
    }

    @Test func nonFiniteInputCoordinatesAreFlagged() {
        #expect(InputEvent.pointer(action: .down, x: 0.5, y: 0.5).isFinite)
        #expect(!InputEvent.pointer(action: .downContinued, x: .nan, y: 0.5).isFinite)
        #expect(!InputEvent.rightClick(x: 0.5, y: .infinity).isFinite)
        #expect(!InputEvent.scroll(dx: .nan, dy: 1).isFinite)
        #expect(InputEvent.scroll(dx: 3e38, dy: -3e38).isFinite)
    }

    @Test func hevcIsChosenOnlyWhenThePhoneOffersIt() throws {
        let both = try Json.decode(Data(#"{"codecs":["hevc","h264"]}"#.utf8))
        #expect(DisplayRequest.resolve(both, forcedScale: nil, forcedFps: nil).codec == "hevc")
        let avcOnly = try Json.decode(Data(#"{"codecs":["h264"]}"#.utf8))
        #expect(DisplayRequest.resolve(avcOnly, forcedScale: nil, forcedFps: nil).codec == "h264")
        #expect(DisplayRequest.resolve([:], forcedScale: nil, forcedFps: nil).codec == "h264")
        let notAList = try Json.decode(Data(#"{"codecs":"hevc"}"#.utf8))
        #expect(DisplayRequest.resolve(notAList, forcedScale: nil, forcedFps: nil).codec == "h264")
    }

    @Test func theHostsCodecOptionWinsOverTheOffer() throws {
        let both = try Json.decode(Data(#"{"codecs":["hevc","h264"]}"#.utf8))
        let forced = DisplayRequest.resolve(both, forcedScale: nil, forcedFps: nil, forcedCodec: "h264")
        #expect(forced.codec == "h264" && forced.codecForced)
        #expect(DisplayRequest.resolve([:], forcedScale: nil, forcedFps: nil, forcedCodec: "hevc").codec == "hevc")
        #expect(!DisplayRequest.resolve(both, forcedScale: nil, forcedFps: nil).codecForced)
    }

    @Test func booleansAndNonNumbersCountAsOmitted() throws {
        let m = try Json.decode(Data(#"{"scale":true,"fps":"30","uiScale":null}"#.utf8))
        let r = DisplayRequest.resolve(m, forcedScale: nil, forcedFps: nil)
        #expect(r.scale == 1.0 && r.fps == 60 && r.uiScale == 2.0)
        #expect(r.requestedScale == nil && r.requestedFps == nil && r.requestedUiScale == nil)
    }

    @Test func launchOptionsWinAndAreClampedToo() {
        let r = DisplayRequest.resolve(["scale": 0.5, "fps": 30], forcedScale: 2, forcedFps: 60)
        #expect(r.scale == 1.0 && r.fps == 60 && r.scaleForced && r.fpsForced)
        #expect(r.requestedScale == 0.5 && r.requestedFps == 30)
    }

    @Test func virtualDisplaySizesFollowTheUiScale() {
        #expect(VirtualDisplaySize.pixels(clientWidth: 2448, clientHeight: 1848, uiScale: 2.0) == (2448, 1848))
        #expect(VirtualDisplaySize.side(clientWidth: 2448, clientHeight: 1848, uiScale: 2.0) == 2560)
        #expect(VirtualDisplaySize.pixels(clientWidth: 2448, clientHeight: 1848, uiScale: 1.5) == (3264, 2464))
        #expect(VirtualDisplaySize.side(clientWidth: 2448, clientHeight: 1848, uiScale: 1.5) == 3414)
        #expect(VirtualDisplaySize.pixels(clientWidth: 1248, clientHeight: 1972, uiScale: 1.5) == (1664, 2628))
        #expect(VirtualDisplaySize.side(clientWidth: 1248, clientHeight: 1972, uiScale: 1.5) == 3414)
    }

    @Test func anOddLongSideAtTwoMatchesTheOldRule() {
        for long in [2561, 2999, 3001] {
            #expect(VirtualDisplaySize.side(clientWidth: long, clientHeight: 1000, uiScale: 2.0) == max((long + 1) & ~1, 2560))
        }
    }
}

@Suite struct InputSequenceTests {
    private func input(_ seq: UInt32) -> SequencedInput { SequencedInput(seq: seq, event: .pointer(action: .move, x: 0.5, y: 0.5)) }

    @Test func repeatsOfInputsAlreadyAppliedAreDropped() {
        var s = InputSequence()
        #expect(s.fresh([input(1), input(2)]).map(\.seq) == [1, 2])
        #expect(s.fresh([input(1), input(2), input(3)]).map(\.seq) == [3])
        #expect(s.fresh([input(2), input(3)]).isEmpty)
    }

    @Test func theSequenceWrapsAround() {
        var s = InputSequence()
        #expect(s.fresh([input(UInt32.max)]).map(\.seq) == [UInt32.max])
        #expect(s.fresh([input(UInt32.max), input(0), input(1)]).map(\.seq) == [0, 1])
    }
}

@Suite struct PairPromptTests {
    @Test func aNameCannotBreakTheLineOrRewriteTheTerminal() {
        #expect(PairPrompt.printable("Pixel\nanswer y\t\u{1B}[2J\u{202E}9") == "Pixelanswer y[2J9")
        #expect(PairPrompt.printable("Fold\u{2028}code 111111\u{2029}") == "Foldcode 111111")
    }

    @Test func aFullLengthNameWithSpacesPassesUnchanged() {
        let name = "Alex’s Galaxy Z Fold " + String(repeating: "x", count: 43)
        #expect(name.count == 64)
        #expect(PairPrompt.printable(name) == name)
    }

    @Test func theRealCodeLeadsTheQuestionWhateverTheNameSays() throws {
        let q = PairPrompt.question(name: "Fold\"? Check that the phone shows 111111. [y/N]", code: "482913", from: "192.168.1.5", waiting: 0)
        let lines = q.split(separator: "\n", omittingEmptySubsequences: true)
        #expect(lines.first == "Pairing request: the phone must show 482913")
        #expect(lines.count == 4)
        #expect(lines[1].hasPrefix("  from \""))
        #expect(lines[2] == "  From 192.168.1.5 · LAN")
        let real = try #require(q.range(of: "482913")), fake = try #require(q.range(of: "111111"))
        #expect(real.lowerBound < fake.lowerBound)
    }
}

@Suite struct PairingRequestTests {
    @Test func theQuestionSaysWhereTheRequestCameFromAndHowManyWait() {
        #expect(PairPrompt.origin("127.0.0.1") == "From this Mac")
        #expect(PairPrompt.origin("100.101.102.103") == "From 100.101.102.103 · Tailscale")
        #expect(PairPrompt.origin("192.168.1.5") == "From 192.168.1.5 · LAN")
        #expect(PairPrompt.waitingLine(0) == nil)
        #expect(PairPrompt.waitingLine(1) == "1 more phone is waiting.")
        #expect(PairPrompt.waitingLine(2) == "2 more phones are waiting.")
        #expect(PairPrompt.question(name: "Fold", code: "1", from: "127.0.0.1", waiting: 2).contains("  2 more phones are waiting.\n"))
    }

    @Test func aSecondRequestWaitsForTheAnswerToTheFirst() {
        var q = ApprovalQueue<String>()
        let a = UUID(), b = UUID()
        #expect(q.add(a, from: "10.0.0.1", "A") == .shown)
        #expect(q.add(b, from: "10.0.0.2", "B") == .waiting)
        #expect(q.waiting == 1)
        #expect(q.answered(b) == nil)
        #expect(q.answered(a)?.item == "B")
        #expect(q.shown?.token == b)
        #expect(q.waiting == 0)
    }

    @Test func theQueueHoldsThreeWaitingAndOnePlacePerAddress() {
        var q = ApprovalQueue<Int>()
        #expect(q.add(UUID(), from: "10.0.0.1", 1) == .shown)
        #expect(q.add(UUID(), from: "10.0.0.1", 2) == .full)
        #expect(q.add(UUID(), from: "10.0.0.2", 2) == .waiting)
        #expect(q.add(UUID(), from: "10.0.0.2", 3) == .full)
        for i in 3...4 { #expect(q.add(UUID(), from: "10.0.0.\(i)", i) == .waiting) }
        #expect(q.add(UUID(), from: "10.0.0.5", 5) == .full)
    }

    @Test func withdrawingTheShownRequestShowsTheNextAndAWaitingOneShowsNothing() {
        var q = ApprovalQueue<String>()
        let a = UUID(), b = UUID(), c = UUID()
        _ = q.add(a, from: "1", "A")
        _ = q.add(b, from: "2", "B")
        _ = q.add(c, from: "3", "C")
        #expect(q.withdrawn(b) == nil)
        #expect(q.waiting == 1)
        #expect(q.withdrawn(a)?.item == "C")
    }

    @Test func aDeniedAddressIsRefusedForThirtySeconds() {
        var c = PairCooldown()
        c.denied("10.0.0.9", at: 100)
        #expect(c.blocks("10.0.0.9", now: 129.9))
        #expect(!c.blocks("10.0.0.8", now: 110))
        #expect(!c.blocks("10.0.0.9", now: 130))
    }

    @Test func pairedPhonesShowTimesLastUseAndTheConnectedOne() {
        let utc = TimeZone(identifier: "UTC")!
        let phones = [
            PairedPhone(id: "a", name: "SM-F971Q", pairedAt: Date(timeIntervalSince1970: 1_792_647_240), lastUsedAt: nil),
            PairedPhone(id: "b", name: "SM-F971Q", pairedAt: Date(timeIntervalSince1970: 1_792_649_160), lastUsedAt: Date(timeIntervalSince1970: 1_792_939_920)),
            PairedPhone(id: "c", name: "Pixel", pairedAt: Date(timeIntervalSince1970: 1_792_649_160), lastUsedAt: nil),
        ]
        let rows = PairedPhoneRows.rows(phones, connectedId: "b", timeZone: utc)
        #expect(rows.map(\.title) == ["SM-F971Q — paired Oct 22, 5:34 AM", "SM-F971Q — paired Oct 22, 6:06 AM", "Pixel"])
        #expect(rows[0].details == ["Paired Oct 22, 2026 at 5:34 AM", "No use recorded"])
        #expect(rows[1].details == ["Paired Oct 22, 2026 at 6:06 AM", "Last used Oct 25, 2026 at 2:52 PM"])
        #expect(rows.map(\.connected) == [false, true, false])
    }
}

@Suite struct PosixTextTests {
    @Test func commonErrorsSayWhatToDo() {
        #expect(PosixText.menu(EADDRINUSE, port: 47800) == "Port 47800 is in use")
        #expect(PosixText.menu(ENETDOWN) == "The network is down — check Wi-Fi")
        #expect(PosixText.menu(ECONNRESET) == "Connection reset by peer")
    }

    @Test func logNamesAreShortWords() {
        #expect(PosixText.slug(ECONNRESET) == "connection_reset")
        #expect(PosixText.slug(EADDRINUSE) == "address_in_use")
        #expect(PosixText.slug(EIO) == "posix")
    }
}

@Suite struct HostStatusTests {
    @Test func aStoppedVideoAndAMatchFallbackAreSpelledOut() {
        let stopped = HostStatus.streamFailed(reason: "the encoder stopped").menu(missing: [])
        #expect(stopped.line == "Video stopped — the encoder stopped")
        #expect(stopped.icon == .attention)
        #expect(HostStatus.connected(name: "SM-F966Q", mode: "mirror", fallback: true).menu(missing: []).line == "Connected: SM-F966Q · Mirror (Match unavailable)")
    }

    @Test func waitingSaysTheHostIsReady() {
        let m = HostStatus.waiting.menu(missing: [])
        #expect(m.line == "Ready — waiting for a phone")
        #expect(m.icon == .idle)
        #expect(m.warnings.isEmpty)
    }

    @Test func aConnectedPhoneShowsItsNameAndTheMode() {
        #expect(HostStatus.connected(name: "SM-F966Q", mode: "extend").menu(missing: []).line == "Connected: SM-F966Q · Extend")
        #expect(HostStatus.connected(name: "SM-F966Q", mode: "mirror").menu(missing: []).line == "Connected: SM-F966Q · Mirror")
        #expect(HostStatus.connected(name: "SM-F966Q", mode: "match").menu(missing: []).line == "Connected: SM-F966Q · Match")
        #expect(HostStatus.connected(name: "SM-F966Q", mode: nil).menu(missing: []).line == "Connected: SM-F966Q")
        #expect(HostStatus.connected(name: "SM-F966Q", mode: "extend").menu(missing: []).icon == .connected)
    }

    @Test func oneMissingPermissionAddsOneWarningAndAsksForAttention() {
        let m = HostStatus.waiting.menu(missing: [.accessibility])
        #expect(m.line == "Ready — waiting for a phone")
        #expect(m.warnings == ["Accessibility is off — taps stay on the phone"])
        #expect(m.icon == .attention)
    }

    @Test func everyMissingPermissionGetsALineOfItsOwnScreenRecordingFirst() {
        let m = HostStatus.needsScreenRecording.menu(missing: [.accessibility, .screenRecording])
        #expect(m.line == "Waiting for Screen Recording")
        #expect(m.warnings == ["Screen Recording is off — open Setup…", "Accessibility is off — taps stay on the phone"])
        #expect(m.icon == .attention)
    }

    @Test func aPortInUseSaysSoAndAsksForAttention() {
        let m = HostStatus.failed(reason: "Port 47801 is in use").menu(missing: [])
        #expect(m.line == "Not running — Port 47801 is in use")
        #expect(m.icon == .attention)
    }

    @Test func aGrantThatNeedsARelaunchSaysSo() {
        let m = HostStatus.relaunchNeeded.menu(missing: [])
        #expect(m.line == "Relaunch Oripane to finish setup")
        #expect(m.icon == .attention)
    }

    @Test func aFullLengthPhoneNameIsShortenedForTheMenu() {
        let line = HostStatus.connected(name: String(repeating: "x", count: 64), mode: "extend").menu(missing: []).line
        #expect(line == "Connected: " + String(repeating: "x", count: 31) + "… · Extend")
    }
}

@Suite struct MacActionTests {
    @Test func everyActionIsReachedByItsWireName() {
        #expect(MacAction.allCases.count == 18)
        for a in MacAction.allCases { #expect(MacAction(rawValue: a.rawValue) == a) }
        #expect(MacAction(rawValue: "missionControl") == .missionControl)
        #expect(MacAction(rawValue: "notificationCenter") == nil)
    }

    @Test func missionControlAndSpacesUseControlArrowsWithFn() {
        let controlFn = MacAction.Flag.control | MacAction.Flag.fn
        #expect(MacAction.missionControl.plan == .shortcut(key: 0x7E, flags: controlFn))
        #expect(MacAction.appExpose.plan == .shortcut(key: 0x7D, flags: controlFn))
        #expect(MacAction.spaceLeft.plan == .shortcut(key: 0x7B, flags: controlFn))
        #expect(MacAction.spaceRight.plan == .shortcut(key: 0x7C, flags: controlFn))
        #expect(MacAction.showDesktop.plan == .shortcut(key: 0x67, flags: MacAction.Flag.fn))
    }

    @Test func systemShortcutsHoldTheirModifiers() {
        #expect(MacAction.launcher.plan == .shortcut(key: 0x31, flags: MacAction.Flag.command))
        #expect(MacAction.screenshot.plan == .shortcut(key: 0x17, flags: MacAction.Flag.command | MacAction.Flag.shift))
        #expect(MacAction.lockScreen.plan == .shortcut(key: 0x0C, flags: MacAction.Flag.control | MacAction.Flag.command))
        #expect(MacAction.previousApp.plan == .shortcut(key: 0x30, flags: MacAction.Flag.command))
    }

    @Test func mediaActionsAreTheirKeyTypes() {
        let types: [MacAction: Int] = [.volumeUp: 0, .volumeDown: 1, .brightnessUp: 2, .brightnessDown: 3, .mute: 7, .playPause: 16, .nextTrack: 17, .previousTrack: 18]
        for (a, t) in types { #expect(a.plan == .media(t)) }
    }

    @Test func appsOpensTheAppsApplication() {
        #expect(MacAction.apps.plan == .open("/System/Applications/Apps.app"))
    }
}

@Suite struct HeldKeysTests {
    let cmd = MacAction.Flag.command, shift = MacAction.Flag.shift, control = MacAction.Flag.control, fn = MacAction.Flag.fn
    let c: UInt16 = 0x08, v: UInt16 = 0x09

    @Test func aCommandFlagPressesCommandAroundTheKey() {
        var keys = HeldKeys()
        #expect(keys.press(c, flags: cmd) == [KeyStroke(0x37, down: true, flags: cmd), KeyStroke(c, down: true, flags: cmd)])
        #expect(keys.release(c, flags: cmd) == [KeyStroke(c, down: false, flags: cmd), KeyStroke(0x37, down: false, flags: 0)])
        #expect(keys.down.isEmpty)
    }

    @Test func aHeldRightCommandCarriesTheFlag() {
        var keys = HeldKeys()
        _ = keys.press(0x36, flags: cmd)
        #expect(keys.press(c, flags: cmd) == [KeyStroke(c, down: true, flags: cmd)])
        #expect(keys.release(c, flags: cmd) == [KeyStroke(c, down: false, flags: cmd)])
        #expect(keys.down == [0x36])
    }

    @Test func aModifierKeyCarriesItsOwnFlag() {
        var keys = HeldKeys()
        #expect(keys.press(0x37, flags: cmd) == [KeyStroke(0x37, down: true, flags: cmd)])
        #expect(keys.press(0x3C, flags: cmd | shift) == [KeyStroke(0x3C, down: true, flags: cmd | shift)])
        #expect(keys.down == [0x37, 0x3C])
    }

    @Test func commandAddedForAModifierKeyGoesWithIt() {
        var keys = HeldKeys()
        #expect(keys.press(0x38, flags: cmd | shift) == [KeyStroke(0x37, down: true, flags: cmd), KeyStroke(0x38, down: true, flags: cmd | shift)])
        #expect(keys.release(0x38, flags: cmd) == [KeyStroke(0x38, down: false, flags: cmd), KeyStroke(0x37, down: false, flags: 0)])
        #expect(keys.down.isEmpty)
    }

    @Test func shiftCommandPressesCommandFirstAndReleasesItLast() {
        var keys = HeldKeys()
        let both = cmd | shift
        #expect(keys.press(c, flags: both).map(\.code) == [0x37, 0x38, c])
        #expect(keys.release(c, flags: both).map(\.code) == [c, 0x38, 0x37])
    }

    @Test func aRepeatedDownAddsNothingAndTheReleaseLetsCommandGoOnce() {
        var keys = HeldKeys()
        _ = keys.press(c, flags: cmd)
        #expect(keys.press(c, flags: cmd) == [KeyStroke(c, down: true, flags: cmd)])
        #expect(keys.release(c, flags: cmd) == [KeyStroke(c, down: false, flags: cmd), KeyStroke(0x37, down: false, flags: 0)])
        #expect(keys.release(c, flags: cmd) == [KeyStroke(c, down: false, flags: cmd)])
    }

    @Test func overlappingKeysLetCommandGoWithTheLastOne() {
        var keys = HeldKeys()
        _ = keys.press(c, flags: cmd)
        #expect(keys.press(v, flags: cmd) == [KeyStroke(v, down: true, flags: cmd)])
        #expect(keys.release(c, flags: cmd) == [KeyStroke(c, down: false, flags: cmd)])
        #expect(keys.release(v, flags: cmd) == [KeyStroke(v, down: false, flags: cmd), KeyStroke(0x37, down: false, flags: 0)])
    }

    @Test func releaseAllLetsGoOfTheKeyAndTheAddedModifier() {
        var keys = HeldKeys()
        _ = keys.press(c, flags: cmd)
        #expect(keys.releaseAll() == [KeyStroke(c, down: false, flags: 0), KeyStroke(0x37, down: false, flags: 0)])
        #expect(keys.down.isEmpty)
        #expect(keys.releaseAll().isEmpty)
    }

    @Test func aKeyWithoutFlagsIsOneDownAndOneUp() {
        var keys = HeldKeys()
        #expect(keys.press(c, flags: 0) == [KeyStroke(c, down: true, flags: 0)])
        #expect(keys.release(c, flags: 0) == [KeyStroke(c, down: false, flags: 0)])
    }

    @Test func missionControlRidesFnOnEveryEvent() {
        var keys = HeldKeys()
        let flags = control | fn
        #expect(keys.press(0x7E, flags: flags) + keys.release(0x7E, flags: flags) == [
            KeyStroke(0x3B, down: true, flags: control | fn),
            KeyStroke(0x7E, down: true, flags: flags),
            KeyStroke(0x7E, down: false, flags: flags),
            KeyStroke(0x3B, down: false, flags: fn),
        ])
    }

    @Test func screenshotHoldsEachModifierOnItsOwnDown() {
        var keys = HeldKeys()
        let flags = cmd | shift
        #expect(keys.press(0x17, flags: flags) + keys.release(0x17, flags: flags) == [
            KeyStroke(0x37, down: true, flags: cmd),
            KeyStroke(0x38, down: true, flags: cmd | shift),
            KeyStroke(0x17, down: true, flags: flags),
            KeyStroke(0x17, down: false, flags: flags),
            KeyStroke(0x38, down: false, flags: cmd),
            KeyStroke(0x37, down: false, flags: 0),
        ])
    }
}

@Suite struct UnicodeChunksTests {
    func isHigh(_ u: UInt16) -> Bool { (0xD800...0xDBFF).contains(u) }
    func isLow(_ u: UInt16) -> Bool { (0xDC00...0xDFFF).contains(u) }

    @Test func anEmojiAtTheTwentiethUnitStaysWhole() {
        let runs = UnicodeChunks.split(String(repeating: "a", count: 19) + "😀")
        #expect(runs.map(\.count) == [19, 2])
        for r in runs {
            #expect(!isLow(r.first!))
            #expect(!isHigh(r.last!))
        }
        #expect(String(decoding: runs.joined(), as: UTF16.self) == String(repeating: "a", count: 19) + "😀")
    }

    @Test func plainTextFillsRunsOfTwenty() {
        #expect(UnicodeChunks.split(String(repeating: "x", count: 45)).map(\.count) == [20, 20, 5])
        #expect(UnicodeChunks.split("").isEmpty)
    }

    @Test func aCharacterLongerThanARunGoesAlone() {
        let family = "👨‍👩‍👧‍👦"
        #expect(family.utf16.count > 10)
        #expect(UnicodeChunks.split("ab" + family + "c", maxUnits: 10).map { String(decoding: $0, as: UTF16.self) } == ["ab", family, "c"])
    }
}

@Suite struct ScrollTests {
    @Test func mirrorOnTheCoverScreenScalesByTheLetterboxedVideo() {
        // 3840x2160 main display (1920x1080 points) fitted into a 1248x1972 panel: a 1248x702 stream.
        let s = ScrollScale.pointsPerPixel(displayHeight: 1080, clientWidth: 1248, clientHeight: 1972, streamWidth: 1248, streamHeight: 702)
        #expect(abs(s - 1080.0 / 702) < 1e-9)
    }

    @Test func aStreamShapedLikeThePanelScalesByThePanel() {
        let s = ScrollScale.pointsPerPixel(displayHeight: 1104, clientWidth: 1768, clientHeight: 2208, streamWidth: 884, streamHeight: 1104)
        #expect(abs(s - 1104.0 / 2208) < 1e-9)
    }

    @Test func slowTravelAddsUpInsteadOfRoundingAway() {
        var a = ScrollAccumulator()
        var total: Int32 = 0
        for _ in 0..<20 { total += a.take(0, 0.9 * 0.5).1 }
        #expect(total == 9)
    }

    @Test func aSmallSidewaysPartScrollsOnlyWhatItAddsUpTo() {
        var a = ScrollAccumulator()
        var sideways: Int32 = 0, down: Int32 = 0
        for _ in 0..<10 {
            let (x, y) = a.take(0.6, -6)
            sideways += x
            down += y
        }
        #expect(sideways == 6)
        #expect(down == -60)
    }

    @Test func aClampedValueDoesNotKeepScrolling() {
        var a = ScrollAccumulator()
        #expect(a.take(0, 1e30).1 == Int32.max)
        #expect(a.take(0, 0).1 == 0)
    }
}

@Suite struct FeedbackWindowTests {
    @Test func countsAddUpAndTimesAverage() {
        var w = FeedbackWindow()
        w.add(Feedback(lastFrameId: 1, packetsReceived: 100, packetsLost: 5, framesRecovered: 2, framesDropped: 0, decodeUs: 3000, totalUs: 10000))
        w.add(Feedback(lastFrameId: 2, packetsReceived: 80, packetsLost: 1, framesRecovered: 0, framesDropped: 1, decodeUs: 5000, totalUs: 14000))
        #expect(w.received == 180)
        #expect(w.lost == 6)
        #expect(w.recovered == 2)
        #expect(w.dropped == 1)
        #expect(w.meanTotalMs == 12)
        #expect(w.meanDecodeMs == 4)
        #expect(FeedbackWindow().meanTotalMs == 0)
        w.add(Feedback(lastFrameId: 3, packetsReceived: 10, packetsLost: 0, framesRecovered: 0, framesDropped: 0, decodeUs: 0, totalUs: 0))
        #expect(w.received == 190)
        #expect(w.meanTotalMs == 12)
    }
}

@Suite struct PacingTests {
    @Test func aKeyframeSpreadsOverHalfAFrame() {
        // 124 packets, as the owner's 1920x1080 keyframes: 8 runs, 7 pauses over 8,333 µs.
        #expect(Pacing.pauseUs(packets: 124, spreadUs: 8_333) == 1_190)
    }

    @Test func smallFramesGoAtOnce() {
        #expect(Pacing.pauseUs(packets: 32, spreadUs: 8_333) == 0)
        #expect(Pacing.pauseUs(packets: 5, spreadUs: 8_333) == 0)
        #expect(Pacing.pauseUs(packets: 124, spreadUs: 0) == 0)
    }
}
