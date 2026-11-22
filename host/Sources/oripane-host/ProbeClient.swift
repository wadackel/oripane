import CryptoKit
import Darwin
import OripaneCore
import Foundation

/// A headless client for checking a host without the phone: pairs (or reuses a saved pairing),
/// streams for a while, writes the reassembled H.264 or HEVC to a file and reports what arrived.
struct ProbeClient {
    var host = "127.0.0.1"
    var controlPort: UInt16 = 47800
    var mediaPort: UInt16 = 47801
    var width = 1768
    var height = 2208
    var mode = "extend"
    var seconds = 5.0
    var output = "probe.h264"
    var stateFile = "probe-pairing.json"
    var sendInput = false
    /// What `display` offers in `codecs`; empty leaves the host on H.264.
    var codecs: [String] = []
    /// Sends a second display configuration this many seconds in, as a fold or rotation would.
    var resize: (after: Double, width: Int, height: Int)?

    struct Saved: Codable { var clientId: Data; var pairKey: Data }

    func run() throws {
        let tcp = try connectTCP()
        defer { close(tcp) }
        var buffer = FrameBuffer()
        func send(_ obj: [String: Any]) throws { try writeAll(tcp, FrameBuffer.frame(Json.encode(obj))) }
        func recv() throws -> Data {
            while true {
                if let f = try buffer.next() { return f }
                var chunk = [UInt8](repeating: 0, count: 65536)
                let n = Darwin.recv(tcp, &chunk, chunk.count, 0)
                guard n > 0 else { throw WireError.truncated }
                buffer.append(Data(chunk[0..<n]))
            }
        }

        var saved = (try? JSONDecoder().decode(Saved.self, from: Data(contentsOf: URL(fileURLWithPath: stateFile))))
        if saved == nil {
            let priv = Curve25519.KeyAgreement.PrivateKey()
            let pub = priv.publicKey.rawRepresentation
            try send(["t": "pair_commit", "v": 1, "name": "probe", "commit": Handshake.commitment(clientPub: pub).base64EncodedString()])
            let hk = try Json.decode(try recv())
            guard let hostPub = (hk["hostPub"] as? String).flatMap({ Data(base64Encoded: $0) }) else { throw WireError.malformed("pair_hostkey") }
            try send(["t": "pair_reveal", "clientPub": pub.base64EncodedString()])
            print("probe sas=\(Handshake.sas(hostPub: hostPub, clientPub: pub))")
            let ok = try Json.decode(try recv())
            guard ok["t"] as? String == "pair_ok", let id = (ok["clientId"] as? String).flatMap({ Data(base64Encoded: $0) }) else {
                throw WireError.malformed("pairing: \(ok)")
            }
            let shared = try Handshake.sharedSecret(privateKey: priv, peer: hostPub)
            saved = Saved(clientId: id, pairKey: Handshake.pairKey(shared: shared, hostPub: hostPub, clientPub: pub))
            try JSONEncoder().encode(saved).write(to: URL(fileURLWithPath: stateFile))
            print("probe paired")
        }
        let pairing = saved!
        let cn = Handshake.randomBytes(32)
        try send(["t": "auth_hello", "v": 1, "clientId": pairing.clientId.base64EncodedString(), "nonce": cn.base64EncodedString()])
        let ch = try Json.decode(try recv())
        guard let hn = (ch["nonce"] as? String).flatMap({ Data(base64Encoded: $0) }),
              let hp = (ch["proof"] as? String).flatMap({ Data(base64Encoded: $0) }),
              Handshake.verify(proof: hp, pairKey: pairing.pairKey, role: "host", clientNonce: cn, hostNonce: hn) else {
            throw WireError.malformed("auth_challenge: \(ch)")
        }
        try send(["t": "auth_proof", "proof": Handshake.proof(pairKey: pairing.pairKey, role: "client", clientNonce: cn, hostNonce: hn).base64EncodedString()])
        let key = Handshake.sessionKey(pairKey: pairing.pairKey, clientNonce: cn, hostNonce: hn)
        var control = ControlCipher(sessionKey: key, isHost: false)
        func sendSealed(_ obj: [String: Any]) throws { try writeAll(tcp, FrameBuffer.frame(try control.seal(Json.encode(obj)))) }
        let okMsg = try Json.decode(try control.open(try recv()))
        guard let sid = okMsg["sessionId"] as? Int else { throw WireError.malformed("auth_ok: \(okMsg)") }
        print("probe session=\(sid)")

        let cipher = SessionCipher(key: key)
        let udp = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        var tv = timeval(tv_sec: 0, tv_usec: 100_000)
        setsockopt(udp, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var rcv: Int32 = 4 << 20
        setsockopt(udp, SOL_SOCKET, SO_RCVBUF, &rcv, socklen_t(MemoryLayout<Int32>.size))
        var to = sockaddr_in()
        to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = mediaPort.bigEndian
        inet_pton(AF_INET, host, &to.sin_addr)
        var seq: UInt64 = 0
        func sendMedia(_ type: MediaType, _ plain: Data) {
            let pkt = try! MediaCodec.seal(MediaHeader(type: type, sessionId: UInt32(sid), seq: seq), plaintext: plain, cipher: cipher, dir: .mediaToHost)
            seq += 1
            _ = pkt.withUnsafeBytes { raw in withUnsafePointer(to: &to) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(udp, raw.baseAddress, raw.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } } }
        }
        sendMedia(.hello, Data())
        try sendSealed(["t": "display", "w": width, "h": height, "dpi": 420, "hz": 60, "mode": mode, "codecs": codecs])

        var reassembler = Reassembler()
        var out = Data()
        var frames = 0, keyframes = 0, packets = 0, lost = 0, recovered = 0, maxSeq: UInt64?
        var latencies: [Double] = []
        var needKey = true
        var lastKeyRequest = Date.distantPast
        let start = Date()
        var lastHello = Date()
        var inputSeq: UInt32 = 0
        var buf = [UInt8](repeating: 0, count: 65536)
        var resized = false
        var streams = 0
        while Date().timeIntervalSince(start) < seconds {
            if let r = resize, !resized, Date().timeIntervalSince(start) > r.after {
                resized = true
                try sendSealed(["t": "display", "w": r.width, "h": r.height, "dpi": 420, "hz": 60, "mode": mode, "codecs": codecs])
                needKey = true
            }
            if Date().timeIntervalSince(lastHello) > 1 { sendMedia(.hello, Data()); lastHello = Date() }
            let n = Darwin.recv(udp, &buf, buf.count, 0)
            guard n > 0 else { continue }
            guard let (h, plain) = try? MediaCodec.open(Data(buf[0..<n]), cipher: cipher, dir: .mediaToClient) else { continue }
            packets += 1
            if let m = maxSeq, h.seq > m + 1 { lost += Int(h.seq - m - 1) }
            maxSeq = max(maxSeq ?? 0, h.seq)
            let packet: FramePacket
            switch h.type {
            case .videoFragment: packet = .fragment(try VideoFragment.decode(plain))
            case .videoParity: packet = .parity(try VideoParity.decode(plain))
            default: continue
            }
            let dropsBefore = reassembler.framesDropped
            guard let frame = reassembler.add(packet) else { continue }
            if reassembler.framesDropped > dropsBefore { needKey = true }
            if frame.recovered { recovered += 1 }
            if needKey && !frame.info.keyframe {
                if Date().timeIntervalSince(lastKeyRequest) > 0.2 { try sendSealed(["t": "keyframe"]); lastKeyRequest = Date() }
                continue
            }
            needKey = false
            frames += 1
            if frame.info.keyframe { keyframes += 1 }
            // Same machine, same clock: capture → reassembled is directly measurable here.
            latencies.append(Double(Int64(monotonicMicros()) - Int64(frame.info.captureUs)) / 1000)
            out.append(frame.data)
            if sendInput && frames % 30 == 0 {
                inputSeq += 1
                let x = Float(frames % 300) / 300
                sendMedia(.input, InputPacket.encode([SequencedInput(seq: inputSeq, event: .pointer(action: .move, x: x, y: 0.5))]))
            }
        }
        try sendSealed(["t": "bye"])
        try out.write(to: URL(fileURLWithPath: output))
        latencies.sort()
        let p = { (q: Double) in latencies.isEmpty ? 0 : latencies[min(latencies.count - 1, Int(Double(latencies.count) * q))] }
        _ = streams
        print(String(format: "probe frames=%d keyframes=%d fps=%.1f packets=%d lost=%d recovered=%d dropped=%d capture_to_reassembled_ms p50=%.1f p90=%.1f p99=%.1f bytes=%d",
                     frames, keyframes, Double(frames) / seconds, packets, lost, recovered, reassembler.framesDropped, p(0.5), p(0.9), p(0.99), out.count))
    }

    private func connectTCP() throws -> Int32 {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = controlPort.bigEndian
        inet_pton(AF_INET, host, &addr.sin_addr)
        let rc = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        guard rc == 0 else { throw POSIXError(.ECONNREFUSED) }
        var one: Int32 = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        return fd
    }

    private func writeAll(_ fd: Int32, _ data: Data) throws {
        try data.withUnsafeBytes { raw in
            var off = 0
            while off < raw.count {
                let n = write(fd, raw.baseAddress! + off, raw.count - off)
                guard n > 0 else { throw POSIXError(.EPIPE) }
                off += n
            }
        }
    }
}
