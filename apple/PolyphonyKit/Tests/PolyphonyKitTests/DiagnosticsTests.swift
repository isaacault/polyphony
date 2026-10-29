import Foundation
import Testing
@testable import PolyphonyKit

struct DiagnosticsTests {
    @Test func urlsLoseTheirQueriesSoNoTokenIsLogged() {
        let line = DiagnosticLog.scrub("Couldn't play https://continuo.ault.me/rest/stream?id=1&u=isaac&t=abc123&s=salt: timed out")
        #expect(line == "Couldn't play https://continuo.ault.me/rest/stream?… timed out", "the whole query goes, to the next space")
        #expect(!line.contains("abc123") && !line.contains("salt"))
        #expect(DiagnosticLog.scrub("no url here") == "no url here")
        #expect(DiagnosticLog.scrub("http://192.168.0.101/rest/getCoverArt.view?id=x and http://h/p?q") == "http://192.168.0.101/rest/getCoverArt.view?… and http://h/p?…")
    }

    @Test func theLogKeepsTheLastLines() {
        let log = DiagnosticLog(capacity: 3, now: { Date(timeIntervalSince1970: 0) })
        for i in 1...5 { log.log("test", "line \(i)") }
        #expect(log.recent == ["1970-01-01 00:00:00 UTC [test] line 3", "1970-01-01 00:00:00 UTC [test] line 4", "1970-01-01 00:00:00 UTC [test] line 5"])
    }

    @Test func theReport() {
        let r = Diagnostics.report(at: Date(timeIntervalSince1970: 0), app: "0.1 (1)", system: "macOS 15.1",
                                   servers: [.init(kind: "continuo", host: "continuo.ault.me", user: "isaac", showing: true,
                                                   receiverAddress: "http://192.168.0.101", libraryHost: "continuo.ault.me"),
                                             .init(kind: "subsonic", host: "music.ault.me", user: "isaac", showing: false,
                                                   receiverAddress: "", libraryHost: nil)],
                                   settings: [("Streaming quality", "Original")], sections: [("Download rules", [])], log: ["a"])
        #expect(r.hasPrefix("Polyphony diagnostics 1970-01-01 00:00:00 UTC\nApp: 0.1 (1)\nMac: macOS 15.1\n"))
        #expect(r.contains("- continuo continuo.ault.me user=isaac (showing) receiverAddress=http://192.168.0.101\n"))
        #expect(r.contains("- subsonic music.ault.me user=isaac receiverAddress=(not set)\n"))
        #expect(r.contains("== Download rules ==\n(none)\n") && r.hasSuffix("== Recent log (1 lines) ==\na\n"))
    }
}
