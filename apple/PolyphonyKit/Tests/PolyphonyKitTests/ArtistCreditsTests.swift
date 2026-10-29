import Testing
@testable import PolyphonyKit

/// The Android app's ArtistCreditsTest cases, unchanged: both apps must agree.
struct ArtistCreditsTests {
    @Test func aTagNamesEachCreditedArtist() {
        #expect(ArtistCredits.names("Drake feat. Rihanna & Future") == ["Drake", "Rihanna", "Future"])
        #expect(ArtistCredits.names("Calvin Harris, Dua Lipa") == ["Calvin Harris", "Dua Lipa"])
        #expect(ArtistCredits.names("Skrillex x Fred again..") == ["Skrillex", "Fred again.."])
    }

    @Test func creditedNotJustNamed() {
        #expect(ArtistCredits.credits("Drake feat. Rihanna", "Rihanna"))
        #expect(ArtistCredits.credits("Calvin Harris ft. Rihanna", "rihanna"))
        #expect(!ArtistCredits.credits("Rihanna Tribute Band", "Rihanna"))
        #expect(!ArtistCredits.credits("Yeah Yeah Yeahs", "Ye"))
        #expect(!ArtistCredits.credits(nil, "Drake"))
    }

    @Test func aNameWithAJoinerOfItsOwnCountsWhole() {
        #expect(ArtistCredits.credits("Simon & Garfunkel", "Simon & Garfunkel"))
        #expect(ArtistCredits.credits("Paul Simon, Simon & Garfunkel", "Simon & Garfunkel"))
        #expect(!ArtistCredits.credits("Simon & Garfunkel Revival", "Simon & Garfunkel"))
    }

    @Test func appearsOnListsOthersAlbumsOnce() {
        let future = Artist(id: "ar-future", name: "Future")
        let tracks = [
            Track(id: "1", title: "Way 2 Sexy", albumID: "clb", artistName: "Drake feat. Future & Young Thug", artistID: "ar-drake"),
            Track(id: "2", title: "N 2 Deep", albumID: "clb", artistName: "Drake feat. Future", artistID: "ar-drake"),
            Track(id: "3", title: "Mask Off", albumID: "future-album", artistName: "Future", artistID: "ar-future"),
            Track(id: "4", title: "Futurama", albumID: "x", artistName: "The Futureheads", artistID: "ar-fh"),
        ]
        #expect(ArtistCredits.appearsOn(future, among: tracks) == ["clb"])
    }
}
