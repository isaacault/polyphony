import Foundation

/// Keeps the Mac from sleeping on its own, and the app from being napped, while a speaker plays. The
/// speaker needs nothing from the Mac to play, but the polls that follow its songs do.
@MainActor
final class SleepGuard {
    private var activity: NSObjectProtocol?

    func hold(_ active: Bool) {
        if active, activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(options: .userInitiated, reason: "Playing on a speaker")
        } else if !active, let activity {
            ProcessInfo.processInfo.endActivity(activity)
            self.activity = nil
        }
    }
}
