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
private func double(_ value: Any?) -> Double? { (value as? NSNumber)?.doubleValue }

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

    @Test func resumePoints() throws {
        let cases = try #require(try contract("resume")["cases"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let tracks = (c["tracks"] as? [String] ?? []).map { Track(id: $0, title: $0, duration: 200) }
            let entry = RecentContext(context: PlayContext(kind: .album, id: "al", title: "Album"), trackID: string(c["trackId"]),
                                      trackIndex: int(c["trackIndex"]) ?? 0, positionMs: int(c["positionMs"]) ?? 0,
                                      updatedAt: Date())
            let expected = ResumePoint(index: int(c["index"]) ?? -1, positionMs: int(c["resumeMs"]) ?? -1)
            #expect(ResumePoint.of(tracks, entry) == expected, "\(c["about"] ?? "")")
        }
    }

    @Test func historyKeepsTheNewestOncePerCollection() {
        var h = ListeningHistory()
        h.limit = 2
        let a = PlayContext(kind: .album, id: "a", title: "A"), b = PlayContext(kind: .album, id: "b", title: "B")
        let c = PlayContext(kind: .playlist, id: "c", title: "C")
        h.record(a, trackID: "1", index: 0, positionMs: 0)
        h.record(b, trackID: "2", index: 0, positionMs: 0)
        h.record(a, trackID: "3", index: 2, positionMs: 5000)
        #expect(h.entries.map(\.context.id) == ["a", "b"] && h.entry(for: a)?.trackID == "3")
        h.record(c, trackID: "4", index: 0, positionMs: 0)
        #expect(h.entries.map(\.context.id) == ["c", "a"], "at most the limit")
    }
}

struct ReplayGainContractTests {
    @Test func volumesAsTheContractHasThem() throws {
        let cases = try #require(try contract("replaygain")["cases"] as? [[String: Any]])
        #expect(cases.count >= 15)
        for c in cases {
            let tags = ReplayGain.Tags(trackGainDb: double(c["trackGain"]), albumGainDb: double(c["albumGain"]),
                                       trackPeak: double(c["trackPeak"]), albumPeak: double(c["albumPeak"]))
            let track = Track(id: "t", title: "t", albumID: "a", replayGain: tags)
            let settings = ReplayGain.Settings(mode: try #require(ReplayGain.Mode(rawValue: string(c["mode"]) ?? "")),
                                               preampDb: double(c["preampDb"]) ?? 0,
                                               preventClipping: (c["preventClipping"] as? NSNumber)?.boolValue ?? true)
            let volume = ReplayGain.volume(for: track, settings, sameAlbumAsNeighbour: (c["sameAlbum"] as? NSNumber)?.boolValue ?? false)
            let expected = try #require(double(c["volume"]))
            #expect(abs(Double(volume) - expected) <= 0.002, "\(string(c["name"]) ?? "?"): \(volume)")
        }
    }
}
