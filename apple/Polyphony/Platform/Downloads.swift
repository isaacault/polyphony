import Foundation
import Observation
import PolyphonyKit

/// Songs kept on this Mac for offline play: the original files, in Application Support, one folder
/// per server, with PolyphonyKit's DownloadIndex beside them. The player prefers a downloaded copy.
/// Download rules (favourites, a playlist, the newest albums…) keep themselves downloaded: they're
/// looked at again on sign-in, every hour, and whenever one changes.
@MainActor
@Observable
final class Downloads {
    private(set) var index = DownloadIndex()
    /// Songs waiting or downloading, by track id, with how far each has got (0–1).
    private(set) var active: [String: Double] = [:]
    private(set) var failed: Set<String> = []
    private(set) var rules: [DownloadRule] = []
    private(set) var checkingRules = false
    private(set) var rulesCheckedAt: Date?
    /// Most the rules may keep on this Mac, in GB (0: no limit). Downloads by hand don't count
    /// against what's removed, but do take up the room.
    private(set) var limitGB: Int = UserDefaults.standard.integer(forKey: "downloads.limitGB")

    private var limitBytes: Int64 { Int64(limitGB) * 1_000_000_000 }

    func setLimit(gb: Int) {
        limitGB = gb
        UserDefaults.standard.set(gb, forKey: "downloads.limitGB")
        enforceLimit()
        Task { await checkRules() }
    }

    /// Played from here: the storage limit keeps what's played.
    func played(_ trackID: String) {
        guard index.contains(trackID) else { return }
        index.touch(trackID)
        save()
    }

    private func enforceLimit() {
        let evict = index.evictions(limitBytes: limitBytes)
        if !evict.isEmpty { remove(evict) }
    }

    @ObservationIgnored private var folder: URL?
    @ObservationIgnored private var queue: [(track: Track, ruleID: String?)] = []
    @ObservationIgnored private var running = false
    /// Where to fetch a song from, and the format it comes in (its own, or a transcode).
    @ObservationIgnored private var source: ((Track) -> (URL, String?))?
    @ObservationIgnored private var library: SubsonicClient?
    @ObservationIgnored private var hourly: Task<Void, Never>?
    @ObservationIgnored private var soon: Task<Void, Never>?
    /// Bumped on every sign-in and sign-out, so a rules check that outlives its server is dropped.
    @ObservationIgnored private var generation = 0

    /// Called on sign-in: this server's downloads and rules, and where to fetch new ones from.
    func attach(server: SavedServer, library: SubsonicClient, fetchURL: @escaping (Track) -> (URL, String?)) {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Polyphony/Downloads", isDirectory: true)
        let name = (server.address.host ?? "server") + "-" + server.subsonicUsername
        let dir = base.appendingPathComponent(name.filter { $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        generation += 1
        folder = dir
        source = fetchURL
        self.library = library
        index = (try? Data(contentsOf: dir.appendingPathComponent("index.json")))
            .flatMap { try? JSONDecoder().decode(DownloadIndex.self, from: $0) } ?? DownloadIndex()
        rules = (try? Data(contentsOf: dir.appendingPathComponent("rules.json")))
            .flatMap { try? JSONDecoder().decode([DownloadRule].self, from: $0) } ?? []
        hourly?.cancel()
        hourly = Task { [weak self] in
            while !Task.isCancelled {
                await self?.checkRules()
                try? await Task.sleep(for: .seconds(3600))
            }
        }
    }

    func detach() {
        generation += 1
        hourly?.cancel()
        soon?.cancel()
        queue = []
        active = [:]
        folder = nil
        source = nil
        library = nil
        index = DownloadIndex()
        rules = []
    }

    var usedBytes: Int64 { index.usedBytes }

    func isDownloaded(_ trackID: String) -> Bool { index.contains(trackID) }

    /// The downloaded copy, when there is one and it's still on disk.
    func localURL(_ trackID: String) -> URL? {
        guard let folder, let file = index.file(for: trackID) else { return nil }
        let url = folder.appendingPathComponent(file)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Downloads by hand: kept until removed by hand, whatever the rules do (a song a rule already
    /// keeps becomes the user's).
    func download(_ tracks: [Track]) {
        var changed = false
        for t in tracks where index.entries[t.id]?.pinned == false {
            index.setRule(t.id, nil)
            changed = true
        }
        if changed { save() }
        enqueue(tracks.map { ($0, nil) })
    }

    func remove(_ trackIDs: [String]) {
        guard let folder else { return }
        for id in trackIDs {
            if let file = index.remove(id) { try? FileManager.default.removeItem(at: folder.appendingPathComponent(file)) }
            active[id] = nil
            queue.removeAll { $0.track.id == id }
        }
        save()
    }

    // MARK: Rules

    func rule(for target: DownloadRule.Target) -> DownloadRule? { rules.first { $0.target == target } }

    func keep(_ target: DownloadRule.Target, maxTracks: Int = 0) {
        guard rule(for: target) == nil else { return }
        rules.append(DownloadRule(target: target, maxTracks: maxTracks))
        rulesChanged()
    }

    /// Stops a rule; the songs only it kept go.
    func stopKeeping(_ ruleID: String) {
        rules.removeAll { $0.id == ruleID }
        rulesChanged()
    }

    func setEnabled(_ ruleID: String, _ enabled: Bool) {
        guard let i = rules.firstIndex(where: { $0.id == ruleID }) else { return }
        rules[i].enabled = enabled
        rulesChanged()
    }

    /// Something a rule may follow changed (a favourite, a playlist): look again shortly, once.
    func libraryChanged() {
        guard !rules.isEmpty else { return }
        soon?.cancel()
        soon = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            await self?.checkRules()
        }
    }

    private func rulesChanged() {
        if let folder, let data = try? JSONEncoder().encode(rules) {
            try? data.write(to: folder.appendingPathComponent("rules.json"), options: .atomic)
        }
        soon?.cancel()
        Task { await checkRules() }
    }

    func checkRules() async {
        guard let library, !checkingRules else { return }
        let started = generation
        checkingRules = true
        defer { checkingRules = false }
        var perRule: [(DownloadRule, [Track]?)] = []
        for rule in rules where rule.enabled {
            perRule.append((rule, try? await rule.tracks(from: library)))
        }
        // Signed out (or into another server) meanwhile: these answers aren't this index's.
        guard generation == started else { return }
        var plan = RulePlan.compute(perRule: perRule, index: index, active: Set(active.keys))
        if limitBytes > 0 {
            let estimate = index.estimatedBytes()
            plan.fitting(budgetBytes: limitBytes - index.usedBytes - Int64(active.count) * estimate, estimateBytes: estimate)
        }
        for (trackID, ruleID) in plan.retag { index.setRule(trackID, ruleID) }
        if !plan.retag.isEmpty { save() }
        if !plan.remove.isEmpty { remove(plan.remove) }
        enqueue(plan.fetch.map { ($0.track, $0.ruleID) })
        rulesCheckedAt = Date()
    }

    // MARK: Fetching

    private func enqueue(_ items: [(Track, String?)]) {
        let new = items.filter { !index.contains($0.0.id) && active[$0.0.id] == nil }
        guard !new.isEmpty else { return }
        for (t, _) in new {
            active[t.id] = 0
            failed.remove(t.id)
        }
        queue += new.map { (track: $0.0, ruleID: $0.1) }
        if !running { Task { await run() } }
    }

    /// One at a time: a library's worth queued shouldn't swamp the server or the network.
    private func run() async {
        running = true
        defer { running = false }
        while !queue.isEmpty {
            let (track, ruleID) = queue.removeFirst()
            guard let folder, let fetch = source?(track) else { break }
            let (url, format) = fetch
            do {
                let (tmp, response) = try await URLSession.shared.download(from: url)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                    throw URLError(.badServerResponse)
                }
                let file = DownloadIndex.fileName(for: track, format: format)
                let target = folder.appendingPathComponent(file)
                try? FileManager.default.removeItem(at: target)
                try FileManager.default.moveItem(at: tmp, to: target)
                let size = try? FileManager.default.attributesOfItem(atPath: target.path)[.size] as? NSNumber
                index.add(track, file: file, bytes: size?.int64Value ?? 0, ruleID: ruleID)
                save()
                if ruleID != nil { enforceLimit() }
            } catch {
                DiagnosticLog.shared.log("downloads", "\(track.title) (\(track.id)): \(error)")
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
