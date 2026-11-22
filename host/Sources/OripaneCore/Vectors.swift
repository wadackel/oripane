import CryptoKit
import Foundation

/// Golden vectors shared with the Kotlin client's tests (`spec/vectors.json`). Every value is
/// derived from fixed inputs, so regenerating the file must not change it.
public enum Vectors {
    public static func generate() throws -> [String: Any] {
        let hostPriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x11, count: 32))
        let clientPriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x22, count: 32))
        let hostPub = hostPriv.publicKey.rawRepresentation
        let clientPub = clientPriv.publicKey.rawRepresentation
        let shared = try Handshake.sharedSecret(privateKey: hostPriv, peer: clientPub)
        let pairKey = Handshake.pairKey(shared: shared, hostPub: hostPub, clientPub: clientPub)
        let cn = Data(repeating: 0xC1, count: 32)
        let hn = Data(repeating: 0xA5, count: 32)
        let sessionKey = Handshake.sessionKey(pairKey: pairKey, clientNonce: cn, hostNonce: hn)
        let cipher = SessionCipher(key: sessionKey)

        let controlPlain = Data(#"{"t":"keyframe"}"#.utf8)
        let controlSealed = try cipher.seal(controlPlain, dir: .controlToHost, counter: 3)

        let frame = Data((0..<2500).map { UInt8($0 % 251) })
        let packets = Packetizer(groupSize: 2).packets(frame: frame, frameId: 7, keyframe: true, captureUs: 123_456_789, hostUs: 4321)
        let header = MediaHeader(type: .videoFragment, sessionId: 0xDEAD_BEEF, seq: 42)
        guard case let .fragment(firstFragment) = packets[0] else { throw WireError.malformed("vector layout") }
        let mediaSealed = try MediaCodec.seal(header, plaintext: firstFragment.encoded, cipher: cipher, dir: .mediaToClient)

        let input = InputPacket.encode([
            SequencedInput(seq: 9, event: .pointer(action: .down, x: 0.25, y: 0.75)),
            SequencedInput(seq: 10, event: .scroll(dx: -3.5, dy: 12)),
            SequencedInput(seq: 11, event: .rightClick(x: 0.5, y: 0.5)),
            SequencedInput(seq: 12, event: .pointer(action: .downContinued, x: 0.5, y: 0.25)),
        ])
        let feedback = Feedback(lastFrameId: 7, packetsReceived: 100, packetsLost: 2, framesRecovered: 1, framesDropped: 0, decodeUs: 3000, totalUs: 31000)

        return [
            "hostPriv": hostPriv.rawRepresentation.hex,
            "clientPriv": clientPriv.rawRepresentation.hex,
            "hostPub": hostPub.hex,
            "clientPub": clientPub.hex,
            "commitment": Handshake.commitment(clientPub: clientPub).hex,
            "shared": shared.hex,
            "sas": Handshake.sas(hostPub: hostPub, clientPub: clientPub),
            "pairKey": pairKey.hex,
            "clientNonce": cn.hex,
            "hostNonce": hn.hex,
            "hostProof": Handshake.proof(pairKey: pairKey, role: "host", clientNonce: cn, hostNonce: hn).hex,
            "clientProof": Handshake.proof(pairKey: pairKey, role: "client", clientNonce: cn, hostNonce: hn).hex,
            "sessionKey": sessionKey.hex,
            "control": ["dir": 2, "counter": 3, "plain": controlPlain.hex, "sealed": controlSealed.hex],
            "frame": ["bytes": frame.hex, "frameId": 7, "groupSize": 2, "captureUs": 123_456_789, "hostUs": 4321],
            "framePackets": packets.map { p -> [String: String] in
                switch p {
                case let .fragment(f): return ["type": "fragment", "plain": f.encoded.hex]
                case let .parity(q): return ["type": "parity", "plain": q.encoded.hex]
                }
            },
            "media": ["sessionId": 0xDEAD_BEEF, "seq": 42, "dir": 3, "plain": firstFragment.encoded.hex, "packet": mediaSealed.hex],
            "input": input.hex,
            "feedback": feedback.encoded.hex,
        ]
    }
}
