import Foundation
import PolyphonyKit
import UserNotifications

/// "Ready to play: Parachutes": a notification when a request arrives in the library, while the app
/// runs (continuo's own notifications reach phones; this is the Mac's).
@MainActor
final class ArrivalNotifier {
    private var known: [Int: Bool] = [:]
    private var task: Task<Void, Never>?

    func start(_ service: ContinuoService) {
        stop()
        task = Task { [weak self] in
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
            while !Task.isCancelled {
                if let requests = try? await service.requests() { self?.check(requests) }
                try? await Task.sleep(for: .seconds(60))
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        known = [:]
    }

    private func check(_ requests: [MusicRequest]) {
        let first = known.isEmpty
        for request in requests {
            let wasDone = known[request.id]
            known[request.id] = request.isDone
            // The first look only learns what's there; after that, a request turning available is news.
            guard !first, request.isDone, wasDone == false else { continue }
            let content = UNMutableNotificationContent()
            content.title = "Ready to play"
            content.body = [request.title, request.artist].compactMap { $0 }.joined(separator: " · ")
            content.sound = .default
            if let albumID = request.albumID { content.userInfo = ["albumID": albumID] }
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "request-\(request.id)", content: content,
                                                                         trigger: nil))
        }
    }
}
