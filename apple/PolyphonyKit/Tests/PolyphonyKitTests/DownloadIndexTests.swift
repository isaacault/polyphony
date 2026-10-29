import Foundation
import Testing
@testable import PolyphonyKit

struct DownloadIndexTests {
    private func t(_ id: String, album: String, n: Int) -> Track {
        Track(id: id, title: "Song \(n)", albumID: album, albumName: "Album \(album)", artistName: "X", trackNumber: n)
    }

    @Test func entriesGroupIntoAlbumsInOrder() {
        var index = DownloadIndex()
        index.add(t("b2", album: "B", n: 2), file: "b2.flac", bytes: 20, at: Date(timeIntervalSince1970: 300))
        index.add(t("a1", album: "A", n: 1), file: "a1.flac", bytes: 10, at: Date(timeIntervalSince1970: 100))
        index.add(t("b1", album: "B", n: 1), file: "b1.flac", bytes: 30, at: Date(timeIntervalSince1970: 200))
        let albums = index.albums
        #expect(albums.map(\.id) == ["B", "A"], "newest download first")
        #expect(albums[0].tracks.map(\.id) == ["b1", "b2"] && albums[0].bytes == 50)
        #expect(index.usedBytes == 60 && index.count(of: [t("b1", album: "B", n: 1), t("zz", album: "B", n: 3)]) == 1)
    }

    @Test func removingGivesBackTheFile() {
        var index = DownloadIndex()
        index.add(t("a1", album: "A", n: 1), file: "a1.flac", bytes: 10)
        #expect(index.remove("a1") == "a1.flac" && !index.contains("a1") && index.remove("a1") == nil)
    }

    @Test func fileNamesAreSafe() {
        let track = Track(id: "al-1/../x", title: "x", container: "flac")
        #expect(DownloadIndex.fileName(for: track, format: nil) == "al-1____x.flac")
        #expect(DownloadIndex.fileName(for: track, format: "mp3") == "al-1____x.mp3")
    }

    @Test func theIndexSurvivesJSON() throws {
        var index = DownloadIndex()
        index.add(t("a1", album: "A", n: 1), file: "a1.flac", bytes: 10, at: Date(timeIntervalSince1970: 1))
        #expect(try JSONDecoder().decode(DownloadIndex.self, from: JSONEncoder().encode(index)) == index)
    }
}

struct DownloadSearchTests {
    @Test func everyWordAnyCaseAnyAccentInAlbumOrder() {
        var i = DownloadIndex()
        i.add(Track(id: "2", title: "Dayvan Cowboy", albumName: "Trans Canada Highway", artistName: "Boards of Canada", trackNumber: 1), file: "2", bytes: 1)
        i.add(Track(id: "1", title: "Roygbiv", albumName: "Music Has the Right to Children", artistName: "Boards of Canada", trackNumber: 3), file: "1", bytes: 1)
        i.add(Track(id: "3", title: "Pérdida", albumName: "Lo Que Fue", artistName: "Ana Tijoux"), file: "3", bytes: 1)
        #expect(i.search("boards canada").map(\.id) == ["1", "2"], "album order: Music… before Trans…")
        #expect(i.search("perdida").map(\.id) == ["3"] && i.search("COWBOY").map(\.id) == ["2"])
        #expect(i.search("boards autechre").isEmpty && i.search("  ").isEmpty)
    }
}
