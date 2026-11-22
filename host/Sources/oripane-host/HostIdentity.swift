import Foundation

/// The host's lasting identity, `host.json` next to the pairings. Pairing keys are made fresh per
/// pairing and the Bonjour name is just the computer name, so this id is what lets a phone know
/// the Mac it reaches over the tailnet is the one it paired with on the LAN.
enum HostIdentity {
    private struct File: Codable { var hostId: Data }

    static func load(directory: URL = stateDirectory()) -> Data {
        let url = directory.appendingPathComponent("host.json")
        if FileManager.default.fileExists(atPath: url.path) {
            if let f = try? JSONDecoder().decode(File.self, from: Data(contentsOf: url)), f.hostId.count == 16 { return f.hostId }
            log("host identity regenerated")
        }
        let id = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        // Unsaved, every start would look like a new Mac to the phones that know this one.
        let saved = (try? JSONEncoder().encode(File(hostId: id)))
            .map { FileManager.default.createFile(atPath: url.path, contents: $0, attributes: [.posixPermissions: 0o600]) } ?? false
        if !saved { log("host identity not_saved path=\(url.path)") }
        return id
    }
}
