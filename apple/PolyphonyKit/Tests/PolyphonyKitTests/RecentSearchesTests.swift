import Testing
@testable import PolyphonyKit

struct RecentSearchesTests {
    @Test func newestFirstOnceEachAndAtMostTen() {
        var r = RecentSearches()
        r.add("Boards of Canada")
        r.add("  geogaddi ")
        r.add("boards of canada")
        #expect(r.items == ["boards of canada", "geogaddi"], "again moves it up, as typed last")
        for i in 0..<12 { r.add("query \(i)") }
        #expect(r.items.count == 10 && r.items.first == "query 11")
    }

    @Test func tooShortIsntKeptAndItemsGo() {
        var r = RecentSearches()
        r.add("a")
        r.add("   ")
        #expect(r.items.isEmpty)
        r.add("Autechre"); r.add("Aphex")
        r.remove("Autechre")
        #expect(r.items == ["Aphex"])
        r.clear()
        #expect(r.items.isEmpty && RecentSearches(Array(repeating: "x", count: 20)).items.count == 10)
    }
}
