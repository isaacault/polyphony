import Foundation
import Testing
@testable import PolyphonyKit

struct StreamChoiceTests {
    @Test func oggProfilesComeAsMP3OfAboutTheSameQualityOnAMac() {
        #expect(StreamChoice.forMac(.balanced, container: "flac") == StreamChoice(format: "mp3", maxBitRateKbps: 160))
        #expect(StreamChoice.forMac(.high, container: "flac") == StreamChoice(format: "mp3", maxBitRateKbps: 192))
        #expect(StreamChoice.forMac(.saver, container: "flac") == StreamChoice(format: "mp3", maxBitRateKbps: 96))
    }

    @Test func theOriginalIsTheFileUnlessThisMacCantPlayIt() {
        #expect(StreamChoice.forMac(.original, container: "flac").isOriginal)
        #expect(StreamChoice.forMac(.original, container: nil).isOriginal)
        #expect(StreamChoice.forMac(.original, container: "ogg") == StreamChoice(format: "mp3", maxBitRateKbps: 320))
    }

    @Test func aRendererGetsTheProfileAsItIs() {
        #expect(StreamChoice.forRenderer(.balanced) == StreamChoice(format: "opus", maxBitRateKbps: 128))
        #expect(StreamChoice.forRenderer(.castSafe) == StreamChoice(format: "mp3", maxBitRateKbps: 320))
        #expect(StreamChoice.forRenderer(.original).isOriginal)
    }
}

struct RendererFormatTests {
    @Test func aSinkListGivesWhatItPlays() {
        let f = RendererFormats.parse(sink: "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3,http-get:*:audio/flac:*,http-get:*:audio/L16;rate=44100:*")
        #expect(f.flac && f.mp3 && f.wav && !f.aac && !f.opus && !f.unknown)
        #expect(f.supports("audio/flac") && !f.supports("audio/ogg"))
        #expect(f.preferredCodecs == [.flac, .mp3])
    }

    @Test func aSpeakerThatWontSayGetsTheBenefitOfTheDoubt() {
        let f = RendererFormats.parse(sink: nil)
        #expect(f.unknown && f.supports("audio/ogg") && f.supports(nil))
    }

    @Test func aProfileTheSpeakerRefusesBecomesOneItTakes() {
        let mp3Only = RendererFormats(mimeTypes: ["audio/mpeg"])
        let c = RendererCodecFallback.choose(.balanced, container: "flac", formats: mp3Only)
        #expect(c.profile.id == TranscodeProfile.castSafe.id && c.mime == "audio/mpeg" && c.substituted)
        let same = RendererCodecFallback.choose(.original, container: "mp3", formats: mp3Only)
        #expect(same.profile.id == TranscodeProfile.originalID && !same.substituted)
        let flacOnFlac = RendererCodecFallback.choose(.original, container: "wav", formats: RendererFormats(mimeTypes: ["audio/flac"]))
        #expect(flacOnFlac.profile.id == TranscodeProfile.losslessCapped.id && flacOnFlac.mime == "audio/flac")
    }

    @Test func aDeviceDescriptionGivesTheConnectionManager() throws {
        let xml = #"<root><device><friendlyName>L</friendlyName><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL></service><service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm</controlURL></service></serviceList></device></root>"#
        let r = try #require(Renderer.parse(description: Data(xml.utf8), location: URL(string: "http://h:1/d.xml")!))
        #expect(r.connectionManager?.absoluteString == "http://h:1/cm")
    }

    @Test func theSpeakerIsAskedWhatItPlays() async throws {
        let renderer = Renderer(udn: "u", name: "L", manufacturer: nil, model: nil, avTransport: URL(string: "http://h/av")!,
                                renderingControl: nil, connectionManager: URL(string: "http://h/cm")!)
        let fake = FakeTransport { _ in
            HTTPResponse(status: 200, body: Data("<s:Envelope xmlns:s=\"x\"><s:Body><u:GetProtocolInfoResponse><Sink>http-get:*:audio/mpeg:*</Sink></u:GetProtocolInfoResponse></s:Body></s:Envelope>".utf8))
        }
        let f = await DLNAControl(renderer: renderer, transport: fake).formats()
        #expect(f.mp3 && !f.flac && !f.unknown)
    }
}

struct PrefetchPlanTests {
    private func song(_ id: String, minutes: Int, onDevice: Bool = false, fetchable: Bool = true) -> PrefetchPlan.Upcoming {
        .init(trackID: id, durationMs: minutes * 60_000, onDevice: onDevice, fetchable: fetchable)
    }

    @Test func twentyMinutesOfMusicOnAnUnmeteredConnection() {
        let songs = (0..<20).map { song("s\($0)", minutes: 5) }
        #expect(PrefetchPlan.pick(songs, PrefetchPlan.unmetered).count == 4)
    }

    @Test func atLeastTwoSongsAndAtMostTen() {
        #expect(PrefetchPlan.pick([song("long", minutes: 60), song("next", minutes: 60), song("no", minutes: 1)], PrefetchPlan.unmetered) == ["long", "next"])
        #expect(PrefetchPlan.pick((0..<30).map { song("s\($0)", minutes: 1) }, PrefetchPlan.unmetered).count == 10)
    }

    @Test func downloadedSongsCountButArentFetched() {
        let songs = [song("have", minutes: 15, onDevice: true), song("playnow", minutes: 3, fetchable: false), song("want", minutes: 3), song("no", minutes: 3)]
        #expect(PrefetchPlan.pick(songs, PrefetchPlan.unmetered) == ["want"])
    }

    @Test func theBudgetGoesWithTheConnection() {
        let wifi = NetworkState(kind: .wifi, isMetered: false), hotspot = NetworkState(kind: .wifi, isMetered: true)
        #expect(PrefetchPlan.budget(wifi, enabled: true, onMetered: false) == PrefetchPlan.unmetered)
        #expect(PrefetchPlan.budget(hotspot, enabled: true, onMetered: true) == PrefetchPlan.metered)
        #expect(PrefetchPlan.budget(hotspot, enabled: true, onMetered: false) == nil)
        #expect(PrefetchPlan.budget(wifi, enabled: false, onMetered: true) == nil)
        #expect(PrefetchPlan.budget(.offline, enabled: true, onMetered: true) == nil)
    }
}

struct LatencyMonitorTests {
    @Test func enoughStallsInTheWindowTripIt() {
        var m = LatencyMonitor()
        for t in [0, 10_000] {
            m.stallStarted(nowMs: t)
            m.stallEnded(nowMs: t + 500)
        }
        #expect(!m.degraded)
        m.stallStarted(nowMs: 20_000)
        #expect(m.degraded)
    }

    @Test func oldStallsDontCount() {
        var m = LatencyMonitor()
        for t in [0, 100_000, 240_000] {
            m.stallStarted(nowMs: t)
            m.stallEnded(nowMs: t + 500)
        }
        #expect(!m.degraded)
    }

    @Test func aLongStallTripsItAtOnceEvenWhileItLasts() {
        var m = LatencyMonitor()
        m.stallStarted(nowMs: 0)
        m.tick(nowMs: 3_000)
        #expect(!m.degraded)
        m.tick(nowMs: 4_000)
        #expect(m.degraded)
    }

    @Test func qualityComesBackAfterAWhileWithoutStalls() {
        var m = LatencyMonitor()
        m.stallStarted(nowMs: 0)
        m.stallEnded(nowMs: 5_000)
        #expect(m.degraded)
        m.tick(nowMs: 604_000)
        #expect(m.degraded)
        m.tick(nowMs: 605_000)
        #expect(!m.degraded)
    }

    @Test func zeroMeansOnlyByHand() {
        var m = LatencyMonitor()
        m.settings.recoverAfterSeconds = 0
        m.stallStarted(nowMs: 0)
        m.stallEnded(nowMs: 5_000)
        m.tick(nowMs: 100_000_000)
        #expect(m.degraded)
        m.reset()
        #expect(!m.degraded)
    }

    @Test func offMeansNothingCounts() {
        var m = LatencyMonitor()
        m.settings.enabled = false
        m.stallStarted(nowMs: 0)
        m.stallEnded(nowMs: 50_000)
        #expect(!m.degraded)
    }
}

struct HomeNetworkTests {
    @Test func theLANAddressIsTheReceiverOrAPrivateBaseURL() {
        let pub = URL(string: "https://continuo.ault.me")!
        #expect(HomeNetwork.lanBase(receiver: "192.168.0.101/", baseURL: pub)?.absoluteString == "http://192.168.0.101")
        #expect(HomeNetwork.lanBase(receiver: nil, baseURL: pub) == nil)
        #expect(HomeNetwork.lanBase(receiver: " ", baseURL: URL(string: "http://192.168.1.5:4533")!)?.absoluteString == "http://192.168.1.5:4533")
        #expect(HomeNetwork.lanBase(receiver: nil, baseURL: URL(string: "http://8.8.8.8")!) == nil)
        #expect(HomeNetwork.lanBase(receiver: nil, baseURL: URL(string: "http://172.16.0.1")!) != nil)
    }

    private let lan = URL(string: "http://192.168.0.101")!

    @Test func homeIsTheServersOwnErrorDocument() async {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: Data("<subsonic-response status=\"failed\"/>".utf8)) }
        #expect(await HomeNetwork.check(kind: .wifi, lan: lan, subsonic: true, transport: fake) == true)
        #expect(fake.calls.first?.url.path == "/rest/stream")
        let stranger = FakeTransport { _ in HTTPResponse(status: 200, body: Data("<html>router</html>".utf8)) }
        #expect(await HomeNetwork.check(kind: .wifi, lan: lan, subsonic: true, transport: stranger) == false)
    }

    @Test func mobileDataIsNeverHomeAndUnknownStaysUnknown() async {
        let fake = FakeTransport { _ in HTTPResponse(status: 200) }
        #expect(await HomeNetwork.check(kind: .cellular, lan: lan, subsonic: true, transport: fake) == false)
        #expect(await HomeNetwork.check(kind: .none, lan: lan, subsonic: true, transport: fake) == nil)
        #expect(await HomeNetwork.check(kind: .wifi, lan: nil, subsonic: true, transport: fake) == nil)
        #expect(fake.calls.isEmpty)
    }
}

@MainActor
struct StreamingRulesTests {
    private let wifi = NetworkState(kind: .wifi, isMetered: false)

    @Test func theOldSingleChoiceBecomesRules() {
        let stored = StreamingRules.migrated(bitrateKbps: 192)
        #expect(stored.policy.defaultProfileID == "custom.mp3.192")
        #expect(stored.profiles.first?.codec == .mp3 && stored.profiles.first?.maxBitrateKbps == 192)
        #expect(StreamingRules.migrated(bitrateKbps: 0).profiles.isEmpty)
    }

    @Test func storedRulesSurviveJSON() throws {
        var stored = StreamingRules.Stored()
        stored.policy.wifiProfileID = "builtin.high"
        stored.deviceProfiles["dlna:u"] = "builtin.castsafe"
        let back = try JSONDecoder().decode(StreamingRules.Stored.self, from: JSONEncoder().encode(stored))
        #expect(back == stored)
    }

    @Test func goingAwayChangesTheDecisionAndSaysSo() {
        let rules = StreamingRules()
        rules.setNetwork(wifi)
        var changes = 0
        rules.onChange = { changes += 1 }
        rules.setAtHome(true)
        #expect(rules.decision().profile.id == TranscodeProfile.originalID && changes == 0)
        rules.setAtHome(false)
        #expect(rules.decision().reason == .awayRule && changes == 1)
    }

    @Test func aLocalMP3StaysAnMP3ButAStruggleCapsIt() {
        let rules = StreamingRules()
        rules.setNetwork(NetworkState(kind: .cellular, isMetered: true))
        #expect(rules.localDecision(container: "mp3", lossless: false).reason == .lossySource)
        #expect(rules.localDecision(container: "flac", lossless: true).profile.id == TranscodeProfile.balancedID)
        #expect(rules.localDecision(container: "ogg", lossless: false).profile.id == TranscodeProfile.balancedID, "this Mac can't play Ogg as it is")
    }

    @Test func stallsCapTheQualityAndANewNetworkClearsIt() {
        let rules = StreamingRules()
        rules.setNetwork(wifi)
        rules.stall(started: true, nowMs: 0)
        rules.stall(started: false, nowMs: 5_000)
        #expect(rules.latencyDegraded && rules.decision().cappedByLatency)
        rules.setNetwork(NetworkState(kind: .ethernet, isMetered: false))
        #expect(!rules.latencyDegraded)
    }

    @Test func deletingAProfileLeavesNothingPointingAtIt() {
        let rules = StreamingRules()
        let p = TranscodeProfile(id: "custom.1", name: "Mine", codec: .mp3, maxBitrateKbps: 256)
        rules.save(profile: p)
        rules.update {
            $0.defaultProfileID = p.id
            $0.wifiProfileID = p.id
            $0.adaptiveLatency.highLatencyProfileID = p.id
        }
        rules.setProfile(p.id, forDevice: "dlna:u")
        rules.delete(profile: p.id)
        #expect(rules.policy.defaultProfileID == TranscodeProfile.originalID && rules.policy.wifiProfileID == nil)
        #expect(rules.policy.adaptiveLatency.highLatencyProfileID == nil && rules.stored.deviceProfiles.isEmpty)
    }

    @Test func aChangeIsSavedAndADeviceRemembered() {
        let rules = StreamingRules()
        var saved: [StreamingRules.Stored] = []
        rules.persist = { saved.append($0) }
        rules.remember(.dlna(udn: "u", name: "Lounge"))
        rules.remember(.dlna(udn: "u", name: "Lounge"))
        #expect(saved.count == 1 && saved[0].knownDevices["dlna:u"] == "Lounge")
        rules.remember(.local)
        #expect(saved.count == 1)
    }
}

/// The player's part in the rules.
@MainActor
private final class RecordingEngine: AudioEngine {
    var onEvent: ((AudioEvent) -> Void)?
    var volume: Float = 1
    var loads: [AudioSource] = []
    var preloads: [AudioSource?] = []
    func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) { loads.append(source) }
    func preload(_ next: AudioSource?) { preloads.append(next) }
    func play() {}
    func pause() {}
    func seek(to time: TimeInterval) {}
    func stop() {}
}

@MainActor
struct PlayerStreamingTests {
    private let songs = (1...3).map { Track(id: "t\($0)", title: "t\($0)", duration: 100) }
    private func settle() async { for _ in 0..<100 { await Task.yield() } }

    @Test func theSongThatWasPreloadedIsTheOnePlayed() async {
        let engine = RecordingEngine()
        let player = PlayerController(engine: engine)
        var quality = "a"
        var asked: [String] = []
        player.resolve = { entry in
            asked.append(entry.title)
            return AudioSource(url: URL(string: "http://h/\(entry.title)?q=\(quality)")!)
        }
        player.play(songs)
        await settle()
        #expect(engine.loads.last?.url.absoluteString == "http://h/t1?q=a")
        #expect(engine.preloads.last??.url.absoluteString == "http://h/t2?q=a")
        quality = "b"
        engine.onEvent?(.ended)
        await settle()
        #expect(engine.loads.last?.url.absoluteString == "http://h/t2?q=a", "as it was armed, not asked for afresh")
        #expect(engine.preloads.last??.url.absoluteString == "http://h/t3?q=b")
        #expect(asked.filter { $0 == "t2" }.count == 1)
    }

    @Test func reloadingAsksAfresh() async {
        let engine = RecordingEngine()
        let player = PlayerController(engine: engine)
        var quality = "a"
        player.resolve = { AudioSource(url: URL(string: "http://h/\($0.title)?q=\(quality)")!) }
        player.play(songs)
        await settle()
        quality = "b"
        player.reloadCurrent()
        await settle()
        #expect(engine.loads.last?.url.absoluteString == "http://h/t1?q=b")
    }

    @Test func aRebufferWhilePlayingIsAStallButLoadingAndSeekingArent() async {
        let engine = RecordingEngine()
        let player = PlayerController(engine: engine)
        player.resolve = { AudioSource(url: URL(string: "http://h/\($0.title)")!) }
        var at = Date(timeIntervalSince1970: 1_000)
        player.now = { at }
        var stalls: [Bool] = []
        player.onStall = { stalls.append($0) }
        player.play(songs)
        await settle()
        engine.onEvent?(.buffering(true))
        engine.onEvent?(.started)
        engine.onEvent?(.buffering(false))
        #expect(stalls.isEmpty, "the song starting")
        at = at.addingTimeInterval(10)
        engine.onEvent?(.buffering(true))
        engine.onEvent?(.buffering(false))
        #expect(stalls == [true, false])
        player.seek(to: 50)
        engine.onEvent?(.buffering(true))
        #expect(stalls == [true, false], "a seek")
    }

    @Test func theQueueChangingIsAnnounced() async {
        let engine = RecordingEngine()
        let player = PlayerController(engine: engine)
        player.resolve = { AudioSource(url: URL(string: "http://h/\($0.title)")!) }
        var told = 0
        player.onUpcomingChanged = { told += 1 }
        player.play(songs)
        await settle()
        let before = told
        player.addToQueue([.track(songs[0])])
        #expect(told == before + 1)
    }

    @Test func withoutPreloadingNothingIsQueuedAhead() async {
        let engine = RecordingEngine()
        let player = PlayerController(engine: engine)
        player.resolve = { AudioSource(url: URL(string: "http://h/\($0.title)")!) }
        player.preloadsNext = false
        player.play(songs)
        await settle()
        #expect(engine.preloads.allSatisfy { $0 == nil })
    }
}
