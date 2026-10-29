import Foundation
import Observation
import PolyphonyKit

/// Songs kept on this Mac for offline play: the original files, in Application Support, one folder
/// per server, with PolyphonyKit's DownloadIndex beside them. The player prefers a downloaded copy.
@MainActor
@Observable
final class Downloads {
    private(set) var index = DownloadIndex()
    /// Songs waiting or downloading, by track id, with how far each has got (0–1).
    private(set) var active: [String: Double] = [:]
    private(set) var failed: Set<String> = []

    @ObservationIgnored private var folder: URL?
    @ObservationIgnored private var queue: [Track] = []
    @ObservationIgnored private var running = false
    @ObservationIgnored private var source: ((Track) -> URL)?

    /// Called on sign-in: this server's downloads, and where to fetch new ones from.
    func attach(server: SavedServer, fetchURL: @escaping (Track) -> URL) {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Polyphony/Downloads", isDirectory: true)
        let name = (server.address.host ?? "server") + "-" + server.subsonicUsername
        let dir = base.appendingPathComponent(name.filter { $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        folder = dir
        source = fetchURL
        if let data = try? Data(contentsOf: dir.appendingPathComponent("index.json")),
           let saved = try? JSONDecoder().decode(DownloadIndex.self, from: data) {
            index = saved
        } else {
            index = DownloadIndex()
        }
    }

    func detach() {
        queue = []
        active = [:]
        folder = nil
        source = nil
        index = DownloadIndex()
    }

    var usedBytes: Int64 { index.usedBytes }

    func isDownloaded(_ trackID: String) -> Bool { index.contains(trackID) }

    /// The downloaded copy, when there is one and it's still on disk.
    func localURL(_ trackID: String) -> URL? {
        guard let folder, let file = index.file(for: trackID) else { return nil }
        let url = folder.appendingPathComponent(file)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func download(_ tracks: [Track]) {
        let new = tracks.filter { !index.contains($0.id) && active[$0.id] == nil }
        guard !new.isEmpty else { return }
        for t in new {
            active[t.id] = 0
            failed.remove(t.id)
        }
        queue += new
        if !running { Task { await run() } }
    }

    func remove(_ trackIDs: [String]) {
        guard let folder else { return }
        for id in trackIDs {
            if let file = index.remove(id) { try? FileManager.default.removeItem(at: folder.appendingPathComponent(file)) }
            active[id] = nil
            queue.removeAll { $0.id == id }
        }
        save()
    }

    /// One at a time: a library's worth queued shouldn't swamp the server or the network.
    private func run() async {
        running = true
        defer { running = false }
        while !queue.isEmpty {
            let track = queue.removeFirst()
            guard let folder, let url = source?(track) else { break }
            do {
                let (tmp, response) = try await URLSession.shared.download(from: url)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                    throw URLError(.badServerResponse)
                }
                let file = DownloadIndex.fileName(for: track, format: track.container)
                let target = folder.appendingPathComponent(file)
                try? FileManager.default.removeItem(at: target)
                try FileManager.default.moveItem(at: tmp, to: target)
                let size = try? FileManager.default.attributesOfItem(atPath: target.path)[.size] as? NSNumber
                index.add(track, file: file, bytes: size?.int64Value ?? 0)
                save()
            } catch {
                failed.insert(track.id)
            }
            active[track.id] = nil
        }
    }

    private func save() {
        guard let folder, let data = try? JSONEncoder().encode(index) else { return }
        try? data.write(to: folder.appendingPathComponent("index.json"), options: .atomic)
    }
}
