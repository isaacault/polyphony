import Testing
@testable import PolyphonyKit

struct ArtworkPaletteTests {
    /// A width × 1 image from runs of (colour, pixels).
    private func image(_ runs: [((UInt8, UInt8, UInt8, UInt8), Int)]) -> ([UInt8], Int) {
        var px: [UInt8] = []
        for ((r, g, b, a), n) in runs { for _ in 0..<n { px += [r, g, b, a] } }
        return (px, px.count / 4)
    }

    private func near(_ c: ArtworkPalette.RGB, _ r: Double, _ g: Double, _ b: Double) -> Bool {
        abs(c.r - r) < 0.05 && abs(c.g - g) < 0.05 && abs(c.b - b) < 0.05
    }

    @Test func aSingleColourIsEverything() throws {
        let (px, w) = image([((200, 30, 30, 255), 100)])
        let p = try #require(ArtworkPalette.from(rgba: px, width: w, height: 1))
        #expect(near(p.dominant, 200 / 255, 30 / 255, 30 / 255) && p.vibrant == p.dominant)
    }

    @Test func theVibrantSwatchIsTheColourfulOneEvenWhenGreyCoversMore() throws {
        let (px, w) = image([((128, 128, 128, 255), 70), ((30, 90, 220, 255), 30)])
        let p = try #require(ArtworkPalette.from(rgba: px, width: w, height: 1))
        #expect(near(p.dominant, 0.5, 0.5, 0.5), "grey covers most")
        #expect(near(p.vibrant, 30 / 255, 90 / 255, 220 / 255), "blue is the vibrant one")
        #expect(near(p.muted, 0.5, 0.5, 0.5))
    }

    @Test func darkCoversAreDarkAndTransparentPixelsDontCount() throws {
        let (px, w) = image([((12, 10, 20, 255), 60), ((255, 255, 255, 0), 200)])
        let p = try #require(ArtworkPalette.from(rgba: px, width: w, height: 1))
        #expect(p.isDark && near(p.dominant, 12 / 255, 10 / 255, 20 / 255))
        let (light, lw) = image([((240, 235, 220, 255), 10)])
        #expect(try #require(ArtworkPalette.from(rgba: light, width: lw, height: 1)).isDark == false)
    }

    @Test func nothingToGoOn() {
        #expect(ArtworkPalette.from(rgba: [], width: 0, height: 0) == nil)
        #expect(ArtworkPalette.from(rgba: [0, 0, 0, 0], width: 1, height: 1) == nil, "all transparent")
        #expect(ArtworkPalette.from(rgba: [1, 2, 3], width: 1, height: 1) == nil, "short buffer")
    }

    @Test func hsl() {
        let red = ArtworkPalette.RGB(1, 0, 0)
        #expect(red.saturation == 1 && red.lightness == 0.5)
        #expect(ArtworkPalette.RGB(0.5, 0.5, 0.5).saturation == 0)
        #expect(abs(ArtworkPalette.RGB(1, 1, 1).luminance - 1) < 1e-9)
    }
}
