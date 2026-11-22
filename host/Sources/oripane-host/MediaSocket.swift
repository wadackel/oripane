import Darwin
import Foundation
import OripaneCore

/// The UDP side: one socket, a blocking receive thread, and paced sends to whichever endpoint the
/// client last said hello from.
final class MediaSocket {
    private let fd: Int32
    private var peer: sockaddr_storage?
    private var peerLength: socklen_t = 0
    private let peerLock = NSLock()
    private var receiving = false
    var onPacket: ((Data, sockaddr_storage, socklen_t) -> Void)?
    /// Probability of dropping an outgoing packet, for exercising FEC and keyframe recovery.
    var dropRate: Double = 0

    init(port: UInt16) throws {
        fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw POSIXError(.init(rawValue: errno) ?? .EIO) }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        // A keyframe at phone resolution is hundreds of packets written in one burst.
        var buf: Int32 = 4 << 20
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buf, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf, socklen_t(MemoryLayout<Int32>.size))
        // Marks the traffic for the Wi-Fi driver's WMM access category; see sys/socket.h.
        var service: Int32 = NET_SERVICE_TYPE_RV
        setsockopt(fd, SOL_SOCKET, SO_NET_SERVICE_TYPE, &service, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY.bigEndian)
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard rc == 0 else {
            let e = errno
            close(fd)
            throw POSIXError(.init(rawValue: e) ?? .EADDRINUSE)
        }
    }

    deinit { close(fd) }

    func startReceiving() {
        guard !receiving else { return }
        receiving = true
        let t = Thread { [weak self] in self?.receiveLoop() }
        t.qualityOfService = .userInteractive
        t.name = "oripane.udp-recv"
        t.start()
    }

    private func receiveLoop() {
        var buffer = [UInt8](repeating: 0, count: 65536)
        while receiving {
            var from = sockaddr_storage()
            var len = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let n = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buffer, buffer.count, 0, $0, &len) }
            }
            if n <= 0 { continue }
            onPacket?(Data(buffer[0..<n]), from, len)
        }
    }

    func setPeer(_ addr: sockaddr_storage, _ len: socklen_t) {
        peerLock.lock(); defer { peerLock.unlock() }
        if peer == nil || !Self.same(peer!, addr) { log("udp peer=\(Self.describe(addr))") }
        peer = addr
        peerLength = len
    }

    func clearPeer() {
        peerLock.lock(); peer = nil; peerLock.unlock()
    }

    var hasPeer: Bool { peerLock.lock(); defer { peerLock.unlock() }; return peer != nil }

    /// Sends a burst, paced as `Pacing` says over `spreadUs`.
    func send(_ packets: [Data], spreadUs: Int = 0) {
        peerLock.lock(); let p = peer; let l = peerLength; peerLock.unlock()
        guard var to = p else { return }
        let pause = useconds_t(Pacing.pauseUs(packets: packets.count, spreadUs: spreadUs))
        for (i, packet) in packets.enumerated() {
            if i > 0, i % Pacing.run == 0, pause > 0 { usleep(pause) }
            if dropRate > 0, Double.random(in: 0..<1) < dropRate { continue }
            _ = packet.withUnsafeBytes { raw in
                withUnsafePointer(to: &to) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(fd, raw.baseAddress, raw.count, 0, $0, l) }
                }
            }
        }
    }

    static func describe(_ a: sockaddr_storage) -> String {
        var a = a
        let length = socklen_t(a.ss_len)
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        var serv = [CChar](repeating: 0, count: Int(NI_MAXSERV))
        withUnsafePointer(to: &a) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                _ = getnameinfo($0, length, &host, socklen_t(host.count), &serv, socklen_t(serv.count), NI_NUMERICHOST | NI_NUMERICSERV)
            }
        }
        return String(cString: host) + ":" + String(cString: serv)
    }

    static func same(_ a: sockaddr_storage, _ b: sockaddr_storage) -> Bool { describe(a) == describe(b) }
}
