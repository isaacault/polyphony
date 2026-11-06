import Foundation

// Google Cast without Google's SDK, which has no macOS edition: the same CASTV2 protocol every sender
// speaks, in the open. Protobuf frames over TLS to port 8009, JSON payloads per namespace, and the
// Default Media Receiver for the audio. The app supplies the socket and finding devices; everything
// else is here, so it's tested without one.

/// A Cast device found on the network.
public struct CastDevice: Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var model: String?

    public init(id: String, name: String, model: String? = nil) {
        self.id = id
        self.name = name
        self.model = model
    }
}

/// One CASTV2 message: the only protobuf this needs, by hand.
public struct CastMessage: Sendable, Equatable {
    public var source: String
    public var destination: String
    public var namespace: String
    public var payload: String

    public init(source: String, destination: String, namespace: String, payload: String) {
        self.source = source
        self.destination = destination
        self.namespace = namespace
        self.payload = payload
    }

    private static func varint(_ value: Int) -> [UInt8] {
        var v = UInt64(value), out: [UInt8] = []
        repeat {
            var byte = UInt8(v & 0x7F)
            v >>= 7
            if v != 0 { byte |= 0x80 }
            out.append(byte)
        } while v != 0
        return out
    }

    /// The message with its 4-byte big-endian length in front, as it goes on the wire.
    public var frame: Data {
        var body: [UInt8] = []
        func string(_ field: Int, _ s: String) {
            let bytes = Array(s.utf8)
            body += Self.varint(field << 3 | 2) + Self.varint(bytes.count) + bytes
        }
        body += Self.varint(1 << 3) + Self.varint(0) // protocol_version: CASTV2_1_0
        string(2, source)
        string(3, destination)
        string(4, namespace)
        body += Self.varint(5 << 3) + Self.varint(0) // payload_type: STRING
        string(6, payload)
        let n = UInt32(body.count)
        return Data([UInt8(n >> 24 & 0xFF), UInt8(n >> 16 & 0xFF), UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)] + body)
    }

    /// A frame's body (without its length). Nil when it isn't one, or carries a binary payload.
    public static func decode(_ data: Data) -> CastMessage? {
        let bytes = [UInt8](data)
        var i = 0
        func readVarint() -> Int? {
            var value = 0, shift = 0
            while i < bytes.count, shift < 63 {
                let b = bytes[i]
                i += 1
                value |= Int(b & 0x7F) << shift
                if b & 0x80 == 0 { return value }
                shift += 7
            }
            return nil
        }
        var message = CastMessage(source: "", destination: "", namespace: "", payload: "")
        var hasPayload = false
        while i < bytes.count {
            guard let tag = readVarint() else { return nil }
            switch tag & 7 {
            case 0:
                guard readVarint() != nil else { return nil }
            case 2:
                guard let length = readVarint(), length >= 0, i + length <= bytes.count else { return nil }
                let value = String(decoding: bytes[i..<(i + length)], as: UTF8.self)
                i += length
                switch tag >> 3 {
                case 2: message.source = value
                case 3: message.destination = value
                case 4: message.namespace = value
                case 6:
                    message.payload = value
                    hasPayload = true
                default: break
                }
            default:
                return nil
            }
        }
        return hasPayload ? message : nil
    }
}

/// Splits a byte stream back into messages, however it arrives.
public struct CastFramer: Sendable {
    private var buffer = Data()
    /// More than any sane message: something's wrong with the stream.
    private static let maximum = 1 << 20

    public init() {}

    public mutating func add(_ data: Data) -> [CastMessage] {
        buffer.append(data)
        var out: [CastMessage] = []
        while buffer.count >= 4 {
            let head = [UInt8](buffer.prefix(4))
            let length = Int(head[0]) << 24 | Int(head[1]) << 16 | Int(head[2]) << 8 | Int(head[3])
            if length > Self.maximum {
                buffer = Data()
                return out
            }
            guard buffer.count >= 4 + length else { break }
            let body = buffer.dropFirst(4).prefix(length)
            if let message = CastMessage.decode(Data(body)) { out.append(message) }
            buffer = Data(buffer.dropFirst(4 + length))
        }
        return out
    }
}

/// The TLS connection to a device, which the app makes (Network.framework).
@MainActor
public protocol CastSocket: AnyObject {
    var onConnected: (() -> Void)? { get set }
    var onBytes: ((Data) -> Void)? { get set }
    var onClosed: ((String?) -> Void)? { get set }
    func connect()
    func send(_ data: Data)
    func close()
}

/// PolyphonyKit's AudioEngine on a Cast device. It starts the Default Media Receiver, which fetches
/// the audio itself (the URL must be one it can reach). The next song is queued on the device, which
/// moves on by itself without a gap; the engine sees that in the media status, says `.ended`, and the
/// controller's load of that same song leaves it playing.
@MainActor
public final class CastEngine: AudioEngine {
    public static let defaultMediaReceiver = "CC1AD845"

    private enum NS {
        static let connection = "urn:x-cast:com.google.cast.tp.connection"
        static let heartbeat = "urn:x-cast:com.google.cast.tp.heartbeat"
        static let receiver = "urn:x-cast:com.google.cast.receiver"
        static let media = "urn:x-cast:com.google.cast.media"
    }

    private static let sender = "sender-polyphony"
    private static let platform = "receiver-0"
    /// Every 5 s a ping; 3 unanswered in a row means the device has gone.
    private static let heartbeatEvery = 5
    private static let missedHeartbeats = 3

    public var onEvent: ((AudioEvent) -> Void)?
    /// The device started or stopped playing: the app keeps the Mac awake meanwhile.
    public var onPlayingChanged: ((Bool) -> Void)?

    public var volume: Float = 0.5 {
        didSet {
            guard !adoptingVolume else { return }
            wantedVolume = min(max(volume, 0), 1)
            guard volumeSender == nil else { return }
            volumeSender = Task { [weak self] in
                // A slider dragged sends a few, the latest winning, not dozens.
                try? await Task.sleep(for: .milliseconds(150))
                guard let self else { return }
                self.volumeSender = nil
                if let level = self.wantedVolume { self.sendReceiver(["type": "SET_VOLUME", "volume": ["level": Double(level)]]) }
            }
        }
    }
    private var adoptingVolume = false
    private var wantedVolume: Float?
    private var volumeSender: Task<Void, Never>?

    public let device: CastDevice
    private let socket: CastSocket
    private let clock: @MainActor () -> Int
    private var framer = CastFramer()
    private var tickTask: Task<Void, Never>?
    private var requestID = 0
    private var ticks = 0
    private var unansweredPings = 0
    private var connected = false

    /// The Default Media Receiver running on the device.
    private var transportID: String?
    private var sessionID: String?
    private var launching = false
    private var mediaSessionID: Int?

    private var pendingLoad: (source: AudioSource, startAt: TimeInterval, autoplay: Bool)?
    private var currentSource: AudioSource?
    private var currentItemID: Int?
    /// The next song: wanted, and whether it's been queued on the device.
    private var wantedNext: AudioSource?
    private var queued: (source: AudioSource, itemID: Int?)?
    private var handedOff = false
    /// A load sent and not yet answered: statuses until then describe what came before.
    private var awaitingLoad = false
    private var loadRequestID: Int?
    private var rate: Float = 1

    private var isPlayingNow = false {
        didSet { if isPlayingNow != oldValue { onPlayingChanged?(isPlayingNow) } }
    }
    private var isBuffering = false
    private var positionSeconds: TimeInterval = 0
    private var sampledAt = 0
    private var duration: TimeInterval?

    public init(device: CastDevice, socket: CastSocket, autoTick: Bool = true,
                clock: @escaping @MainActor () -> Int = { Int(Date().timeIntervalSince1970 * 1000) }) {
        self.device = device
        self.socket = socket
        self.clock = clock
        socket.onConnected = { [weak self] in self?.didConnect() }
        socket.onBytes = { [weak self] data in self?.didReceive(data) }
        socket.onClosed = { [weak self] reason in self?.didClose(reason) }
        socket.connect()
        if autoTick {
            tickTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(1))
                    self?.tick()
                }
            }
        }
    }

    // MARK: AudioEngine

    public func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) {
        if handedOff, currentSource?.url == source.url, startAt == 0 {
            // The device already moved on to this song.
            handedOff = false
            if autoplay { onEvent?(.started) } else { pause() }
            return
        }
        handedOff = false
        currentSource = source
        queued = nil
        wantedNext = nil
        isPlayingNow = false
        positionSeconds = startAt
        sampledAt = clock()
        duration = source.info?.durationMs.map { TimeInterval($0) / 1000 }
        setBuffering(true)
        if transportID != nil {
            sendLoad(source, startAt: startAt, autoplay: autoplay)
        } else {
            pendingLoad = (source, startAt, autoplay)
            launchIfNeeded()
        }
    }

    public func preload(_ next: AudioSource?) {
        wantedNext = next
        if let queued, queued.source.url != next?.url {
            if let id = queued.itemID { sendMedia(["type": "QUEUE_REMOVE", "itemIds": [id]]) }
            self.queued = nil
        }
        queueNextIfNeeded()
    }

    public func play() { sendMedia(["type": "PLAY"]) }

    public func pause() {
        freezePosition()
        isPlayingNow = false
        onEvent?(.paused)
        sendMedia(["type": "PAUSE"])
    }

    public func seek(to time: TimeInterval) {
        positionSeconds = time
        sampledAt = clock()
        sendMedia(["type": "SEEK", "currentTime": time])
    }

    /// Stops the music and leaves the receiver app; the connection stays until [detach].
    public func stop() {
        isPlayingNow = false
        pendingLoad = nil
        currentSource = nil
        queued = nil
        wantedNext = nil
        handedOff = false
        if mediaSessionID != nil { sendMedia(["type": "STOP"]) }
        if let sessionID { sendReceiver(["type": "STOP", "sessionId": sessionID]) }
        mediaSessionID = nil
    }

    public func setRate(_ rate: Float) {
        self.rate = rate
        if mediaSessionID != nil { sendMedia(["type": "SET_PLAYBACK_RATE", "playbackRate": Double(rate)]) }
    }

    /// Lets go of the device.
    public func detach() {
        tickTask?.cancel()
        tickTask = nil
        isPlayingNow = false
        if let transportID { send(NS.connection, to: transportID, ["type": "CLOSE"]) }
        if connected { send(NS.connection, to: Self.platform, ["type": "CLOSE"]) }
        connected = false
        socket.close()
    }

    /// Once a second: the position, and now and then a heartbeat and a look at the status.
    func tick() {
        guard connected else { return }
        ticks += 1
        if isPlayingNow {
            onEvent?(.position(positionNow(), duration: duration))
        }
        if ticks % Self.heartbeatEvery == 0 {
            if unansweredPings >= Self.missedHeartbeats {
                didClose("the Cast device stopped answering")
                return
            }
            unansweredPings += 1
            send(NS.heartbeat, to: Self.platform, ["type": "PING"])
            if mediaSessionID != nil { sendMedia(["type": "GET_STATUS"]) }
        }
    }

    // MARK: Inside

    private func didConnect() {
        connected = true
        send(NS.connection, to: Self.platform, ["type": "CONNECT"])
        sendReceiver(["type": "GET_STATUS"])
    }

    private func didClose(_ reason: String?) {
        let wasConnected = connected
        connected = false
        transportID = nil
        sessionID = nil
        mediaSessionID = nil
        isPlayingNow = false
        setBuffering(false)
        guard wasConnected || reason != nil else { return }
        DiagnosticLog.shared.log("cast", "\(device.name): \(reason ?? "connection closed")")
        onEvent?(.failed(reason.map { "Couldn't reach \(device.name): \($0)" } ?? "The connection to \(device.name) closed."))
    }

    private func didReceive(_ data: Data) {
        for message in framer.add(data) {
            guard let object = try? JSONSerialization.jsonObject(with: Data(message.payload.utf8)) as? [String: Any] else { continue }
            handle(object, namespace: message.namespace, from: message.source)
        }
    }

    private func handle(_ object: [String: Any], namespace: String, from source: String) {
        let type = object["type"] as? String
        switch namespace {
        case NS.heartbeat:
            if type == "PING" { send(NS.heartbeat, to: source, ["type": "PONG"]) }
            if type == "PONG" { unansweredPings = 0 }
        case NS.connection:
            // The device closed our channel to the receiver app (someone else took over).
            if type == "CLOSE", source == transportID {
                transportID = nil
                sessionID = nil
                mediaSessionID = nil
                isPlayingNow = false
                onEvent?(.paused)
            }
        case NS.receiver:
            if type == "RECEIVER_STATUS", let status = object["status"] as? [String: Any] { receiverStatus(status) }
            if type == "LAUNCH_ERROR" {
                launching = false
                fail("\(device.name) couldn't start the music player.")
            }
        case NS.media:
            switch type {
            case "MEDIA_STATUS":
                for status in object["status"] as? [[String: Any]] ?? [] { mediaStatus(status) }
            case "LOAD_FAILED", "LOAD_CANCELLED":
                if type == "LOAD_FAILED" { fail("\(device.name) couldn't play this song.") }
            case "INVALID_REQUEST", "INVALID_PLAYER_STATE":
                DiagnosticLog.shared.log("cast", "\(device.name) refused a request: \(object["reason"] as? String ?? type ?? "?")")
            default:
                break
            }
        default:
            break
        }
    }

    private func receiverStatus(_ status: [String: Any]) {
        if let v = status["volume"] as? [String: Any], let level = (v["level"] as? NSNumber)?.floatValue, wantedVolume == nil {
            adoptingVolume = true
            volume = level
            adoptingVolume = false
        }
        let apps = status["applications"] as? [[String: Any]] ?? []
        guard let app = apps.first(where: { $0["appId"] as? String == Self.defaultMediaReceiver }),
              let transport = app["transportId"] as? String else {
            // Another app took the device over, or nothing runs yet.
            transportID = nil
            sessionID = nil
            mediaSessionID = nil
            if pendingLoad != nil { launchIfNeeded() }
            return
        }
        launching = false
        sessionID = app["sessionId"] as? String
        guard transport != transportID else { return }
        transportID = transport
        send(NS.connection, to: transport, ["type": "CONNECT"])
        if let pending = pendingLoad {
            pendingLoad = nil
            sendLoad(pending.source, startAt: pending.startAt, autoplay: pending.autoplay)
        } else {
            sendMedia(["type": "GET_STATUS"])
        }
    }

    private func launchIfNeeded() {
        guard connected, !launching, transportID == nil else { return }
        launching = true
        sendReceiver(["type": "LAUNCH", "appId": Self.defaultMediaReceiver])
    }

    private func mediaDict(_ source: AudioSource) -> [String: Any] {
        var metadata: [String: Any] = ["metadataType": 3, "title": source.info?.title ?? ""]
        if let artist = source.info?.artist { metadata["artist"] = artist }
        if let album = source.info?.album { metadata["albumName"] = album }
        if let art = source.info?.artworkURL?.absoluteString { metadata["images"] = [["url": art]] }
        var media: [String: Any] = ["contentId": source.url.absoluteString, "streamType": "BUFFERED",
                                    "contentType": source.info?.mimeType ?? "audio/flac", "metadata": metadata]
        if let ms = source.info?.durationMs { media["duration"] = Double(ms) / 1000 }
        return media
    }

    private func sendLoad(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) {
        guard let transportID else { return }
        awaitingLoad = true
        currentItemID = nil
        var message: [String: Any] = ["type": "LOAD", "media": mediaDict(source), "autoplay": autoplay, "currentTime": startAt]
        if let sessionID { message["sessionId"] = sessionID }
        if rate != 1 { message["playbackRate"] = Double(rate) }
        loadRequestID = send(NS.media, to: transportID, message)
    }

    private func queueNextIfNeeded() {
        guard let next = wantedNext, queued == nil, mediaSessionID != nil, !awaitingLoad else { return }
        queued = (next, nil)
        sendMedia(["type": "QUEUE_INSERT", "items": [["media": mediaDict(next), "autoplay": true, "preloadTime": 20]]])
    }

    private func mediaStatus(_ status: [String: Any]) {
        let requested = status["requestId"] as? Int
        // What the device says before our load has landed describes the song before it.
        if awaitingLoad, let requested, requested != loadRequestID, status["media"] == nil { return }
        if let id = status["mediaSessionId"] as? Int { mediaSessionID = id }
        let contentID = (status["media"] as? [String: Any])?["contentId"] as? String
        if awaitingLoad {
            if let contentID, contentID == currentSource?.url.absoluteString {
                awaitingLoad = false
            } else if status["playerState"] as? String != "IDLE" || contentID != nil {
                return
            }
        }
        let itemID = status["currentItemId"] as? Int
        // Note which queue item is the armed one.
        if var q = queued, q.itemID == nil, let items = status["items"] as? [[String: Any]] {
            for item in items where ((item["media"] as? [String: Any])?["contentId"] as? String) == q.source.url.absoluteString {
                if item["itemId"] as? Int != itemID || contentID != q.source.url.absoluteString {
                    q.itemID = item["itemId"] as? Int
                    queued = q
                }
            }
        }
        if let d = ((status["media"] as? [String: Any])?["duration"] as? NSNumber)?.doubleValue, d > 0 { duration = d }
        if let t = (status["currentTime"] as? NSNumber)?.doubleValue {
            positionSeconds = t
            sampledAt = clock()
        }
        // The device moved on to the queued song by itself.
        if let q = queued, let contentID, contentID == q.source.url.absoluteString, contentID != currentSource?.url.absoluteString {
            DiagnosticLog.shared.log("cast", "\(device.name) moved to the next song on its own")
            currentSource = q.source
            currentItemID = itemID
            queued = nil
            wantedNext = nil
            handedOff = true
            duration = q.source.info?.durationMs.map { TimeInterval($0) / 1000 }
            onEvent?(.ended)
            return
        }
        if currentItemID == nil { currentItemID = itemID }

        switch status["playerState"] as? String {
        case "PLAYING":
            setBuffering(false)
            if !isPlayingNow {
                isPlayingNow = true
                onEvent?(.started)
            }
        case "PAUSED":
            setBuffering(false)
            if isPlayingNow {
                freezePosition()
                isPlayingNow = false
                onEvent?(.paused)
            }
        case "BUFFERING":
            setBuffering(true)
        case "IDLE":
            let wasPlaying = isPlayingNow
            isPlayingNow = false
            setBuffering(false)
            switch status["idleReason"] as? String {
            case "FINISHED":
                mediaSessionID = nil
                queued = nil
                wantedNext = nil
                onEvent?(.ended)
            case "ERROR":
                fail("\(device.name) couldn't play this song.")
            case "CANCELLED", "INTERRUPTED":
                // Stopped from elsewhere (a phone's Cast controls, another sender).
                if wasPlaying { onEvent?(.paused) }
            default:
                break
            }
        default:
            break
        }
        queueNextIfNeeded()
    }

    private func positionNow() -> TimeInterval {
        guard isPlayingNow else { return positionSeconds }
        let t = positionSeconds + TimeInterval(max(clock() - sampledAt, 0)) / 1000 * Double(rate)
        if let duration, duration > 0 { return min(t, duration) }
        return t
    }

    private func freezePosition() {
        positionSeconds = positionNow()
        sampledAt = clock()
    }

    private func setBuffering(_ on: Bool) {
        guard on != isBuffering else { return }
        isBuffering = on
        onEvent?(.buffering(on))
    }

    private func fail(_ message: String) {
        isPlayingNow = false
        setBuffering(false)
        DiagnosticLog.shared.log("cast", message)
        onEvent?(.failed(message))
    }

    private func sendReceiver(_ message: [String: Any]) {
        send(NS.receiver, to: Self.platform, message)
    }

    private func sendMedia(_ message: [String: Any]) {
        guard let transportID, let mediaSessionID else { return }
        var m = message
        m["mediaSessionId"] = mediaSessionID
        send(NS.media, to: transportID, m)
    }

    @discardableResult
    private func send(_ namespace: String, to destination: String, _ message: [String: Any]) -> Int? {
        var m = message
        var id: Int?
        // Connection and heartbeat messages carry no request id.
        if namespace == NS.receiver || namespace == NS.media {
            requestID += 1
            m["requestId"] = requestID
            id = requestID
        }
        guard let body = try? JSONSerialization.data(withJSONObject: m) else { return nil }
        let frame = CastMessage(source: Self.sender, destination: destination, namespace: namespace,
                                payload: String(decoding: body, as: UTF8.self)).frame
        socket.send(frame)
        return id
    }
}
