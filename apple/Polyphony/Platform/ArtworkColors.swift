import AppKit
import ImageIO
import PolyphonyKit
import SwiftUI

/// A cover's colours (PolyphonyKit's ArtworkPalette) from a small copy of it, cached by URL, for
/// Now Playing's tint (as the Android app's artwork colours in the player).
@MainActor
enum ArtworkColors {
    private static var cache: [URL: ArtworkPalette] = [:]
    nonisolated private static let side = 48

    static func palette(for url: URL) async -> ArtworkPalette? {
        if let hit = cache[url] { return hit }
        guard let data = try? await URLSession.shared.data(from: url).0 else { return nil }
        let palette = await Task.detached { pixels(data).flatMap { ArtworkPalette.from(rgba: $0, width: side, height: side) } }.value
        if let palette { cache[url] = palette }
        return palette
    }

    /// The image drawn at side × side, as RGBA bytes.
    nonisolated private static func pixels(_ data: Data) -> [UInt8]? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        var buffer = [UInt8](repeating: 0, count: side * side * 4)
        let drawn = buffer.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(data: raw.baseAddress, width: side, height: side, bitsPerComponent: 8,
                                          bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        return drawn ? buffer : nil
    }
}

extension Color {
    init(_ rgb: ArtworkPalette.RGB) {
        self.init(red: rgb.r, green: rgb.g, blue: rgb.b)
    }
}
