import AppKit
import PolyphonyKit
import SwiftUI
import UniformTypeIdentifiers

@main
struct PolyphonyApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel()

    var body: some Scene {
        Window("Polyphony", id: "main") {
            RootView()
                .environment(model)
                .frame(minWidth: 900, minHeight: 560)
                .task {
                    AppDelegate.player = model.player
                    await model.start()
                }
        }
        .windowToolbarStyle(.unified)
        .commands {
            PlaybackCommands(player: model.player)
            GoCommands(model: model)
            SidebarCommands()
            CommandGroup(after: .help) {
                Button("Copy Diagnostics") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(model.diagnostics(), forType: .string)
                }
                Button("Save Diagnostics…") { saveDiagnostics(model.diagnostics()) }
            }
        }

        Settings {
            SettingsView()
                .environment(model)
        }

        // A mini player in the menu bar, for when the window is out of the way.
        MenuBarExtra("Polyphony", systemImage: model.player.isPlaying ? "waveform" : "music.note") {
            MiniPlayer()
                .environment(model)
                .onAppear { AppDelegate.player = model.player }
        }
        .menuBarExtraStyle(.window)
    }
}

/// The Go menu: ⌘F for Search, ⌘L for the album that's playing, ⌘1… for the library.
struct GoCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandMenu("Go") {
            Button("Search") { model.go(to: .search) }
                .keyboardShortcut("f", modifiers: .command)
            Button("Show Playing Album") { model.showCurrentAlbum() }
                .keyboardShortcut("l", modifiers: .command)
                .disabled(model.player.current?.libraryTrack?.albumID == nil)
            Divider()
            Button("Home") { model.go(to: .home) }.keyboardShortcut("1", modifiers: .command)
            Button("Artists") { model.go(to: .artists) }.keyboardShortcut("2", modifiers: .command)
            Button("Albums") { model.go(to: .albums) }.keyboardShortcut("3", modifiers: .command)
            Button("Songs") { model.go(to: .songs) }.keyboardShortcut("4", modifiers: .command)
            Button("Playlists") { model.go(to: .playlists) }.keyboardShortcut("5", modifiers: .command)
            if model.isContinuo {
                Button("Requests") { model.go(to: .requests) }.keyboardShortcut("6", modifiers: .command)
            }
        }
    }
}

/// The Dock icon's menu: Play/Pause, Next, Previous.
final class AppDelegate: NSObject, NSApplicationDelegate {
    @MainActor static weak var player: PlayerController?

    func applicationDockMenu(_ sender: NSApplication) -> NSMenu? {
        MainActor.assumeIsolated { () -> NSMenu? in
            guard let player = Self.player, player.current != nil else { return nil }
            let menu = NSMenu()
            menu.addItem(item(player.isPlaying ? "Pause" : "Play", #selector(togglePlay)))
            menu.addItem(item("Next", #selector(next)))
            menu.addItem(item("Previous", #selector(previous)))
            return menu
        }
    }

    // Keep playing with the window closed: music apps don't quit when their window does.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    private func item(_ title: String, _ action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        return item
    }

    @objc private func togglePlay() { MainActor.assumeIsolated { Self.player?.togglePlay() } }
    @objc private func next() { MainActor.assumeIsolated { Self.player?.next() } }
    @objc private func previous() { MainActor.assumeIsolated { Self.player?.previous() } }
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
            Divider()
            Menu("Sleep Timer") {
                ForEach([15, 30, 45, 60, 90], id: \.self) { minutes in
                    Button("\(minutes) Minutes") { player.setSleepTimer(.minutes(minutes)) }
                }
                Button("End of Song") { player.setSleepTimer(.endOfSong) }
                if player.sleepAt != nil || player.sleepAtEndOfSong {
                    Divider()
                    Button("Turn Off") { player.setSleepTimer(nil) }
                }
            }
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

/// Help > Save Diagnostics: a text file to send when something's wrong (no passwords or tokens in it).
@MainActor
private func saveDiagnostics(_ text: String) {
    let panel = NSSavePanel()
    let format = DateFormatter()
    format.dateFormat = "yyyyMMdd-HHmmss"
    let stamp = format.string(from: Date())
    panel.nameFieldStringValue = "polyphony-diagnostics-\(stamp).txt"
    panel.allowedContentTypes = [.plainText]
    guard panel.runModal() == .OK, let url = panel.url else { return }
    try? text.write(to: url, atomically: true, encoding: .utf8)
}
