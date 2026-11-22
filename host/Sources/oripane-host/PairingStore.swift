import Foundation

/// Where the host keeps its pairings and identity: `ORIPANE_STATE_DIR`, or Application Support.
func stateDirectory() -> URL {
    let override = ProcessInfo.processInfo.environment["ORIPANE_STATE_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
    let dir = override ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("oripane", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    return dir
}

struct Pairing: Codable {
    var name: String
    var pairKey: Data
    var pairedAt: Date
    /// Optional, so a file written before it was kept still decodes: a failed decode starts empty.
    var lastUsedAt: Date? = nil
}

/// Paired clients by id, in a 0600 file under Application Support.
final class PairingStore {
    private let url: URL
    private var entries: [String: Pairing]
    private let lock = NSLock()

    init(directory: URL = stateDirectory()) {
        url = directory.appendingPathComponent("pairings.json")
        entries = (try? JSONDecoder().decode([String: Pairing].self, from: Data(contentsOf: url))) ?? [:]
    }

    func lookup(_ clientId: Data) -> Pairing? {
        lock.lock(); defer { lock.unlock() }
        return entries[clientId.base64EncodedString()]
    }

    func contains(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return entries[id] != nil
    }

    func add(_ clientId: Data, _ pairing: Pairing) {
        lock.lock(); defer { lock.unlock() }
        entries[clientId.base64EncodedString()] = pairing
        save()
    }

    /// A phone authenticated with this pairing now.
    func touch(_ id: String) {
        lock.lock(); defer { lock.unlock() }
        guard entries[id] != nil else { return }
        entries[id]?.lastUsedAt = Date()
        save()
    }

    func all() -> [(String, Pairing)] {
        lock.lock(); defer { lock.unlock() }
        return entries.sorted { $0.value.pairedAt < $1.value.pairedAt }.map { ($0.key, $0.value) }
    }

    @discardableResult
    func remove(_ id: String) -> Pairing? {
        lock.lock(); defer { lock.unlock() }
        guard let removed = entries.removeValue(forKey: id) else { return nil }
        save()
        return removed
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        entries = [:]
        save()
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        // Written on every authentication: a write cut short would leave a file that no longer
        // decodes, and the store would then start empty and save over every pairing.
        guard (try? data.write(to: url, options: .atomic)) != nil else { return }
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}
