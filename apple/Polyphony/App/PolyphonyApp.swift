import PolyphonyKit
import SwiftUI

@main
struct PolyphonyApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        Window("Polyphony", id: "main") {
            RootView()
                .environment(model)
                .frame(minWidth: 900, minHeight: 560)
                .task { await model.start() }
        }
        .windowToolbarStyle(.unified)
        .commands {
            PlaybackCommands(player: model.player)
            SidebarCommands()
        }

        Settings {
            SettingsView()
                .environment(model)
        }
    }
}

/// The Controls menu, as Music has it: ⌥⌘P (or Space in the window) to play/pause, ⌘→/⌘← for next/previous.
struct PlaybackCommands: Commands {
    let player: PlayerController

    var body: some Commands {
        CommandMenu("Controls") {
            // Space itself is handled by the main view (RootView), not here: as a menu shortcut it
            // would swallow spaces typed into the search field.
            Button(player.isPlaying ? "Pause" : "Play") { player.togglePlay() }
                .keyboardShortcut("p", modifiers: [.command, .option])
                .disabled(player.current == nil)
            Button("Next") { player.next() }
                .keyboardShortcut(.rightArrow, modifiers: .command)
            Button("Previous") { player.previous() }
                .keyboardShortcut(.leftArrow, modifiers: .command)
            Divider()
            Button(player.queue.shuffled ? "Shuffle Off" : "Shuffle On") { player.setShuffle(!player.queue.shuffled) }
                .keyboardShortcut("s", modifiers: [.command, .shift])
            Button("Repeat: \(player.queue.repeatMode.label)") { player.cycleRepeat() }
                .keyboardShortcut("r", modifiers: [.command, .shift])
            Divider()
            Button("Volume Up") { player.volume = min(1, player.volume + 0.1) }
                .keyboardShortcut(.upArrow, modifiers: .command)
            Button("Volume Down") { player.volume = max(0, player.volume - 0.1) }
                .keyboardShortcut(.downArrow, modifiers: .command)
        }
    }
}

extension RepeatMode {
    var label: String {
        switch self {
        case .off: "Off"
        case .all: "All"
        case .one: "One"
        }
    }
}
