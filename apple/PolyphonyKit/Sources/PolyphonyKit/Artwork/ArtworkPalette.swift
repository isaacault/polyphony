import Foundation

/// Colours picked from a cover, to tint Now Playing (as the Android app's artwork colours, which use
/// AndroidX Palette): the dominant colour, and the vibrant and muted swatches that score best on
/// saturation, lightness and how much of the cover they cover, with Palette's targets and weights.
public struct ArtworkPalette: Sendable, Equatable {
    public struct RGB: Sendable, Hashable {
        public var r: Double
        public var g: Double
        public var b: Double

        public init(_ r: Double, _ g: Double, _ b: Double) {
            self.r = r
            self.g = g
            self.b = b
        }

        public var lightness: Double { (max(r, g, b) + min(r, g, b)) / 2 }

        public var saturation: Double {
            let hi = max(r, g, b), lo = min(r, g, b)
            guard hi != lo else { return 0 }
            let l = lightness
            return (hi - lo) / (1 - abs(2 * l - 1))
        }

        /// Relative luminance (sRGB), for choosing light or dark text on it.
        public var luminance: Double {
            func linear(_ c: Double) -> Double { c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
            return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
        }
    }

    public var dominant: RGB
    public var vibrant: RGB
    public var darkVibrant: RGB
    public var muted: RGB
    public var darkMuted: RGB

    public var isDark: Bool { dominant.luminance < 0.18 }

    /// [rgba] is width × height pixels, 4 bytes each (a small copy of the cover: 32 to 128 px is plenty).
    public static func from(rgba: [UInt8], width: Int, height: Int) -> ArtworkPalette? {
        guard width > 0, height > 0, rgba.count >= width * height * 4 else { return nil }
        // 4 bits a channel: 4096 boxes, each keeping the sum of its pixels.
        var sums = [Int: (r: Int, g: Int, b: Int, n: Int)]()
        for i in stride(from: 0, to: width * height * 4, by: 4) where rgba[i + 3] >= 128 {
            let r = Int(rgba[i]), g = Int(rgba[i + 1]), b = Int(rgba[i + 2])
            let key = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4)
            let s = sums[key] ?? (0, 0, 0, 0)
            sums[key] = (s.r + r, s.g + g, s.b + b, s.n + 1)
        }
        let boxes = sums.values.map { s in
            (colour: RGB(Double(s.r) / Double(s.n) / 255, Double(s.g) / Double(s.n) / 255, Double(s.b) / Double(s.n) / 255), count: s.n)
        }
        guard let top = boxes.max(by: { $0.count < $1.count }) else { return nil }
        let most = Double(top.count)

        func best(saturation: ClosedRange<Double>, targetSaturation: Double,
                  lightness: ClosedRange<Double>, targetLightness: Double) -> RGB? {
            func score(_ box: (colour: RGB, count: Int)) -> Double {
                0.24 * (1 - abs(box.colour.saturation - targetSaturation))
                    + 0.52 * (1 - abs(box.colour.lightness - targetLightness))
                    + 0.24 * Double(box.count) / most
            }
            return boxes.filter { saturation.contains($0.colour.saturation) && lightness.contains($0.colour.lightness) }
                .max { score($0) < score($1) }?.colour
        }

        let vibrant = best(saturation: 0.35...1, targetSaturation: 1, lightness: 0.3...0.7, targetLightness: 0.5)
        let darkVibrant = best(saturation: 0.35...1, targetSaturation: 1, lightness: 0...0.45, targetLightness: 0.26)
        let muted = best(saturation: 0...0.4, targetSaturation: 0.3, lightness: 0.3...0.7, targetLightness: 0.5)
        let darkMuted = best(saturation: 0...0.4, targetSaturation: 0.3, lightness: 0...0.45, targetLightness: 0.26)
        let dominant = top.colour
        return ArtworkPalette(dominant: dominant, vibrant: vibrant ?? dominant, darkVibrant: darkVibrant ?? vibrant ?? dominant,
                              muted: muted ?? dominant, darkMuted: darkMuted ?? dominant)
    }
}
