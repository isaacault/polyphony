import Foundation
import Testing
@testable import PolyphonyKit

struct UPnPTests {
    @Test func timesBothWays() {
        #expect(UPnPTime.format(ms: 185_250) == "0:03:05" && UPnPTime.format(ms: 185_250, withMillis: true) == "0:03:05.250")
        #expect(UPnPTime.format(ms: 3_725_000) == "1:02:05")
        #expect(UPnPTime.parse("0:03:05.25") == 185_250 && UPnPTime.parse("01:00:00") == 3_600_000)
        #expect(UPnPTime.parse("NOT_IMPLEMENTED") == nil && UPnPTime.parse(nil) == nil)
    }

    @Test func soapEnvelopeEscapesItsArguments() {
        let env = SOAP.envelope(service: Renderer.avTransportType, action: "SetAVTransportURI",
                                args: [("InstanceID", "0"), ("CurrentURI", "http://x/a?b=1&c=2")])
        #expect(env.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        #expect(env.contains("<CurrentURI>http://x/a?b=1&amp;c=2</CurrentURI>"))
    }

    @Test func responsesAndFaults() throws {
        let ok = #"<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><Track>1</Track><TrackDuration>0:03:20</TrackDuration><RelTime>0:00:42</RelTime></u:GetPositionInfoResponse></s:Body></s:Envelope>"#
        let out = try SOAP.parseResponse(Data(ok.utf8), action: "GetPositionInfo")
        #expect(out["RelTime"] == "0:00:42" && out["TrackDuration"] == "0:03:20")
        let fault = #"<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>716</errorCode><errorDescription>Resource not found</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"#
        #expect(throws: DLNAError(code: 716, message: "Resource not found")) { try SOAP.parseResponse(Data(fault.utf8), action: "Play") }
    }

    @Test func didlCarriesTheTrackAndAnEscapedURL() {
        let didl = DIDL.metadata(url: "http://192.168.0.101/rest/stream?id=1&t=x", mime: "audio/flac", title: "Speaker & Co",
                                 artist: "Alaskan Tapes", album: nil, artworkURL: nil, durationMs: 180_038)
        #expect(didl.contains("<dc:title>Speaker &amp; Co</dc:title>") && didl.contains("<upnp:artist>Alaskan Tapes</upnp:artist>"))
        #expect(didl.contains("duration=\"0:03:00.038\"") && didl.contains(">http://192.168.0.101/rest/stream?id=1&amp;t=x</res>"))
        #expect(DIDL.protocolInfo(mime: "audio/mpeg").contains("DLNA.ORG_PN=MP3;"))
    }

    @Test func ssdpResponses() throws {
        let reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.0.215:49154/MediaRenderer/desc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:9ab0c000::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        let r = try #require(SSDP.parse(reply))
        #expect(r.location.absoluteString == "http://192.168.0.215:49154/MediaRenderer/desc.xml" && r.target == SSDP.mediaRenderer)
        #expect(SSDP.search().hasPrefix("M-SEARCH * HTTP/1.1\r\n") && SSDP.search().contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
    }

    @Test func aDeviceDescriptionGivesTheRenderer() throws {
        let xml = #"<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Lounge</friendlyName><manufacturer>Yamaha Corporation</manufacturer><modelName>RX-V483</modelName><UDN>uuid:9ab0c000-f668-11de-9976-00a0deffd733</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/AVTransport/ctrl</controlURL></service><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/RenderingControl/ctrl</controlURL></service></serviceList></device></root>"#
        let r = try #require(Renderer.parse(description: Data(xml.utf8), location: URL(string: "http://192.168.0.215:49154/MediaRenderer/desc.xml")!))
        #expect(r.name == "Lounge" && r.model == "RX-V483" && r.udn.hasPrefix("uuid:9ab0c000"))
        #expect(r.avTransport.absoluteString == "http://192.168.0.215:49154/AVTransport/ctrl")
        #expect(r.renderingControl?.absoluteString == "http://192.168.0.215:49154/RenderingControl/ctrl")
    }

    @Test func somethingWithoutAVTransportIsntARenderer() {
        let xml = #"<root><device><friendlyName>NAS</friendlyName><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/cd</controlURL></service></serviceList></device></root>"#
        #expect(Renderer.parse(description: Data(xml.utf8), location: URL(string: "http://h/d.xml")!) == nil)
    }
}

/// A speaker that answers SOAP from a script: its transport state and position.
private final class FakeSpeaker: @unchecked Sendable {
    var state = "STOPPED"
    var position = "0:00:00"
    var actions: [String] = []
    var lastURI: String?
    var volume = 31
    var volumesSet: [String] = []

    func respond(_ request: HTTPRequest) -> HTTPResponse {
        let action = (request.headers["SOAPACTION"] ?? "").split(separator: "#").last.map { String($0.dropLast()) } ?? ""
        actions.append(action)
        let body = String(data: request.body ?? Data(), encoding: .utf8) ?? ""
        if action == "SetAVTransportURI", let r = body.range(of: "<CurrentURI>"), let e = body.range(of: "</CurrentURI>") {
            lastURI = String(body[r.upperBound..<e.lowerBound])
        }
        let out: String
        switch action {
        case "GetTransportInfo": out = "<CurrentTransportState>\(state)</CurrentTransportState>"
        case "GetPositionInfo": out = "<RelTime>\(position)</RelTime><TrackDuration>0:03:20</TrackDuration>"
        case "GetVolume": out = "<CurrentVolume>\(volume)</CurrentVolume>"
        case "SetVolume":
            if let r = body.range(of: "<DesiredVolume>"), let e = body.range(of: "</DesiredVolume>") {
                volumesSet.append(String(body[r.upperBound..<e.lowerBound]))
            }
            out = ""
        default: out = ""
        }
        return HTTPResponse(status: 200, body: Data("<s:Envelope xmlns:s=\"x\"><s:Body><u:\(action)Response>\(out)</u:\(action)Response></s:Body></s:Envelope>".utf8))
    }
}

private final class TestClock: @unchecked Sendable { var now = 1_000_000 }

@MainActor
struct DLNAEngineTests {
    private let renderer = Renderer(udn: "uuid:1", name: "Lounge", manufacturer: nil, model: nil,
                                    avTransport: URL(string: "http://192.168.0.215:49154/AVTransport/ctrl")!,
                                    renderingControl: URL(string: "http://192.168.0.215:49154/RenderingControl/ctrl")!)

    private func engine(_ speaker: FakeSpeaker, clock: @escaping @MainActor () -> Int = { Int(Date().timeIntervalSince1970 * 1000) }) -> (DLNAEngine, [AudioEvent]) {
        let e = DLNAEngine(control: DLNAControl(renderer: renderer, transport: FakeTransport { speaker.respond($0) }), autoPoll: false, clock: clock)
        return (e, [])
    }

    @Test func loadingSetsTheURIWithMetadataAndPlays() async {
        let speaker = FakeSpeaker()
        let (e, _) = engine(speaker)
        e.load(AudioSource(url: URL(string: "http://192.168.0.101/rest/stream?id=1")!,
                           info: .init(title: "Speaker", artist: "Alaskan Tapes", durationMs: 180_000)), startAt: 0, autoplay: true)
        for _ in 0..<50 { await Task.yield() }
        #expect(speaker.actions == ["SetAVTransportURI", "Play"] && speaker.lastURI == "http://192.168.0.101/rest/stream?id=1")
    }

    @Test func pollingReportsPlayingPositionAndTheEnd() async {
        let speaker = FakeSpeaker()
        let clock = TestClock()
        let (e, _) = engine(speaker) { clock.now }
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        e.load(AudioSource(url: URL(string: "http://h/a")!), startAt: 0, autoplay: true)
        for _ in 0..<50 { await Task.yield() }
        clock.now += 5_000
        speaker.state = "PLAYING"; speaker.position = "0:00:42"
        await e.pollOnce()
        #expect(events.contains(.started) && events.contains(.position(42, duration: 200)))
        clock.now += 1_000
        speaker.position = "0:03:19"
        await e.pollOnce()
        clock.now += 1_000
        speaker.state = "STOPPED"
        await e.pollOnce()
        #expect(events.last == .ended)
        await e.pollOnce()
        #expect(events.filter { $0 == .ended }.count == 1, "once")
    }

    @Test func stoppedMidSongFromTheSpeakerIsAPauseNotAnEnd() async {
        let speaker = FakeSpeaker()
        let clock = TestClock()
        let (e, _) = engine(speaker) { clock.now }
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        e.load(AudioSource(url: URL(string: "http://h/a")!), startAt: 0, autoplay: true)
        for _ in 0..<50 { await Task.yield() }
        clock.now += 5_000
        speaker.state = "PLAYING"; speaker.position = "0:00:42"
        await e.pollOnce()
        clock.now += 1_000
        speaker.state = "STOPPED"
        await e.pollOnce()
        #expect(!events.contains(.ended) && events.last(where: { $0 == .paused || $0 == .started }) == .paused)
    }

    @Test func ourOwnStopIsntAnEnd() async {
        let speaker = FakeSpeaker()
        let (e, _) = engine(speaker)
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        e.load(AudioSource(url: URL(string: "http://h/a")!), startAt: 0, autoplay: true)
        speaker.state = "PLAYING"
        await e.pollOnce()
        e.stop()
        speaker.state = "STOPPED"
        await e.pollOnce()
        #expect(!events.contains(.ended))
    }

    @Test func aPausedSpeakerSaysPaused() async {
        let speaker = FakeSpeaker()
        let clock = TestClock()
        let (e, _) = engine(speaker) { clock.now }
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        e.load(AudioSource(url: URL(string: "http://h/a")!), startAt: 0, autoplay: true)
        for _ in 0..<50 { await Task.yield() }
        clock.now += 5_000
        speaker.state = "PLAYING"; await e.pollOnce()
        clock.now += 5_000
        speaker.state = "PAUSED_PLAYBACK"; await e.pollOnce()
        #expect(events.last(where: { $0 == .paused || $0 == .started }) == .paused)
    }

    @Test func theSpeakersOwnVolumeIsReadAndChangesGoInOrderLatestWinning() async {
        let speaker = FakeSpeaker()
        let (e, _) = engine(speaker)
        await e.adoptSpeakerVolume()
        #expect(abs(e.volume - 0.31) < 0.001 && speaker.volumesSet.isEmpty, "read, not sent back")
        for v in stride(from: 0.32, through: 0.50, by: 0.02) { e.volume = Float(v) }
        for _ in 0..<200 { await Task.yield() }
        #expect(speaker.volumesSet.last == "50", "the latest wins")
        #expect(speaker.volumesSet.count < 10, "not one call per step: \(speaker.volumesSet)")
        #expect(speaker.volumesSet == speaker.volumesSet.sorted { Int($0)! < Int($1)! }, "in order")
    }
}

/// A speaker simulated against a virtual clock: its position runs while PLAYING, songs end at their
/// length, and a SetNext song starts by itself. The Android engine's FakeTransport.
private final class SimSpeaker: @unchecked Sendable {
    var now = 1_000_000
    var durations: [String: Int] = [:]
    var supportsSetNext = true
    var supportsPause = true
    /// The Yamaha RX-V483: after a hand-off every report stays at the old song's end, still PLAYING.
    var freezeAtHandoff = false
    /// Whether TrackMetaData carries the playing song's item id (when not frozen on it).
    var metadataFollows = false
    var answersMediaInfo = false
    var doubleEscapeMetadata = false

    private(set) var calls: [String] = []
    private(set) var state = "NO_MEDIA_PRESENT"
    private var currentURI: String?
    private var currentID: String?
    private var nextURI: String?
    private var nextID: String?
    private var base = 0
    private var since = 0
    private var frozen: (position: Int, duration: Int, uri: String?, id: String?)?

    private func settle() {
        while state == "PLAYING", let uri = currentURI, let d = durations[uri], base + (now - since) >= d {
            let endedAt = since + (d - base)
            if frozen == nil, freezeAtHandoff, nextURI != nil { frozen = (d, d, uri, currentID) }
            if let next = nextURI {
                currentURI = next
                currentID = nextID
                nextURI = nil
                nextID = nil
                base = 0
                since = endedAt
            } else {
                state = "STOPPED"
                base = d
            }
        }
    }

    private func field(_ body: String, _ name: String) -> String? {
        guard let r = body.range(of: "<\(name)>"), let e = body.range(of: "</\(name)>") else { return nil }
        return String(body[r.upperBound..<e.lowerBound])
    }

    private func short(_ uri: String) -> String { uri.split(separator: "/").last.map(String.init) ?? uri }

    func respond(_ request: HTTPRequest) -> HTTPResponse {
        settle()
        let action = (request.headers["SOAPACTION"] ?? "").split(separator: "#").last.map { String($0.dropLast()) } ?? ""
        let body = String(data: request.body ?? Data(), encoding: .utf8) ?? ""
        func id(_ metadata: String?) -> String? {
            metadata.flatMap { DIDL.itemID($0.replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")) }
        }
        var out = ""
        switch action {
        case "SetAVTransportURI":
            currentURI = field(body, "CurrentURI")
            currentID = id(field(body, "CurrentURIMetaData"))
            nextURI = nil
            frozen = nil
            base = 0
            state = "STOPPED"
            calls.append("setUri:\(short(currentURI ?? ""))")
        case "SetNextAVTransportURI":
            guard supportsSetNext else { return Self.fault() }
            nextURI = field(body, "NextURI")
            nextID = id(field(body, "NextURIMetaData"))
            calls.append("setNext:\(short(nextURI ?? ""))")
        case "Play":
            if state != "PLAYING" { since = now }
            state = "PLAYING"
            calls.append("play")
        case "Pause":
            guard supportsPause else { return Self.fault() }
            base += now - since
            state = "PAUSED_PLAYBACK"
            calls.append("pause")
        case "Stop":
            state = "STOPPED"
            calls.append("stop")
        case "Seek":
            base = UPnPTime.parse(field(body, "Target")) ?? 0
            since = now
            calls.append("seek")
        case "GetTransportInfo":
            out = "<CurrentTransportState>\(state)</CurrentTransportState>"
        case "GetPositionInfo":
            if let f = frozen {
                out = "<RelTime>\(UPnPTime.format(ms: f.position))</RelTime><TrackDuration>\(UPnPTime.format(ms: f.duration))</TrackDuration><TrackURI>\(f.uri ?? "")</TrackURI>"
                if metadataFollows, let currentID { out += metadata(currentID) }
            } else {
                let p = state == "PLAYING" ? base + (now - since) : base
                out = "<RelTime>\(UPnPTime.format(ms: p))</RelTime><TrackDuration>\(UPnPTime.format(ms: currentURI.flatMap { durations[$0] } ?? 0))</TrackDuration><TrackURI>\(currentURI ?? "")</TrackURI>"
                if metadataFollows, let currentID { out += metadata(currentID) }
            }
        case "GetMediaInfo":
            guard answersMediaInfo else { return Self.fault() }
            out = "<CurrentURI>\(currentURI ?? "")</CurrentURI><NextURI>\(nextURI ?? "")</NextURI>"
        default:
            break
        }
        return HTTPResponse(status: 200, body: Data("<s:Envelope xmlns:s=\"x\"><s:Body><u:\(action)Response>\(out)</u:\(action)Response></s:Body></s:Envelope>".utf8))
    }

    private func metadata(_ id: String) -> String {
        let didl = "<DIDL-Lite><item id=\"\(id)\"></item></DIDL-Lite>"
        if doubleEscapeMetadata {
            return "<TrackMetaData>&lt;DIDL-Lite&gt;&lt;item id=&amp;quot;\(id)&amp;quot;&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</TrackMetaData>"
        }
        return "<TrackMetaData>\(XMLEscape.escape(didl))</TrackMetaData>"
    }

    private static func fault() -> HTTPResponse {
        HTTPResponse(status: 500, body: Data("<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault><errorCode>602</errorCode><errorDescription>Not implemented</errorDescription></s:Fault></s:Body></s:Envelope>".utf8))
    }
}

/// The engine on a [SimSpeaker] under a real PlayerController, as in the app.
@MainActor
private final class Rig {
    let speaker = SimSpeaker()
    let engine: DLNAEngine
    let player: PlayerController
    let songs: [Track]

    init(_ lengths: [(String, Int)], configure: (SimSpeaker) -> Void = { _ in }) {
        let speaker = self.speaker
        configure(speaker)
        let renderer = Renderer(udn: "uuid:1", name: "Lounge", manufacturer: nil, model: nil,
                                avTransport: URL(string: "http://192.168.0.215:49154/AVTransport/ctrl")!, renderingControl: nil)
        engine = DLNAEngine(control: DLNAControl(renderer: renderer, transport: FakeTransport { speaker.respond($0) }),
                            autoPoll: false, clock: { speaker.now })
        player = PlayerController(engine: engine)
        songs = lengths.map { Track(id: $0.0, title: $0.0, duration: TimeInterval($0.1) / 1000) }
        for (name, ms) in lengths { speaker.durations["http://h/\(name)"] = ms }
        player.resolve = { entry in
            guard case let .track(t) = entry else { return nil }
            return AudioSource(url: URL(string: "http://h/\(t.id)")!, info: .init(title: t.title, durationMs: Int(t.duration * 1000)))
        }
    }

    func settle() async { for _ in 0..<300 { await Task.yield() } }

    func start() async {
        player.play(songs)
        await settle()
    }

    /// Time passes in polls of [every] ms.
    func run(_ ms: Int, every: Int = 1_000) async {
        var left = ms
        while left > 0 {
            speaker.now += every
            left -= every
            await engine.pollOnce()
            await settle()
        }
    }

    var currentTitle: String? { player.current?.title }
    func count(_ call: String) -> Int { speaker.calls.filter { $0 == call }.count }
}

@MainActor
struct DLNAGaplessTests {
    @Test func itemIDsHoweverTheyreQuoted() {
        #expect(DIDL.itemID(#"<DIDL-Lite><item id="175" parentID="-1"></item></DIDL-Lite>"#) == "175")
        #expect(DIDL.itemID("<item id=&quot;175&quot; parentID=&quot;-1&quot;>") == "175", "the Yamaha's")
        #expect(DIDL.itemID("<DIDL-Lite></DIDL-Lite>") == nil && DIDL.itemID(nil) == nil)
    }

    @Test func aSpeakerThatSaysSoIsFollowedWithoutCueingAgain() async {
        let rig = Rig([("a", 10_000), ("b", 20_000), ("c", 15_000)])
        await rig.start()
        await rig.run(3_000)
        #expect(rig.count("setNext:b") == 1, "armed: \(rig.speaker.calls)")
        await rig.run(9_000)
        #expect(rig.currentTitle == "b")
        #expect(rig.count("setUri:b") == 0, "the speaker moved on by itself")
        #expect(rig.player.isPlaying)
        await rig.run(3_000)
        #expect(rig.count("setNext:c") == 1, "the next hand-off is armed too")
    }

    @Test func aFrozenSpeakerIsFollowedThroughEveryHandOffAndStaysGapless() async {
        // The Yamaha RX-V483: PLAYING at a's end for good after the first hand-off.
        let rig = Rig([("a", 10_000), ("b", 20_000), ("c", 15_000)]) { $0.freezeAtHandoff = true }
        await rig.start()
        await rig.run(3_000)
        #expect(rig.count("setNext:b") == 1)
        await rig.run(14_000)
        #expect(rig.currentTitle == "b", "followed onto b")
        #expect(rig.player.position > 3 && rig.player.position < 9, "from the clock, not a's frozen end: \(rig.player.position)")
        #expect(rig.player.duration == 20)
        await rig.run(3_000)
        #expect(rig.count("setNext:c") == 1, "c is armed gaplessly too")
        await rig.run(16_000)
        #expect(rig.currentTitle == "c", "the clock follows onto c")
        #expect(rig.player.duration == 15)
        #expect(rig.count("setUri:b") == 0 && rig.count("setUri:c") == 0, "only ever SetNext: \(rig.speaker.calls)")
    }

    @Test func trackMetaDataShowsAHandOffAtOnce() async {
        let rig = Rig([("a", 10_000), ("b", 20_000)]) {
            $0.freezeAtHandoff = true
            $0.metadataFollows = true
            $0.doubleEscapeMetadata = true
        }
        await rig.start()
        await rig.run(3_000)
        await rig.run(8_000)
        #expect(rig.currentTitle == "b", "the first poll past a's end")
        #expect(rig.count("setUri:b") == 0)
    }

    @Test func getMediaInfoShowsAHandOffAtOnce() async {
        let rig = Rig([("a", 10_000), ("b", 20_000)]) {
            $0.freezeAtHandoff = true
            $0.answersMediaInfo = true
        }
        await rig.start()
        await rig.run(11_000)
        #expect(rig.currentTitle == "b")
        #expect(rig.count("setUri:b") == 0)
    }

    @Test func pollsThatComeLateDontReplayASong() async {
        // A Mac that dozed: polls 6 s apart.
        let rig = Rig([("a", 10_000), ("b", 30_000), ("c", 30_000)]) { $0.freezeAtHandoff = true }
        await rig.start()
        await rig.run(60_000, every: 6_000)
        #expect(rig.count("setUri:b") == 0 && rig.count("setUri:c") == 0, "\(rig.speaker.calls)")
        #expect(rig.currentTitle == "c")
    }

    @Test func aSpeakerThatStopsAfterPlayingTheArmedSongTooDoesntReplayIt() async {
        // a hands off to b with the reports frozen at a's end, and it stops once b has also finished,
        // before the frozen report gave the hand-off away. The queue lands past b.
        let rig = Rig([("a", 10_000), ("b", 4_000), ("c", 6_000)]) { $0.freezeAtHandoff = true }
        await rig.start()
        await rig.run(3_000)
        #expect(rig.count("setNext:b") == 1)
        await rig.run(11_000)
        #expect(rig.count("setUri:b") == 0, "never cued again: \(rig.speaker.calls)")
        #expect(rig.currentTitle == "c")
    }

    @Test func aSpeakerWithoutSetNextGetsEachSongWhenTheLastStops() async {
        let rig = Rig([("a", 10_000), ("b", 20_000)]) { $0.supportsSetNext = false }
        await rig.start()
        await rig.run(13_000)
        #expect(rig.currentTitle == "b")
        #expect(rig.count("setUri:b") == 1)
        #expect(rig.count("setNext:b") == 0)
    }

    @Test func removingTheArmedSongFromTheQueueDisarmsIt() async {
        let rig = Rig([("a", 60_000), ("b", 20_000), ("c", 20_000)])
        await rig.start()
        await rig.run(3_000)
        #expect(rig.count("setNext:b") == 1)
        rig.player.remove(rig.player.queue.upNext[0].id)
        await rig.settle()
        await rig.run(2_000)
        #expect(rig.count("setNext:c") == 1, "c replaces it: \(rig.speaker.calls)")
    }

    @Test func aSpeakerWithoutPauseIsStoppedAndCuedAgainAtItsPlace() async {
        let rig = Rig([("a", 60_000), ("b", 20_000)]) { $0.supportsPause = false }
        await rig.start()
        await rig.run(10_000)
        rig.player.pause()
        await rig.settle()
        #expect(rig.count("stop") == 1 && !rig.player.isPlaying)
        rig.speaker.now += 5_000
        await rig.engine.pollOnce()
        rig.player.resume()
        await rig.settle()
        #expect(rig.count("setUri:a") == 2 && rig.count("seek") == 1, "\(rig.speaker.calls)")
        #expect(rig.player.isPlaying)
    }

    @Test func aSongThatRestartsIsntTakenForTheHandOff() async {
        // Next pressed: an ordinary load, with the speaker stopped first.
        let rig = Rig([("a", 60_000), ("b", 20_000)])
        await rig.start()
        await rig.run(3_000)
        rig.player.next()
        await rig.settle()
        #expect(rig.count("setUri:b") == 1 && rig.count("stop") == 1, "\(rig.speaker.calls)")
    }

    @Test func whileItPlaysTheAppIsTold() async {
        let rig = Rig([("a", 60_000)])
        var states: [Bool] = []
        rig.engine.onPlayingChanged = { states.append($0) }
        await rig.start()
        rig.player.pause()
        await rig.settle()
        #expect(states == [true, false])
    }
}

struct PlayNowLinkCacheTests {
    private let url = URL(string: "http://192.168.0.101/v1/play-now/s1/audio?sig=x")!

    @Test func aLinkCountsForSixHours() {
        var cache = PlayNowLinkCache()
        let t = Date(timeIntervalSince1970: 1_000_000)
        cache.put("s1", url, now: t)
        #expect(cache.fresh("s1", now: t.addingTimeInterval(5 * 3600)) == url)
        #expect(cache.fresh("s1", now: t.addingTimeInterval(6 * 3600)) == nil)
    }

    @Test func aRefusedStreamIsLeftAloneForAWhile() {
        var cache = PlayNowLinkCache()
        let t = Date(timeIntervalSince1970: 1_000_000)
        cache.failed("s1", now: t)
        #expect(cache.recentlyFailed("s1", now: t.addingTimeInterval(29)))
        #expect(!cache.recentlyFailed("s1", now: t.addingTimeInterval(31)))
        cache.failed("s2", now: t)
        cache.put("s2", url, now: t)
        #expect(!cache.recentlyFailed("s2", now: t), "a link clears it")
    }
}
