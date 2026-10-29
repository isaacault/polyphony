import Foundation

/// The SOAP calls to one renderer's AVTransport and RenderingControl (InstanceID 0, as all do).
public struct DLNAControl: Sendable {
    public let renderer: Renderer
    private let transport: HTTPTransport

    public init(renderer: Renderer, transport: HTTPTransport = URLSessionTransport()) {
        self.renderer = renderer
        self.transport = transport
    }

    @discardableResult
    func call(_ action: String, _ args: [(String, String)], rendering: Bool = false) async throws -> [String: String] {
        let type = rendering ? Renderer.renderingControlType : Renderer.avTransportType
        guard let url = rendering ? renderer.renderingControl : renderer.avTransport else {
            throw DLNAError(code: 0, message: "\(renderer.name) has no volume control")
        }
        let body = Data(SOAP.envelope(service: type, action: action, args: [("InstanceID", "0")] + args).utf8)
        let response = try await transport.send(HTTPRequest(method: "POST", url: url, headers: [
            "Content-Type": "text/xml; charset=\"utf-8\"", "SOAPACTION": "\"\(type)#\(action)\"",
        ], body: body))
        // A fault comes with HTTP 500 and a SOAP body: read the body either way.
        let out = try SOAP.parseResponse(response.body, action: action)
        guard (200..<300).contains(response.status) else {
            throw DLNAError(code: response.status, message: "\(renderer.name) refused \(action)")
        }
        return out
    }

    public func setURI(_ url: String, metadata: String) async throws {
        try await call("SetAVTransportURI", [("CurrentURI", url), ("CurrentURIMetaData", metadata)])
    }

    public func play() async throws { try await call("Play", [("Speed", "1")]) }
    public func pause() async throws { try await call("Pause", []) }
    public func stop() async throws { try await call("Stop", []) }

    public func seek(ms: Int) async throws {
        try await call("Seek", [("Unit", "REL_TIME"), ("Target", UPnPTime.format(ms: ms))])
    }

    /// PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT.
    public func transportState() async throws -> String {
        (try await call("GetTransportInfo", [])["CurrentTransportState"] ?? "").uppercased()
    }

    public func position() async throws -> (positionMs: Int?, durationMs: Int?) {
        let out = try await call("GetPositionInfo", [])
        return (UPnPTime.parse(out["RelTime"]), UPnPTime.parse(out["TrackDuration"]).flatMap { $0 > 0 ? $0 : nil })
    }

    public func setVolume(_ percent: Int) async throws {
        try await call("SetVolume", [("Channel", "Master"), ("DesiredVolume", "\(min(max(percent, 0), 100))")], rendering: true)
    }

    /// The speaker's volume now (0–100), nil when it won't say.
    public func volume() async throws -> Int? {
        (try await call("GetVolume", [("Channel", "Master")], rendering: true)["CurrentVolume"]).flatMap { Int($0) }
    }
}

/// PolyphonyKit's AudioEngine on a DLNA speaker: the speaker fetches the audio itself (the URL must
/// be one it can reach: plain HTTP on the LAN), and the engine polls its state and position. A song
/// "ends" when the speaker stops by itself after playing it.
@MainActor
public final class DLNAEngine: AudioEngine {
    public var onEvent: ((AudioEvent) -> Void)?
    /// Starts at the speaker's own volume (read as the engine connects) so the slider doesn't jump it.
    /// Changes go one at a time, the latest winning: a slider dragged sends a few, in order, not dozens.
    public var volume: Float = 0.5 {
        didSet {
            guard !adoptingVolume else { return }
            wantedVolume = Int((min(max(volume, 0), 1) * 100).rounded())
            guard volumeSender == nil else { return }
            volumeSender = Task { [weak self] in await self?.sendVolumes() }
        }
    }
    private var adoptingVolume = false
    private var wantedVolume: Int?
    private var sentVolume: Int?
    private var volumeSender: Task<Void, Never>?

    private func sendVolumes() async {
        while let wanted = wantedVolume, wanted != sentVolume {
            sentVolume = wanted
            try? await control.setVolume(wanted)
        }
        volumeSender = nil
    }

    /// Reads the speaker's volume into [volume] without sending it back.
    public func adoptSpeakerVolume() async {
        guard let percent = try? await control.volume() else { return }
        sentVolume = percent
        adoptingVolume = true
        volume = Float(percent) / 100
        adoptingVolume = false
    }

    public var renderer: Renderer { control.renderer }

    private let control: DLNAControl
    private let pollInterval: Duration
    private var pollTask: Task<Void, Never>?
    private var loadedURL: String?
    /// Seen playing since the last load: a stop after this is the song ending (or the speaker's own Stop).
    private var sawPlaying = false
    private var stoppedByUs = false
    private var lastState = ""
    private var lastPositionMs = 0
    private var durationMs: Int?

    public init(control: DLNAControl, pollInterval: Duration = .seconds(1), autoPoll: Bool = true) {
        self.control = control
        self.pollInterval = pollInterval
        if autoPoll {
            startPolling()
            Task { [weak self] in await self?.adoptSpeakerVolume() }
        }
    }

    public func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) {
        let url = source.url.absoluteString
        let info = source.info
        let metadata = DIDL.metadata(url: url, mime: info?.mimeType ?? "audio/flac", title: info?.title ?? "",
                                     artist: info?.artist, album: info?.album, artworkURL: info?.artworkURL?.absoluteString,
                                     durationMs: info?.durationMs)
        loadedURL = url
        sawPlaying = false
        stoppedByUs = false
        lastPositionMs = Int(startAt * 1000)
        durationMs = info?.durationMs
        onEvent?(.buffering(true))
        let control = self.control
        Task {
            do {
                try await control.setURI(url, metadata: metadata)
                if autoplay { try await control.play() }
                if startAt > 0 { try? await control.seek(ms: Int(startAt * 1000)) }
            } catch {
                DiagnosticLog.shared.log("dlna", "\(control.renderer.name): hand-off of \(url) failed: \(error)")
                self.onEvent?(.failed(Self.explain(error, url: url)))
            }
        }
    }

    /// No gapless hand-off to speakers: the next song loads when this one ends.
    public func preload(_ next: AudioSource?) {}

    public func play() { send { try await $0.play() } }
    public func pause() { send { try await $0.pause() } }

    public func seek(to time: TimeInterval) {
        lastPositionMs = Int(time * 1000)
        send { try await $0.seek(ms: Int(time * 1000)) }
    }

    public func stop() {
        stoppedByUs = true
        loadedURL = nil
        send { try await $0.stop() }
    }

    /// Stops polling; the speaker keeps whatever it was doing.
    public func detach() {
        pollTask?.cancel()
        pollTask = nil
    }

    private func send(_ action: @escaping @Sendable (DLNAControl) async throws -> Void) {
        let control = self.control
        Task {
            do { try await action(control) } catch { self.onEvent?(.failed(Self.explain(error, url: nil))) }
        }
    }

    private func startPolling() {
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.pollOnce()
                try? await Task.sleep(for: self?.pollInterval ?? .seconds(1))
            }
        }
    }

    /// One look at the speaker: its state, then where it is in the song.
    func pollOnce() async {
        guard loadedURL != nil else { return }
        guard let state = try? await control.transportState() else { return }
        if let reading = try? await control.position() {
            if let duration = reading.durationMs { durationMs = duration }
            if let position = reading.positionMs {
                lastPositionMs = position
                onEvent?(.position(TimeInterval(position) / 1000, duration: durationMs.map { TimeInterval($0) / 1000 }))
            }
        }
        defer { lastState = state }
        switch state {
        case "PLAYING":
            if lastState != "PLAYING" {
                sawPlaying = true
                onEvent?(.buffering(false))
                onEvent?(.started)
            }
        case "PAUSED_PLAYBACK":
            if lastState != "PAUSED_PLAYBACK" { onEvent?(.paused) }
        case "STOPPED", "NO_MEDIA_PRESENT":
            guard sawPlaying, !stoppedByUs, lastState != state else { return }
            sawPlaying = false
            loadedURL = nil
            onEvent?(.ended)
        default:
            break
        }
    }

    private static func explain(_ error: Error, url: String?) -> String {
        var message = (error as? CustomStringConvertible)?.description ?? "The speaker didn't answer."
        if url?.lowercased().hasPrefix("https://") == true {
            message += " Speakers often can't play HTTPS links: set a plain-HTTP receiver address in Settings."
        }
        return message
    }
}
