import AppKit
import SwiftUI

/// Settings > General > Appearance, read where it matters through @AppStorage. The Android app's
/// appearance settings, as far as a Mac has them.
enum ThemeMode: String, CaseIterable {
    case system, light, dark

    var label: String { rawValue.capitalized }

    var scheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

enum ArtworkShape: String, CaseIterable {
    case rounded, square, circle

    var label: String { rawValue.capitalized }

    /// Large enough to make a circle of any cover shown; a corner radius past half the side stops there.
    var cornerRadius: CGFloat {
        switch self {
        case .rounded: 12
        case .square: 0
        case .circle: 1000
        }
    }
}

enum RatingStyle: String, CaseIterable {
    case compact, stars

    var label: String { self == .compact ? "★4" : "★★★★☆" }

    func text(_ rating: Int) -> String {
        switch self {
        case .compact: "★\(rating)"
        case .stars: String(repeating: "★", count: rating) + String(repeating: "☆", count: max(5 - rating, 0))
        }
    }
}

extension Color {
    init(rgb: Int) {
        self.init(red: Double(rgb >> 16 & 0xFF) / 255, green: Double(rgb >> 8 & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }

    var rgb: Int {
        guard let c = NSColor(self).usingColorSpace(.sRGB) else { return 0x6750A4 }
        return Int((c.redComponent * 255).rounded()) << 16 | Int((c.greenComponent * 255).rounded()) << 8 | Int((c.blueComponent * 255).rounded())
    }
}

/// Covers come through URLSession's shared cache (AsyncImage): how large it may grow, and clearing it.
enum ArtworkCache {
    static var limitMB: Int {
        let saved = UserDefaults.standard.integer(forKey: "library.artworkCacheMB")
        return saved > 0 ? saved : 512
    }

    static func apply() {
        URLCache.shared.diskCapacity = limitMB * 1_000_000
        URLCache.shared.memoryCapacity = 64_000_000
    }

    static func set(limitMB: Int) {
        UserDefaults.standard.set(limitMB, forKey: "library.artworkCacheMB")
        apply()
    }

    static func clear() {
        URLCache.shared.removeAllCachedResponses()
    }
}

/// The theme and accent, on each window.
struct AppAppearance: ViewModifier {
    @AppStorage("appearance.theme") private var theme: ThemeMode = .system
    @AppStorage("appearance.customAccent") private var customAccent = false
    @AppStorage("appearance.accent") private var accent = 0x6750A4

    private var accentColor: Color? { customAccent ? Color(rgb: accent) : nil }

    func body(content: Content) -> some View {
        content
            .preferredColorScheme(theme.scheme)
            .tint(accentColor)
    }
}

/// A true black behind the library in dark mode, when asked for.
struct PureBlackBackground: ViewModifier {
    @AppStorage("appearance.pureBlack") private var pureBlack = false
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        if pureBlack && scheme == .dark {
            content.scrollContentBackground(.hidden).background(Color.black)
        } else {
            content
        }
    }
}
