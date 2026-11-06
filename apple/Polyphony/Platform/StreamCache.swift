import Foundation
import PolyphonyKit
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Songs fetched whole ahead of the player, in the Caches folder, least recently used going first.
/// Nothing here is precious: macOS may clear it, and it's fetched again.
@MainActor
final class StreamCache {
    private let folder: URL
    private let session: URLSession

    /// The most it keeps, in MB (Settings > Playback).
    var limitMB: Int {
        didSet {
            UserDefaults.standard.set(limitMB, forKey: "playback.streamCacheMB")
            trim()
        }
    }

    private var limitBytes: Int64 { Int64(limitMB) * 1_000_000 }

    init() {
        folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Polyphony/Streams", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let saved = UserDefaults.standard.integer(forKey: "playback.streamCacheMB")
        limitMB = saved > 0 ? saved : 1024
        session = URLSession(configuration: .ephemeral)
    }

    /// The whole file for this stream, when it's here; using it keeps it longer.
    func file(_ name: String) -> URL? {
        guard let url = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
            .first(where: { $0.deletingPathExtension().lastPathComponent == Self.safe(name) }) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
        return url
    }

    /// What one plan may fetch: a quarter of the limit, so fetching ahead never pushes out what was
    /// just played.
    var fetchBudgetBytes: Int64 { limitBytes / 4 }

    /// Fetches the stream into the cache unless it's here: how many bytes that took. Nil when the
    /// fetch failed or was cancelled: the rest of the plan waits for the next update.
    func fetch(_ url: URL, name: String, container: String?) async -> Int64? {
        if file(name) != nil { return 0 }
        guard let temp = await download(url) else { return nil }
        let target = folder.appendingPathComponent(Self.safe(name)).appendingPathExtension(container ?? "mp3")
        try? FileManager.default.removeItem(at: target)
        guard (try? FileManager.default.moveItem(at: temp, to: target)) != nil else { return nil }
        let size = (try? target.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        trim()
        return Int64(size)
    }

    private func download(_ url: URL) async -> URL? {
        let session = session
        let holder = TaskHolder()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<URL?, Never>) in
                let task = session.downloadTask(with: url) { temp, response, _ in
                    // The temporary file goes away when this returns: move it somewhere of our own.
                    guard let temp, ((response as? HTTPURLResponse)?.statusCode ?? 0) == 200 else {
                        continuation.resume(returning: nil)
                        return
                    }
                    let kept = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                    let moved = (try? FileManager.default.moveItem(at: temp, to: kept)) != nil
                    continuation.resume(returning: moved ? kept : nil)
                }
                holder.task = task
                task.resume()
            }
        } onCancel: {
            holder.task?.cancel()
        }
    }

    private final class TaskHolder: @unchecked Sendable {
        var task: URLSessionDownloadTask?
    }

    private func trim() {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .contentModificationDateKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: Array(keys)) else { return }
        let rows = files.map { url -> (url: URL, size: Int64, used: Date) in
            let values = try? url.resourceValues(forKeys: keys)
            return (url, Int64(values?.fileSize ?? 0), values?.contentModificationDate ?? .distantPast)
        }.sorted { $0.used < $1.used }
        var total = rows.reduce(Int64(0)) { $0 + $1.size }
        for row in rows where total > limitBytes {
            try? FileManager.default.removeItem(at: row.url)
            total -= row.size
        }
    }

    private static func safe(_ name: String) -> String {
        String(name.map { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" ? $0 : "_" })
    }
}

/// For quick questions on the LAN: a device that isn't there shouldn't hold anything up.
struct ShortTimeoutTransport: HTTPTransport {
    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        var r = URLRequest(url: request.url, timeoutInterval: 2)
        r.httpMethod = request.method
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 2
        configuration.timeoutIntervalForResource = 3
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }
        return try await withCheckedThrowingContinuation { continuation in
            session.dataTask(with: r) { data, response, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: HTTPResponse(status: (response as? HTTPURLResponse)?.statusCode ?? 0, body: data ?? Data()))
                }
            }.resume()
        }
    }
}
