import CryptoKit
import Foundation

public enum Direction: UInt8 {
    case controlToClient = 0x01
    case controlToHost = 0x02
    case mediaToClient = 0x03
    case mediaToHost = 0x04
}

public struct SessionCipher {
    private let key: SymmetricKey

    public init(key: Data) { self.key = SymmetricKey(data: key) }

    public static func nonce(_ dir: Direction, _ counter: UInt64) -> Data {
        var w = ByteWriter(capacity: 12)
        w.u8(dir.rawValue)
        w.bytes([0, 0, 0])
        w.u64(counter)
        return w.data
    }

    public func seal(_ plaintext: Data, dir: Direction, counter: UInt64, aad: Data = Data()) throws -> Data {
        let nonce = try AES.GCM.Nonce(data: Self.nonce(dir, counter))
        let box = try AES.GCM.seal(plaintext, using: key, nonce: nonce, authenticating: aad)
        return box.ciphertext + box.tag
    }

    public func open(_ sealed: Data, dir: Direction, counter: UInt64, aad: Data = Data()) throws -> Data {
        guard sealed.count >= 16 else { throw WireError.truncated }
        let split = sealed.endIndex - 16
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: Self.nonce(dir, counter)),
                ciphertext: sealed[sealed.startIndex..<split],
                tag: sealed[split..<sealed.endIndex]
            )
            return try AES.GCM.open(box, using: key, authenticating: aad)
        } catch {
            throw WireError.authentication
        }
    }
}

public enum Handshake {
    public static func commitment(clientPub: Data) -> Data {
        Data(SHA256.hash(data: clientPub))
    }

    public static func sas(hostPub: Data, clientPub: Data) -> String {
        let digest = Data(SHA256.hash(data: Data("oripane-sas".utf8) + hostPub + clientPub))
        let v = digest.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return String(format: "%06u", v % 1_000_000)
    }

    public static func sharedSecret(privateKey: Curve25519.KeyAgreement.PrivateKey, peer: Data) throws -> Data {
        let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peer)
        let secret = try privateKey.sharedSecretFromKeyAgreement(with: pub)
        return secret.withUnsafeBytes { Data($0) }
    }

    public static func pairKey(shared: Data, hostPub: Data, clientPub: Data) -> Data {
        hkdf(ikm: shared, salt: hostPub + clientPub, info: "oripane-pair")
    }

    public static func proof(pairKey: Data, role: String, clientNonce: Data, hostNonce: Data) -> Data {
        let mac = HMAC<SHA256>.authenticationCode(
            for: Data(role.utf8) + clientNonce + hostNonce,
            using: SymmetricKey(data: pairKey)
        )
        return Data(mac)
    }

    public static func verify(proof: Data, pairKey: Data, role: String, clientNonce: Data, hostNonce: Data) -> Bool {
        HMAC<SHA256>.isValidAuthenticationCode(
            proof,
            authenticating: Data(role.utf8) + clientNonce + hostNonce,
            using: SymmetricKey(data: pairKey)
        )
    }

    public static func sessionKey(pairKey: Data, clientNonce: Data, hostNonce: Data) -> Data {
        hkdf(ikm: pairKey, salt: clientNonce + hostNonce, info: "oripane-session")
    }

    public static func randomBytes(_ n: Int) -> Data {
        var g = SystemRandomNumberGenerator()
        return Data((0..<n).map { _ in UInt8.random(in: 0...255, using: &g) })
    }

    static func hkdf(ikm: Data, salt: Data, info: String) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: salt,
            info: Data(info.utf8),
            outputByteCount: 32
        )
        return key.withUnsafeBytes { Data($0) }
    }
}
