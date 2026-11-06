import Foundation
import Testing
@testable import PolyphonyKit

struct CastMessageTests {
    @Test func aMessageSurvivesTheWire() {
        let m = CastMessage(source: "sender-0", destination: "receiver-0", namespace: "urn:x-cast:com.google.cast.tp.connection", payload: #"{"type":"CONNECT"}"#)
        var framer = CastFramer()
        #expect(framer.add(m.frame) == [m])
    }

    @Test func theFrameIsWhatADeviceExpects() {
        let m = CastMessage(source: "s", destination: "d", namespace: "n", payload: "p")
        // Length 16, then: version 0, source, destination, namespace, type string, payload.
        #expect([UInt8](m.frame) == [0, 0, 0, 16, 0x08, 0, 0x12, 1, 0x73, 0x1A, 1, 0x64, 0x22, 1, 0x6E, 0x28, 0, 0x32, 1, 0x70])
    }

    @Test func aLongPayloadsLengthIsAVarint() {
        let payload = String(repeating: "x", count: 300)
        let m = CastMessage(source: "s", destination: "d", namespace: "n", payload: payload)
        var framer = CastFramer()
        #expect(framer.add(m.frame).first?.payload == payload)
    }

    @Test func messagesArriveHoweverTheBytesDo() {
        let a = CastMessage(source: "s", destination: "d", namespace: "n", payload: "one")
        let b = CastMessage(source: "s", destination: "d", namespace: "n", payload: "two")
        var framer = CastFramer()
        var got: [CastMessage] = []
        for byte in a.frame + b.frame { got += framer.add(Data([byte])) }
        #expect(got == [a, b])
        #expect(framer.add(a.frame + b.frame) == [a, b], "and together")
    }

    @Test func nonsenseIsDropped() {
        #expect(CastMessage.decode(Data([0xFF, 0xFF])) == nil)
        var framer = CastFramer()
        #expect(framer.add(Data([0xFF, 0xFF, 0xFF, 0xFF, 1, 2, 3])).isEmpty, "an impossible length")
    }
}

/// A Cast device scripted at the socket: what it was told, and statuses to send back.
@MainActor
private final class FakeCastSocket: CastSocket {
    var onConnected: (() -> Void)?
    var onBytes: ((Data) -> Void)?
    var onClosed: ((String?) -> Void)?
    var closed = false
    private var framer = CastFramer()
    private(set) var sent: [(namespace: String, destination: String, body: [String: Any])] = []
    var autoReply = true

    func connect() { onConnected?() }
    func close() { closed = true }

    func send(_ data: Data) {
        for m in framer.add(data) {
            let body = (try? JSONSerialization.jsonObject(with: Data(m.payload.utf8)) as? [String: Any]) ?? [:]
            sent.append((m.namespace, m.destination, body))
            if autoReply, body["type"] as? String == "LAUNCH" { receiver(running: true) }
        }
    }

    func types(_ namespace: String) -> [String] {
        sent.filter { $0.namespace.hasSuffix(namespace) }.compactMap { $0.body["type"] as? String }
    }

    func last(_ type: String) -> [String: Any]? { sent.last { $0.body["type"] as? String == type }?.body }

    func deliver(_ namespace: String, _ object: [String: Any], source: String = "receiver-0") {
        let body = String(decoding: try! JSONSerialization.data(withJSONObject: object), as: UTF8.self)
        onBytes?(CastMessage(source: source, destination: "sender", namespace: "urn:x-cast:com.google.cast.\(namespace)", payload: body).frame)
    }

    func receiver(running: Bool, volume: Double = 0.4) {
        var status: [String: Any] = ["volume": ["level": volume]]
        if running { status["applications"] = [["appId": CastEngine.defaultMediaReceiver, "transportId": "web-1", "sessionId": "sess-1"]] }
        deliver("receiver", ["type": "RECEIVER_STATUS", "status": status])
    }

    /// A media status for what's playing.
    func media(_ state: String, url: String?, time: Double = 0, idle: String? = nil, itemID: Int? = 1,
               items: [(Int, String)] = [], duration: Double? = nil) {
        var status: [String: Any] = ["mediaSessionId": 7, "playerState": state, "currentTime": time]
        if let url {
            var media: [String: Any] = ["contentId": url]
            if let duration { media["duration"] = duration }
            status["media"] = media
        }
        if let idle { status["idleReason"] = idle }
        if let itemID { status["currentItemId"] = itemID }
        if !items.isEmpty { status["items"] = items.map { ["itemId": $0.0, "media": ["contentId": $0.1]] as [String: Any] } }
        deliver("media", ["type": "MEDIA_STATUS", "status": [status]], source: "web-1")
    }
}

private final class ClockBox: @unchecked Sendable { var now = 1_000_000 }

@MainActor
struct CastEngineTests {
    private let a = "http://192.168.0.101/rest/stream?id=a"
    private let b = "http://192.168.0.101/rest/stream?id=b"

    private func source(_ url: String, title: String = "Song") -> AudioSource {
        AudioSource(url: URL(string: url)!, info: .init(title: title, artist: "Artist", album: "Album", artworkURL: URL(string: "http://h/c.jpg"), mimeType: "audio/mpeg", durationMs: 180_000))
    }

    private func engine(clock: ClockBox = ClockBox()) -> (CastEngine, FakeCastSocket) {
        let socket = FakeCastSocket()
        let engine = CastEngine(device: CastDevice(id: "d", name: "Lounge"), socket: socket, autoTick: false, clock: { clock.now })
        return (engine, socket)
    }

    @Test func connectingAsksWhatsRunning() {
        let (_, socket) = engine()
        #expect(socket.types("tp.connection") == ["CONNECT"] && socket.types("receiver") == ["GET_STATUS"])
        #expect(socket.sent[0].destination == "receiver-0")
    }

    @Test func aSongStartsTheReceiverThenLoads() throws {
        let (e, socket) = engine()
        socket.receiver(running: false)
        e.load(source(a), startAt: 12, autoplay: true)
        #expect(socket.types("receiver").contains("LAUNCH"))
        #expect(socket.last("LAUNCH")?["appId"] as? String == "CC1AD845")
        let load = try #require(socket.sent.last { $0.body["type"] as? String == "LOAD" })
        #expect(load.destination == "web-1", "the receiver app's transport")
        #expect(socket.sent.contains { $0.destination == "web-1" && $0.body["type"] as? String == "CONNECT" })
        #expect(load.body["currentTime"] as? Double == 12 && load.body["autoplay"] as? Bool == true)
        let media = try #require(load.body["media"] as? [String: Any])
        #expect(media["contentId"] as? String == a && media["contentType"] as? String == "audio/mpeg" && media["streamType"] as? String == "BUFFERED")
        let metadata = try #require(media["metadata"] as? [String: Any])
        #expect(metadata["metadataType"] as? Int == 3 && metadata["title"] as? String == "Song" && metadata["artist"] as? String == "Artist")
    }

    @Test func aReceiverAlreadyRunningIsUsed() {
        let (e, socket) = engine()
        socket.receiver(running: true)
        e.load(source(a), startAt: 0, autoplay: true)
        #expect(!socket.types("receiver").contains("LAUNCH") && socket.types("media").contains("LOAD"))
    }

    private func playing(_ e: CastEngine, _ socket: FakeCastSocket) -> [AudioEvent] {
        socket.receiver(running: true)
        e.load(source(a), startAt: 0, autoplay: true)
        socket.media("PLAYING", url: a, time: 1, duration: 200)
        return []
    }

    @Test func statusesBecomeEventsAndPositionRunsOnTheClock() {
        let clock = ClockBox()
        let (e, socket) = engine(clock: clock)
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        _ = playing(e, socket)
        #expect(events.contains(.started) && events.contains(.buffering(false)))
        clock.now += 4_000
        e.tick()
        #expect(events.last == .position(5, duration: 200))
        socket.media("PAUSED", url: a, time: 5)
        #expect(events.last == .paused)
    }

    @Test func controlsReachTheMediaSession() throws {
        let (e, socket) = engine()
        _ = playing(e, socket)
        e.pause()
        e.play()
        e.seek(to: 30)
        e.setRate(1.5)
        #expect(socket.types("media").suffix(4) == ["PAUSE", "PLAY", "SEEK", "SET_PLAYBACK_RATE"])
        #expect(socket.last("SEEK")?["mediaSessionId"] as? Int == 7 && socket.last("SEEK")?["currentTime"] as? Double == 30)
    }

    @Test func theEndOfTheLastSongIsTheEnd() {
        let (e, socket) = engine()
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        _ = playing(e, socket)
        socket.media("IDLE", url: nil, idle: "FINISHED")
        #expect(events.last == .ended)
    }

    @Test func theNextSongIsQueuedAndTheHandOffFollowedWithoutLoadingAgain() throws {
        let (e, socket) = engine()
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        _ = playing(e, socket)
        e.preload(source(b))
        let insert = try #require(socket.last("QUEUE_INSERT"))
        let items = try #require(insert["items"] as? [[String: Any]])
        #expect((items[0]["media"] as? [String: Any])?["contentId"] as? String == b)
        let loads = socket.types("media").filter { $0 == "LOAD" }.count
        socket.media("PLAYING", url: b, itemID: 2, items: [(2, b)])
        #expect(events.last == .ended)
        e.load(source(b), startAt: 0, autoplay: true)
        #expect(socket.types("media").filter { $0 == "LOAD" }.count == loads, "it's already playing")
        #expect(events.last == .started)
    }

    @Test func aChangedQueueReplacesWhatWasQueued() throws {
        let (e, socket) = engine()
        _ = playing(e, socket)
        e.preload(source(b))
        socket.media("PLAYING", url: a, itemID: 1, items: [(1, a), (2, b)])
        e.preload(source("http://192.168.0.101/rest/stream?id=c"))
        #expect(socket.last("QUEUE_REMOVE")?["itemIds"] as? [Int] == [2])
        let inserted = try #require(socket.last("QUEUE_INSERT")?["items"] as? [[String: Any]])
        #expect((inserted[0]["media"] as? [String: Any])?["contentId"] as? String == "http://192.168.0.101/rest/stream?id=c")
    }

    @Test func aNextSongAskedForBeforeTheLoadLandsWaitsForIt() {
        let (e, socket) = engine()
        socket.receiver(running: true)
        e.load(source(a), startAt: 0, autoplay: true)
        e.preload(source(b))
        #expect(!socket.types("media").contains("QUEUE_INSERT"))
        socket.media("PLAYING", url: a)
        #expect(socket.types("media").contains("QUEUE_INSERT"))
    }

    @Test func failuresSaySo() {
        let (e, socket) = engine()
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        _ = playing(e, socket)
        socket.deliver("media", ["type": "LOAD_FAILED"], source: "web-1")
        #expect(events.last == .failed("Lounge couldn't play this song."))
        socket.media("IDLE", url: nil, idle: "ERROR")
        #expect(events.last == .failed("Lounge couldn't play this song."))
    }

    @Test func stoppedFromElsewhereIsAPauseNotAnEnd() {
        let (e, socket) = engine()
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        _ = playing(e, socket)
        socket.media("IDLE", url: nil, idle: "CANCELLED")
        #expect(!events.contains(.ended) && events.last == .paused)
    }

    @Test func anotherAppTakingTheDeviceIsAPause() {
        let (e, socket) = engine()
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        _ = playing(e, socket)
        socket.deliver("tp.connection", ["type": "CLOSE"], source: "web-1")
        #expect(events.last == .paused)
    }

    @Test func theHeartbeatIsKeptAndADeadDeviceNoticed() {
        let (e, socket) = engine()
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        socket.deliver("tp.heartbeat", ["type": "PING"])
        #expect(socket.types("tp.heartbeat") == ["PONG"], "its pings answered")
        for _ in 0..<5 { e.tick() }
        #expect(socket.types("tp.heartbeat").last == "PING")
        socket.deliver("tp.heartbeat", ["type": "PONG"])
        for _ in 0..<15 { e.tick() }
        #expect(!events.contains { if case .failed = $0 { true } else { false } }, "3 unanswered so far")
        for _ in 0..<5 { e.tick() }
        #expect(events.contains(.failed("Couldn't reach Lounge: the Cast device stopped answering")))
    }

    @Test func aDroppedConnectionIsReported() {
        let (e, socket) = engine()
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        socket.onClosed?("connection reset")
        #expect(events.last == .failed("Couldn't reach Lounge: connection reset"))
    }

    @Test func theDevicesVolumeIsAdoptedAndChangesSentLatestWinning() async throws {
        let (e, socket) = engine()
        socket.receiver(running: false, volume: 0.31)
        #expect(abs(e.volume - 0.31) < 0.001 && !socket.types("receiver").contains("SET_VOLUME"), "read, not sent back")
        e.volume = 0.5
        e.volume = 0.6
        try await Task.sleep(for: .milliseconds(300))
        let sets = socket.sent.filter { $0.body["type"] as? String == "SET_VOLUME" }
        #expect(sets.count == 1)
        let level = try #require((sets[0].body["volume"] as? [String: Any])?["level"] as? Double)
        #expect(abs(level - 0.6) < 0.001)
    }

    @Test func stoppingLeavesTheAppAndDetachingLetsGo() {
        let (e, socket) = engine()
        var playing: [Bool] = []
        e.onPlayingChanged = { playing.append($0) }
        _ = self.playing(e, socket)
        e.stop()
        #expect(socket.types("media").last == "STOP")
        #expect(socket.last("STOP")?["sessionId"] as? String == "sess-1")
        e.detach()
        #expect(socket.closed && socket.types("tp.connection").last == "CLOSE")
        #expect(playing == [true, false])
    }

    @Test func underARealController() async {
        let socket = FakeCastSocket()
        let engine = CastEngine(device: CastDevice(id: "d", name: "Lounge"), socket: socket, autoTick: false)
        let player = PlayerController(engine: engine)
        let songs = ["a", "b"].map { Track(id: $0, title: $0, duration: 100) }
        player.resolve = { entry in
            AudioSource(url: URL(string: "http://h/\(entry.title)")!, info: .init(title: entry.title, durationMs: 100_000))
        }
        socket.receiver(running: true)
        player.play(songs)
        for _ in 0..<100 { await Task.yield() }
        socket.media("PLAYING", url: "http://h/a", duration: 100)
        for _ in 0..<100 { await Task.yield() }
        #expect(player.isPlaying && socket.types("media").contains("QUEUE_INSERT"))
        socket.media("PLAYING", url: "http://h/b", itemID: 2, items: [(2, "http://h/b")])
        for _ in 0..<100 { await Task.yield() }
        #expect(player.current?.title == "b" && player.isPlaying)
        #expect(socket.types("media").filter { $0 == "LOAD" }.count == 1, "the device moved on by itself")
    }
}
