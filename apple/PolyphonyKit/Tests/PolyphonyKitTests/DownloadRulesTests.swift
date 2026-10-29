import Foundation
import Testing
@testable import PolyphonyKit

struct DownloadRulesTests {
    private func t(_ id: String) -> Track { Track(id: id, title: id, duration: 100) }

    private func index(_ entries: [(String, String?)]) -> DownloadIndex {
        var i = DownloadIndex()
        for (id, rule) in entries { i.add(t(id), file: id + ".flac", bytes: 10, ruleID: rule) }
        return i
    }

    @Test func fetchesWhatsMissingInTheRulesOrder() {
        let fav = DownloadRule(id: "r1", target: .favourites)
        let plan = RulePlan.compute(perRule: [(fav, [t("b"), t("a"), t("c")])], index: index([("a", "r1")]))
        #expect(plan.fetch.map(\.track.id) == ["b", "c"] && plan.fetch.allSatisfy { $0.ruleID == "r1" })
        #expect(plan.remove.isEmpty && plan.retag.isEmpty)
    }

    @Test func aCapKeepsTheFirstOnes() {
        let rule = DownloadRule(id: "r1", target: .recentlyAdded(albums: 5), maxTracks: 2)
        let plan = RulePlan.compute(perRule: [(rule, [t("a"), t("b"), t("c")])], index: DownloadIndex())
        #expect(plan.fetch.map(\.track.id) == ["a", "b"])
    }

    @Test func whatNoRuleWantsGoesButHandDownloadsStay() {
        let rule = DownloadRule(id: "r1", target: .favourites)
        let plan = RulePlan.compute(perRule: [(rule, [t("a")])], index: index([("a", "r1"), ("b", "r1"), ("mine", nil)]))
        #expect(plan.remove == ["b"])
        let keep = RulePlan.compute(perRule: [(rule, [t("a")])], index: index([("a", "r1"), ("b", "r1")]), removeUnmatched: false)
        #expect(keep.remove.isEmpty)
    }

    @Test func aRuleThatCantSayIsHeld() {
        let playlist = DownloadRule(id: "p", target: .playlist(id: "x", name: "Road"))
        let current = index([("a", "p"), ("b", "p")])
        #expect(RulePlan.compute(perRule: [(playlist, nil)], index: current).remove.isEmpty, "lookup failed")
        #expect(RulePlan.compute(perRule: [(playlist, [])], index: current).remove.isEmpty, "empty while it keeps downloads")
    }

    @Test func aDisabledOrDeletedRulesDownloadsGo() {
        let off = DownloadRule(id: "r1", target: .favourites, enabled: false)
        #expect(RulePlan.compute(perRule: [(off, [t("a")])], index: index([("a", "r1")])).remove == ["a"])
        #expect(RulePlan.compute(perRule: [], index: index([("a", "gone")])).remove == ["a"])
    }

    @Test func aSongBothWantIsKeptByTheEarlierAndHandedOverWhenItLetsGo() {
        let first = DownloadRule(id: "r1", target: .favourites)
        let second = DownloadRule(id: "r2", target: .genre("Ambient"))
        let both = RulePlan.compute(perRule: [(first, [t("a")]), (second, [t("a")])], index: DownloadIndex())
        #expect(both.fetch == [RulePlan.Fetch(track: t("a"), ruleID: "r1")])
        let handed = RulePlan.compute(perRule: [(first, []), (second, [t("a")])], index: index([("a", "r1"), ("z", "r1")]))
        #expect(handed.retag.map(\.0) == [] , "r1 is held (empty while it keeps downloads): it keeps its songs")
        let released = RulePlan.compute(perRule: [(second, [t("a")])], index: index([("a", "r1")]))
        #expect(released.retag.map { [$0.0, $0.1] } == [["a", "r2"]] && released.remove.isEmpty)
    }

    @Test func alreadyQueuedIsntFetchedTwice() {
        let rule = DownloadRule(id: "r1", target: .favourites)
        #expect(RulePlan.compute(perRule: [(rule, [t("a"), t("b")])], index: DownloadIndex(), active: ["a"]).fetch.map(\.track.id) == ["b"])
    }

    @Test func rulesAndOlderIndexesSurviveJSON() throws {
        let rules = [DownloadRule(target: .playlist(id: "x", name: "Road")), DownloadRule(target: .mostPlayed(albums: 10), maxTracks: 50)]
        #expect(try JSONDecoder().decode([DownloadRule].self, from: JSONEncoder().encode(rules)) == rules)
        let old = #"{"entries":{"a":{"track":{"id":"a","title":"a","duration":100,"starred":false},"file":"a.flac","bytes":5,"downloadedAt":0}}}"#
        let decoded = try JSONDecoder().decode(DownloadIndex.self, from: Data(old.utf8))
        #expect(decoded.entries["a"]?.pinned == true, "downloads from before rules were by hand")
    }

    @Test func labels() {
        #expect(DownloadRule.Target.recentlyAdded(albums: 20).label == "Last 20 albums added")
        #expect(DownloadRule.Target.playlist(id: "x", name: "Road").label == "Playlist · Road")
    }
}
