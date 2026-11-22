import CryptoKit
import OripaneCore
import Foundation
import Network

/// The TCP control channel: listens (and advertises over Bonjour), runs pairing and
/// authentication in the clear, then hands an authenticated, sealed connection to the host.
final class ControlServer {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "oripane.control")
    private let store: PairingStore
    private let approver: Approver
    private var connections: [ObjectIdentifier: ControlConnection] = [:]
    var onEstablished: ((ControlConnection) -> Void)?
    var onReady: (() -> Void)?
    /// Without it a failed listener ends the process, as a command run from a terminal should.
    var onFailure: ((NWError) -> Void)?

    init(port: UInt16, store: PairingStore, approver: Approver, advertise: Bool, name: String, hostId: Data) throws {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.noDelay = true
            // A phone that vanished while the pairing question is open has no session to time out.
            tcp.enableKeepalive = true
            tcp.keepaliveIdle = 5
            tcp.keepaliveInterval = 2
            tcp.keepaliveCount = 3
        }
        listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
        self.store = store
        self.approver = approver
        if advertise {
            // The TXT `id` lets a phone match this service to a Mac it saved under another address.
            listener.service = NWListener.Service(name: name, type: "_oripane._tcp", txtRecord: NWTXTRecord(["id": hostId.base64EncodedString()]))
        }
    }

    func start() {
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                log("control listening port=\(self.listener.port?.rawValue ?? 0)")
                self.onReady?()
            case let .failed(e):
                log("control listener_failed reason=\(logReason(e))")
                guard let onFailure = self.onFailure else { exit(1) }
                onFailure(e)
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] nw in
            guard let self else { return }
            let c = ControlConnection(nw: nw, queue: self.queue, store: self.store, approver: self.approver)
            self.connections[ObjectIdentifier(c)] = c
            c.onEstablished = { [weak self] c in self?.onEstablished?(c) }
            c.onClose = { [weak self] c in self?.connections[ObjectIdentifier(c)] = nil }
            c.start()
        }
        listener.start(queue: queue)
    }

    func stop() {
        listener.cancel()
    }
}

/// A phone asking to pair: its cleaned name, the code both screens show, and the address it came from.
struct PairRequest {
    let name: String
    let code: String
    let from: String
}

/// `refused`: turned away without asking the owner, which does not count as their denial.
enum PairOutcome { case allowed, denied, refused }

/// Asks the person at the Mac to confirm a pairing code. The returned closure withdraws the
/// question when the phone gives up first: an Allow then would reach no one.
protocol Approver: AnyObject {
    func approve(_ request: PairRequest, completion: @escaping (PairOutcome) -> Void) -> () -> Void
}

final class ControlConnection {
    enum State {
        case fresh
        case pairing(priv: Curve25519.KeyAgreement.PrivateKey, commit: Data, name: String)
        case awaitingApproval
        case awaitingAuthHello
        case authenticating(clientId: Data, pairKey: Data, cn: Data, hn: Data, name: String)
        case established
        case closed
    }

    private let nw: NWConnection
    private let queue: DispatchQueue
    private let store: PairingStore
    private let approver: Approver
    private var buffer = FrameBuffer()
    private var state = State.fresh
    private var cipher: ControlCipher?
    private(set) var sessionKey = Data()
    private(set) var clientName = ""
    /// The pairing this connection authenticated with, base64 as in pairings.json.
    private(set) var clientId = ""
    private var withdrawApproval: (() -> Void)?
    let peer: String
    /// The peer's address without its port.
    private let peerHost: String
    var onEstablished: ((ControlConnection) -> Void)?
    var onClose: ((ControlConnection) -> Void)?
    var onMessage: (([String: Any]) -> Void)?
    var onDisconnect: (() -> Void)?

    init(nw: NWConnection, queue: DispatchQueue, store: PairingStore, approver: Approver) {
        self.nw = nw
        self.queue = queue
        self.store = store
        self.approver = approver
        peer = "\(nw.endpoint)"
        if case let .hostPort(host, _) = nw.endpoint { peerHost = "\(host)" } else { peerHost = peer }
    }

    func start() {
        log("control connection peer=\(peer)")
        nw.stateUpdateHandler = { [weak self] st in
            if case .failed = st { self?.close(reason: "failed") }
            if case .cancelled = st { self?.close(reason: "cancelled") }
        }
        nw.start(queue: queue)
        receive()
    }

    private func receive() {
        nw.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, done, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                do {
                    while let frame = try self.buffer.next() { try self.handle(frame) }
                } catch {
                    log("control protocol_error peer=\(self.peer) error=\(error)")
                    self.close(reason: "protocol error")
                    return
                }
            }
            if done || error != nil { self.close(reason: error.map(logReason) ?? "eof"); return }
            self.receive()
        }
    }

    func send(_ message: [String: Any]) {
        queue.async { [self] in
            guard case .established = state, var c = cipher else { return }
            do {
                let sealed = try c.seal(Json.encode(message))
                cipher = c
                nw.send(content: FrameBuffer.frame(sealed), completion: .contentProcessed { _ in })
            } catch {
                log("control seal_failed error=\(error)")
            }
        }
    }

    /// Like `refuse`, for a sealed message after authentication.
    func sendThenClose(_ message: [String: Any], reason: String) {
        queue.async { [self] in
            guard case .established = state, var c = cipher, let sealed = try? c.seal(Json.encode(message)) else {
                close(reason: reason)
                return
            }
            cipher = c
            nw.send(content: FrameBuffer.frame(sealed), completion: .contentProcessed { [weak self] _ in
                self?.queue.async { self?.close(reason: reason) }
            })
        }
    }

    private func sendPlain(_ message: [String: Any]) {
        nw.send(content: FrameBuffer.frame(Json.encode(message)), completion: .contentProcessed { _ in })
    }

    /// A refusal has to reach the client before the connection goes; cancelling right after
    /// send() discards what is still queued, and the client sees a bare EOF.
    private func refuse(_ message: [String: Any], reason: String) {
        nw.send(content: FrameBuffer.frame(Json.encode(message)), completion: .contentProcessed { [weak self] _ in
            self?.queue.async { self?.close(reason: reason) }
        })
    }

    /// `close` for callers off the connection's queue.
    func disconnect(reason: String) {
        queue.async { [self] in close(reason: reason) }
    }

    func close(reason: String) {
        if case .closed = state { return }
        // An answered request has no withdrawal left; a denial closes from this state too.
        if case .awaitingApproval = state, let withdraw = withdrawApproval {
            log("pairing cancelled peer=\(peer) reason=\(reason)")
            withdraw()
        }
        withdrawApproval = nil
        let wasEstablished: Bool
        if case .established = state { wasEstablished = true } else { wasEstablished = false }
        state = .closed
        log("control closed peer=\(peer) reason=\(reason)")
        nw.cancel()
        if wasEstablished { onDisconnect?() }
        onClose?(self)
    }

    private func handle(_ frame: Data) throws {
        if case .established = state {
            guard var c = cipher else { return }
            let plain = try c.open(frame)
            cipher = c
            onMessage?(try Json.decode(plain))
            return
        }
        let msg = try Json.decode(frame)
        let t = msg["t"] as? String ?? ""
        switch (state, t) {
        case (.fresh, "pair_commit"):
            guard let commit = b64(msg["commit"]), commit.count == 32 else { throw WireError.malformed("commit") }
            let priv = Curve25519.KeyAgreement.PrivateKey()
            // Cleaned here, where it arrives: the name reaches the log, the prompt or dialog, and pairings.json.
            let name = PairPrompt.printable(msg["name"] as? String ?? "client").prefix(64)
            state = .pairing(priv: priv, commit: commit, name: String(name))
            sendPlain(["t": "pair_hostkey", "hostPub": priv.publicKey.rawRepresentation.base64EncodedString()])

        case let (.pairing(priv, commit, name), "pair_reveal"):
            guard let clientPub = b64(msg["clientPub"]), clientPub.count == 32,
                  Handshake.commitment(clientPub: clientPub) == commit else {
                log("pairing commitment_mismatch peer=\(peer)")
                refuse(["t": "pair_denied"], reason: "commitment mismatch")
                return
            }
            let hostPub = priv.publicKey.rawRepresentation
            let shared = try Handshake.sharedSecret(privateKey: priv, peer: clientPub)
            let pairKey = Handshake.pairKey(shared: shared, hostPub: hostPub, clientPub: clientPub)
            let code = Handshake.sas(hostPub: hostPub, clientPub: clientPub)
            state = .awaitingApproval
            log("pairing requested peer=\(peer) name=\(name) code=\(code)")
            withdrawApproval = approver.approve(PairRequest(name: name, code: code, from: peerHost)) { [weak self] outcome in
                self?.queue.async {
                    guard let self, case .awaitingApproval = self.state else { return }
                    self.withdrawApproval = nil
                    if outcome == .allowed {
                        let clientId = Handshake.randomBytes(16)
                        self.store.add(clientId, Pairing(name: name, pairKey: pairKey, pairedAt: Date()))
                        log("pairing approved name=\(name)")
                        self.state = .awaitingAuthHello
                        self.sendPlain(["t": "pair_ok", "clientId": clientId.base64EncodedString()])
                    } else {
                        log("pairing denied name=\(name)")
                        self.refuse(["t": "pair_denied"], reason: "pairing denied")
                    }
                }
            }

        case (.fresh, "auth_hello"), (.awaitingAuthHello, "auth_hello"):
            guard let clientId = b64(msg["clientId"]), let cn = b64(msg["nonce"]), cn.count == 32 else {
                throw WireError.malformed("auth_hello")
            }
            guard let pairing = store.lookup(clientId) else {
                log("auth unknown_client peer=\(peer)")
                refuse(["t": "auth_unknown"], reason: "unknown client")
                return
            }
            let hn = Handshake.randomBytes(32)
            state = .authenticating(clientId: clientId, pairKey: pairing.pairKey, cn: cn, hn: hn, name: pairing.name)
            sendPlain([
                "t": "auth_challenge",
                "nonce": hn.base64EncodedString(),
                "proof": Handshake.proof(pairKey: pairing.pairKey, role: "host", clientNonce: cn, hostNonce: hn).base64EncodedString(),
            ])

        case let (.authenticating(id, pairKey, cn, hn, name), "auth_proof"):
            guard let proof = b64(msg["proof"]),
                  Handshake.verify(proof: proof, pairKey: pairKey, role: "client", clientNonce: cn, hostNonce: hn) else {
                log("auth bad_proof peer=\(peer)")
                close(reason: "bad proof")
                return
            }
            sessionKey = Handshake.sessionKey(pairKey: pairKey, clientNonce: cn, hostNonce: hn)
            cipher = ControlCipher(sessionKey: sessionKey, isHost: true)
            clientName = name
            clientId = id.base64EncodedString()
            state = .established
            log("auth ok peer=\(peer) name=\(name)")
            onEstablished?(self)

        default:
            throw WireError.malformed("unexpected \(t) in \(state)")
        }
    }

    private func b64(_ v: Any?) -> Data? {
        (v as? String).flatMap { Data(base64Encoded: $0) }
    }
}
