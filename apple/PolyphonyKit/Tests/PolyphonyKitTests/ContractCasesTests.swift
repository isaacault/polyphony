import Foundation
import Testing
@testable import PolyphonyKit

/// contract/cases: the rules the Kotlin side (shared/domain, android/app) tests with the same files,
/// so the apps can't drift apart.
private func contract(_ name: String) throws -> [String: Any] {
    // Tests/PolyphonyKitTests/ → the repo root, four levels up.
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let data = try Data(contentsOf: root.appendingPathComponent("contract/cases/\(name).json"))
    return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
}

private func string(_ value: Any?) -> String? { value as? String }
private func int(_ value: Any?) -> Int? { (value as? NSNumber)?.intValue }

struct ContractCasesTests {
    @Test func artistNames() throws {
        let cases = try #require(try contract("artist-credits")["names"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            #expect(ArtistCredits.names(string(c["tag"])) == (c["names"] as? [String]), "\(c)")
        }
    }

    @Test func artistCredits() throws {
        let cases = try #require(try contract("artist-credits")["credits"] as? [[String: Any]])
        for c in cases {
            #expect(ArtistCredits.credits(string(c["tag"]), string(c["artist"]) ?? "") == (c["credits"] as? Bool), "\(c)")
        }
    }

    private func track(_ c: [String: Any], _ i: Int) -> Track {
        Track(id: "t\(i)", title: "t\(i)", bitDepth: int(c["bitDepth"]), sampleRateHz: int(c["sampleRate"]),
              container: string(c["container"]))
    }

    @Test func qualityBadges() throws {
        let cases = try #require(try contract("quality")["badges"] as? [[String: Any]])
        for (i, c) in cases.enumerated() {
            #expect(AudioQuality.badge(for: track(c, i)) == string(c["badge"]), "\(c)")
        }
    }

    @Test func albumBadgesAndTrackChips() throws {
        let albums = try #require(try contract("quality")["albums"] as? [[String: Any]])
        for album in albums {
            let specs = try #require(album["tracks"] as? [[String: Any]])
            let tracks = specs.enumerated().map { track($1, $0) }
            let badge = AudioQuality.albumBadge(tracks)
            #expect(badge == string(album["badge"]), "\(album["about"] ?? "")")
            for (i, spec) in specs.enumerated() {
                #expect(AudioQuality.trackChip(tracks[i], albumBadge: badge) == string(spec["chip"]), "track \(i): \(album["about"] ?? "")")
            }
        }
    }
}
