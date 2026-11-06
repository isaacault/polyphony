import Foundation

/// GetPositionInfo's answer. [itemID] is TrackMetaData's DIDL item id, which the engine sets to its
/// own number for each song.
public struct DLNAReport: Sendable, Equatable {
    public var trackURI: String?
    public var positionMs: Int?
    public var durationMs: Int?
    public var itemID: String?
}

public struct DLNAMedia: Sendable, Equatable {
    public var currentURI: String?
    public var nextURI: String?
}

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

    /// Hands the speaker the song after this one, for a gapless change; speakers without it refuse.
    public func setNextURI(_ url: String, metadata: String) async throws {
        try await call("SetNextAVTransportURI", [("NextURI", url), ("NextURIMetaData", metadata)])
    }

    public func report() async throws -> DLNAReport {
        let out = try await call("GetPositionInfo", [])
        return DLNAReport(trackURI: out["TrackURI"].flatMap { $0.isEmpty ? nil : $0 },
                          positionMs: UPnPTime.parse(out["RelTime"]),
                          durationMs: UPnPTime.parse(out["TrackDuration"]).flatMap { $0 > 0 ? $0 : nil },
                          itemID: DIDL.itemID(out["TrackMetaData"]))
    }

    /// What the speaker holds as current and as queued next (nil once used).
    public func mediaInfo() async throws -> DLNAMedia {
        let out = try await call("GetMediaInfo", [])
        return DLNAMedia(currentURI: out["CurrentURI"].flatMap { $0.isEmpty ? nil : $0 },
                         nextURI: out["NextURI"].flatMap { $0.isEmpty || $0 == "NOT_IMPLEMENTED" ? nil : $0 })
    }

    public func setVolume(_ percent: Int) async throws {
        try await call("SetVolume", [("Channel", "Master"), ("DesiredVolume", "\(min(max(percent, 0), 100))")], rendering: true)
    }

    /// The speaker's volume now (0–100), nil when it won't say.
    public func volume() async throws -> Int? {
        (try await call("GetVolume", [("Channel", "Master")], rendering: true)["CurrentVolume"]).flatMap { Int($0) }
    }
}

/// PolyphonyKit's AudioEngine on a DLNA speaker, the Android app's DlnaPlaybackEngine in Swift. The
/// speaker fetches the audio itself (the URL must be one it can reach: plain HTTP on the LAN), and the
/// engine polls its state and position.
///
/// Gapless: the next song is handed over with SetNextAVTransportURI, and the speaker moves on by
/// itself. Not every speaker says so (the Yamaha RX-V483 freezes its position report at the old song's
/// end), so a hand-off is followed by whichever signal shows it first: the track URI, TrackMetaData's
/// item id, GetMediaInfo in a song's last seconds, the position jumping back, passing the end or
/// freezing near it, the duration becoming the next song's, and once the report is known to be frozen,
/// the engine's own clock. The engine then says `.ended`; the controller's load of that same song
/// leaves it playing. A speaker that refuses SetNext gets each song when the last one stops.
@MainActor
public final class DLNAEngine: AudioEngine {
    public var onEvent: ((AudioEvent) -> Void)?
    /// The speaker started or stopped playing: the app keeps the Mac awake meanwhile, or the polls
    /// that follow the songs would stop with it.
    public var onPlayingChanged: ((Bool) -> Void)?

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

    private struct Song {
        let uid: Int
        let url: String
        let durationMs: Int
        let source: AudioSource
    }

    private let control: DLNAControl
    private let pollInterval: Duration
    private let clock: @MainActor () -> Int
    private var pollTask: Task<Void, Never>?

    private var uidCounter = 0
    private var loaded: Song?
    private var armed: Song?
    private var wantedNext: AudioSource?
    /// The speaker moved on to the armed song: the controller's load of that song leaves it playing.
    private var handedOff = false

    /// Commands run one at a time, in order; a poll waits its turn (the Android engine's mutex).
    private var lastCommand: Task<Void, Never>?
    private var commandsPending = 0

    private var wantsPlay = false
    private var isPlayingNow = false {
        didSet { if isPlayingNow != oldValue { onPlayingChanged?(isPlayingNow) } }
    }
    private var isBuffering = false
    private var userStopped = false
    /// A speaker without Pause was stopped instead; Play cues the song again at its place.
    private var pausedByStop = false
    private var pendingSeekMs = 0
    private var lastCommandAt = 0
    private var playStartedAt = 0
    private var pollFailures = 0

    private var setNextSupported: Bool?
    private var mediaInfoSupported: Bool?
    /// The armed song's uid once GetMediaInfo has shown it queued.
    private var mediaInfoShowedArmed: Int?
    private var armedAt = 0

    private var positionMs = 0
    private var sampledAt = 0
    private var durationMs = 0

    /// When the last poll came, and how long the speaker has said PLAYING since the song loaded.
    private var lastPollAt = 0
    private var playedMs = 0
    private var lastPollPlaying = false

    /// The position last reported while PLAYING, and since when it hasn't moved.
    private var lastReportedMs: Int?
    private var reportUnchangedSince = 0
    /// The frozen position a speaker keeps reporting after a hand-off: ignored until it changes.
    private var staleReportedMs: Int?

    private static let maxPollFailures = 5
    private static let settleMs = 1_800
    private static let endToleranceMs = 3_500
    private static let regressionMs = 5_000
    private static let nearEndWindowMs = 20_000
    private static let durationMatchMs = 2_000
    /// Reported positions this close count as not moving (speakers round to whole seconds).
    private static let frozenJitterMs = 500
    private static let frozenHandoffMs = 4_000
    /// With a frozen report, the clock this far past a song's end means the next one is playing.
    private static let clockGraceMs = 2_500
    /// GetMediaInfo is asked only this close to a song's end, while a next song is armed.
    private static let mediaInfoWindowMs = 5_000
    private static let unknownDurationMinPlayMs = 5_000
    private static let reasonFrozen = "position frozen at the end"
    private static let reasonClock = "clock past the end"

    public init(control: DLNAControl, pollInterval: Duration = .seconds(1), autoPoll: Bool = true,
                clock: @escaping @MainActor () -> Int = { Int(Date().timeIntervalSince1970 * 1000) }) {
        self.control = control
        self.pollInterval = pollInterval
        self.clock = clock
        if autoPoll {
            startPolling()
            Task { [weak self] in await self?.adoptSpeakerVolume() }
        }
    }

    // MARK: Commands

    public func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) {
        let url = source.url.absoluteString
        if handedOff, let loaded, loaded.url == url, startAt == 0 {
            // The speaker already moved on to this song.
            handedOff = false
            if autoplay { onEvent?(.started) } else { pause() }
            return
        }
        if handedOff { DiagnosticLog.shared.log("dlna", "the next song wasn't the one armed: loading it") }
        handedOff = false
        let hadSong = loaded != nil
        uidCounter += 1
        let uid = uidCounter
        let song = Song(uid: uid, url: url, durationMs: source.info?.durationMs ?? 0, source: source)
        let startMs = Int(startAt * 1000)
        let now = clock()
        loaded = song
        armed = nil
        wantedNext = nil
        pausedByStop = false
        userStopped = false
        wantsPlay = autoplay
        isPlayingNow = false
        pendingSeekMs = 0
        pollFailures = 0
        playedMs = 0
        lastPollAt = 0
        lastPollPlaying = false
        lastReportedMs = nil
        staleReportedMs = nil
        positionMs = startMs
        sampledAt = now
        durationMs = song.durationMs
        lastCommandAt = now
        setBuffering(true)
        let metadata = Self.metadata(for: source, uid: uid)
        command { [weak self] in
            guard let self else { return }
            let control = self.control
            do {
                if hadSong {
                    try? await control.stop()
                    self.lastCommandAt = self.clock()
                }
                guard self.loaded?.uid == uid else { return }
                try await control.setURI(url, metadata: metadata)
                guard self.loaded?.uid == uid else { return }
                self.lastCommandAt = self.clock()
                if autoplay {
                    try await control.play()
                    if startMs > 0 { await self.seekWithRetry(startMs) }
                    self.markPlaying()
                } else {
                    self.pendingSeekMs = startMs
                    self.setBuffering(false)
                }
            } catch {
                self.fail("hand-off failed", error, url: url)
            }
        }
    }

    public func preload(_ next: AudioSource?) {
        wantedNext = next
        // The queue changed: what's armed may no longer be right. Arming again replaces it.
        if let armed, armed.url != next?.url.absoluteString { self.armed = nil }
    }

    public func play() {
        guard let song = loaded else { return }
        if pausedByStop {
            let at = TimeInterval(pendingSeekMs > 0 ? pendingSeekMs : positionMs) / 1000
            load(song.source, startAt: at, autoplay: true)
            return
        }
        wantsPlay = true
        command { [weak self] in
            guard let self else { return }
            do {
                try await self.control.play()
                if self.pendingSeekMs > 0 {
                    let target = self.pendingSeekMs
                    self.pendingSeekMs = 0
                    await self.seekWithRetry(target)
                }
                self.markPlaying()
            } catch {
                self.fail("the speaker refused Play", error, url: nil)
            }
        }
    }

    public func pause() {
        guard loaded != nil else { return }
        freezePosition()
        wantsPlay = false
        isPlayingNow = false
        onEvent?(.paused)
        command { [weak self] in
            guard let self else { return }
            do {
                do {
                    try await self.control.pause()
                } catch is DLNAError {
                    // A speaker without Pause (some AVRs): stop, and cue it again on Play.
                    try await self.control.stop()
                    self.pausedByStop = true
                }
                self.lastCommandAt = self.clock()
            } catch {
                self.fail("the speaker refused Pause", error, url: nil)
            }
        }
    }

    public func seek(to time: TimeInterval) {
        guard loaded != nil else { return }
        let target = Int(time * 1000)
        positionMs = target
        sampledAt = clock()
        lastCommandAt = sampledAt
        if pausedByStop {
            pendingSeekMs = target
            return
        }
        command { [weak self] in await self?.seekWithRetry(target) }
    }

    public func stop() {
        userStopped = true
        handedOff = false
        loaded = nil
        armed = nil
        wantedNext = nil
        wantsPlay = false
        isPlayingNow = false
        command { [weak self] in
            guard let self else { return }
            try? await self.control.stop()
            self.lastCommandAt = self.clock()
        }
    }

    /// Stops polling; the speaker keeps whatever it was doing.
    public func detach() {
        pollTask?.cancel()
        pollTask = nil
        isPlayingNow = false
    }

    // MARK: Inside

    private static func metadata(for source: AudioSource, uid: Int) -> String {
        let info = source.info
        return DIDL.metadata(url: source.url.absoluteString, mime: info?.mimeType ?? "audio/flac", title: info?.title ?? "",
                             artist: info?.artist, album: info?.album, artworkURL: info?.artworkURL?.absoluteString,
                             durationMs: info?.durationMs, itemID: String(uid))
    }

    private func command(_ body: @escaping @MainActor () async -> Void) {
        let previous = lastCommand
        commandsPending += 1
        lastCommand = Task { @MainActor [weak self] in
            await previous?.value
            await body()
            self?.commandsPending -= 1
        }
    }

    private func setBuffering(_ on: Bool) {
        guard on != isBuffering else { return }
        isBuffering = on
        onEvent?(.buffering(on))
    }

    private func markPlaying() {
        let now = clock()
        playStartedAt = now
        lastCommandAt = now
        // Played time counts from Play, not from the first poll, which may come late.
        lastPollAt = now
        lastPollPlaying = true
        sampledAt = now
        wantsPlay = true
        isPlayingNow = true
        setBuffering(false)
        onEvent?(.started)
    }

    private func seekWithRetry(_ ms: Int) async {
        do {
            try await control.seek(ms: ms)
        } catch {
            // Some speakers refuse Seek until they report PLAYING: once more shortly after.
            try? await Task.sleep(for: .milliseconds(600))
            try? await control.seek(ms: ms)
        }
        lastCommandAt = clock()
        positionMs = ms
        sampledAt = lastCommandAt
    }

    private func positionAt(_ now: Int) -> Int {
        guard isPlayingNow else { return positionMs }
        let extrapolated = positionMs + max(now - sampledAt, 0)
        return durationMs > 0 ? min(extrapolated, durationMs) : extrapolated
    }

    /// The engine's own idea of the position: not capped at the song's end.
    private func clockPosition(_ now: Int) -> Int {
        isPlayingNow ? positionMs + max(now - sampledAt, 0) : positionMs
    }

    private func freezePosition() {
        let now = clock()
        positionMs = positionAt(now)
        sampledAt = now
    }

    private func fail(_ what: String, _ error: Error?, url: String?) {
        loaded = nil
        armed = nil
        handedOff = false
        wantsPlay = false
        isPlayingNow = false
        setBuffering(false)
        // Into the log too, without the URL: it carries the library credential.
        DiagnosticLog.shared.log("dlna", "\(control.renderer.name): \(what): \(error.map(ErrorText.of) ?? "no answer")")
        onEvent?(.failed(Self.explain(what, error, url: url)))
    }

    private static func explain(_ what: String, _ error: Error?, url: String?) -> String {
        var message = error.map(ErrorText.of) ?? what
        if url?.lowercased().hasPrefix("https://") == true {
            message += " Speakers often can't play HTTPS links: set a plain-HTTP receiver address in Settings."
        }
        return message
    }

    private func startPolling() {
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.pollOnce()
                try? await Task.sleep(for: self?.pollInterval ?? .seconds(1))
            }
        }
    }

    /// One look at the speaker: its state, where it is, and whether it has moved on.
    func pollOnce() async {
        guard let current = loaded, commandsPending == 0 else { return }
        let state = try? await control.transportState()
        let report = try? await control.report()
        guard loaded?.uid == current.uid, commandsPending == 0 else { return }
        if state == nil && report == nil {
            pollFailures += 1
            if pollFailures >= Self.maxPollFailures { fail("the speaker stopped responding", nil, url: nil) }
            return
        }
        pollFailures = 0
        let now = clock()
        let sinceCommand = now - lastCommandAt
        let playing = state == "PLAYING"
        if playing && lastPollAt > 0 {
            let gap = max(now - lastPollAt, 0)
            // PLAYING at both ends: it played the whole gap, however late this poll came (a Mac that
            // dozed polls late). Otherwise it started somewhere in between.
            playedMs += lastPollPlaying ? gap : min(gap, Self.pollIntervalMs(pollInterval))
        }
        lastPollPlaying = playing
        lastPollAt = now

        if let p = report {
            let armedNow = armed
            let stale = isStale(p)
            let frozenFor = trackFrozen(p, state, now)
            var reason = armedNow.flatMap { handoffReason($0, current, p, now, sinceCommand, state, frozenFor, stale) }
            if let armedNow, reason == nil, playing, isPlayingNow, sinceCommand > Self.settleMs, durationMs > 0,
               clockPosition(now) >= durationMs - Self.mediaInfoWindowMs {
                reason = await mediaInfoReason(armedNow, current, now)
                guard loaded?.uid == current.uid, commandsPending == 0 else { return }
            }
            if let armedNow, let reason {
                advance(to: armedNow, p, now, reason, stale)
                return
            } else if stale {
                // Still the frozen report from before the hand-off: the engine's clock goes on.
            } else if armedNow == nil || playing {
                // While a hand-off may be under way, a STOPPED or TRANSITIONING sample mustn't
                // overwrite the duration and position it's checked against.
                if let d = p.durationMs, d > 0 { durationMs = d }
                if let position = p.positionMs, sinceCommand > Self.settleMs {
                    positionMs = position
                    sampledAt = now
                }
            }
        }

        switch state {
        case "PLAYING":
            setBuffering(false)
            if !wantsPlay && sinceCommand > Self.settleMs && !pausedByStop {
                // Resumed from the speaker's own controls.
                wantsPlay = true
                isPlayingNow = true
                sampledAt = now
                playStartedAt = now
                onEvent?(.started)
            }
        case "PAUSED_PLAYBACK":
            if wantsPlay && sinceCommand > Self.settleMs {
                freezePosition()
                wantsPlay = false
                isPlayingNow = false
                setBuffering(false)
                onEvent?(.paused)
            } else if !wantsPlay {
                setBuffering(false)
            }
        case "TRANSITIONING":
            setBuffering(true)
        case "STOPPED", "NO_MEDIA_PRESENT":
            if wantsPlay && isPlayingNow && sinceCommand > Self.settleMs && !userStopped {
                let position = positionAt(now)
                let nearEnd = durationMs > 0 ? position >= durationMs - Self.endToleranceMs
                                             : now - playStartedAt > Self.unknownDurationMinPlayMs
                if nearEnd {
                    let pastNext = playedArmedToo(current)
                    if pastNext, let armed {
                        DiagnosticLog.shared.log("dlna", "\(control.renderer.name) stopped after also playing the queued song unnoticed: skipping \(armed.source.info?.title ?? "it")")
                    }
                    loaded = nil
                    armed = nil
                    wantedNext = nil
                    wantsPlay = false
                    isPlayingNow = false
                    onEvent?(pastNext ? .endedPastNext : .ended)
                    return
                }
                // Stopped from the speaker itself (or one that can't hold its place): shown as paused.
                freezePosition()
                pausedByStop = true
                wantsPlay = false
                isPlayingNow = false
                onEvent?(.paused)
            }
        default:
            break
        }

        onEvent?(.position(TimeInterval(positionAt(now)) / 1000, duration: durationMs > 0 ? TimeInterval(durationMs) / 1000 : nil))
        await armNextIfNeeded(current)
    }

    private static func pollIntervalMs(_ d: Duration) -> Int {
        Int(d.components.seconds) * 1000 + Int(d.components.attoseconds / 1_000_000_000_000_000)
    }

    private func armNextIfNeeded(_ current: Song) async {
        guard setNextSupported != false, isPlayingNow, let next = wantedNext else { return }
        let url = next.url.absoluteString
        guard armed?.url != url else { return }
        uidCounter += 1
        let uid = uidCounter
        do {
            try await control.setNextURI(url, metadata: Self.metadata(for: next, uid: uid))
            guard loaded?.uid == current.uid, wantedNext?.url.absoluteString == url else { return }
            armed = Song(uid: uid, url: url, durationMs: next.info?.durationMs ?? 0, source: next)
            armedAt = clock()
            setNextSupported = true
        } catch {
            // It doesn't do SetNextAVTransportURI: each song starts when the last one stops.
            DiagnosticLog.shared.log("dlna", "\(control.renderer.name) refused SetNext: no gapless on it")
            setNextSupported = false
            armed = nil
        }
    }

    /// How long it has reported the same position while PLAYING (0 when moving or not playing).
    private func trackFrozen(_ p: DLNAReport, _ state: String?, _ now: Int) -> Int {
        guard state == "PLAYING", let position = p.positionMs else {
            lastReportedMs = nil
            return 0
        }
        guard let last = lastReportedMs, abs(position - last) <= Self.frozenJitterMs else {
            lastReportedMs = position
            reportUnchangedSince = now
            return 0
        }
        return now - reportUnchangedSince
    }

    private func isStale(_ p: DLNAReport) -> Bool {
        guard let stale = staleReportedMs else { return false }
        guard let position = p.positionMs else { return true }
        if abs(position - stale) <= Self.frozenJitterMs { return true }
        staleReportedMs = nil // live reports again
        return false
    }

    /// GetMediaInfo near the song's end: a speaker that hides a hand-off in GetPositionInfo may show
    /// it here, as the armed URI becoming current, or the queued next being used up.
    private func mediaInfoReason(_ armed: Song, _ current: Song, _ now: Int) async -> String? {
        guard mediaInfoSupported != false, now - armedAt > Self.settleMs else { return nil }
        guard let media = try? await control.mediaInfo() else {
            mediaInfoSupported = false
            return nil
        }
        mediaInfoSupported = true
        if let uri = media.currentURI, Self.sameURL(uri, armed.url), !Self.sameURL(uri, current.url) {
            return "GetMediaInfo current URI"
        }
        if let next = media.nextURI, Self.sameURL(next, armed.url) { mediaInfoShowedArmed = armed.uid }
        if media.nextURI == nil, mediaInfoShowedArmed == armed.uid, clockPosition(now) >= durationMs - Self.endToleranceMs {
            return "GetMediaInfo next URI taken"
        }
        return nil
    }

    /// Why the speaker seems to have moved on to the armed song by itself, or nil. See the type's
    /// comment. Once its report has frozen, position and duration describe the old song, so only the
    /// clock counts.
    private func handoffReason(_ armed: Song, _ current: Song, _ p: DLNAReport, _ now: Int, _ sinceCommand: Int,
                               _ state: String?, _ frozenFor: Int, _ stale: Bool) -> String? {
        if let uri = p.trackURI, Self.sameURL(uri, armed.url), !Self.sameURL(uri, current.url) { return "uri" }
        if let id = p.itemID, id == String(armed.uid), id != String(current.uid) { return "track metadata" }
        guard sinceCommand > Self.settleMs, state == "PLAYING", isPlayingNow else { return nil }
        if stale {
            return durationMs > 0 && clockPosition(now) >= durationMs + Self.clockGraceMs ? Self.reasonClock : nil
        }
        if let position = p.positionMs, durationMs > 0 {
            let expected = positionAt(now)
            if expected >= durationMs - Self.nearEndWindowMs && position + Self.regressionMs < expected { return "position jumped back" }
            if position > durationMs + Self.endToleranceMs { return "position past the end" }
            if position >= durationMs - Self.nearEndWindowMs && frozenFor >= Self.frozenHandoffMs { return Self.reasonFrozen }
        }
        if let reported = p.durationMs, reported > 0, durationMs > 0, armed.durationMs > 0,
           abs(reported - durationMs) > Self.durationMatchMs, abs(reported - armed.durationMs) <= Self.durationMatchMs {
            return "duration changed"
        }
        return nil
    }

    private func advance(to next: Song, _ p: DLNAReport, _ now: Int, _ reason: String, _ stale: Bool) {
        let frozen = reason == Self.reasonFrozen
        let position: Int
        if frozen {
            // The report stopped moving when the last song ended; the next one has played since.
            position = now - reportUnchangedSince
        } else if stale {
            position = max(clockPosition(now) - durationMs, 0)
        } else if let reported = p.positionMs {
            // One still counting from the last song's start.
            position = durationMs > 0 && reported > durationMs + Self.endToleranceMs ? reported - durationMs : reported
        } else {
            position = 0
        }
        let describesOldSong = frozen || stale
        let itemSeen = p.itemID == nil ? "none" : p.itemID == String(next.uid) ? "next" : p.itemID == loaded.map { String($0.uid) } ? "previous" : "other"
        DiagnosticLog.shared.log("dlna", "\(control.renderer.name) moved to the next song on its own (\(reason)) "
            + "[report frozen=\(describesOldSong), TrackMetaData item=\(itemSeen), GetMediaInfo=\(mediaInfoSupported.map(String.init) ?? "unasked")]")
        // Its reports stay pinned to the old song: ignored from here, and later hand-offs followed by
        // TrackMetaData, GetMediaInfo or the clock. SetNext stays armed (gapless).
        if frozen { staleReportedMs = p.positionMs }
        loaded = next
        armed = nil
        wantedNext = nil
        handedOff = true
        playStartedAt = now
        playedMs = position
        positionMs = position
        sampledAt = now
        durationMs = (describesOldSong ? nil : p.durationMs.flatMap { $0 > 0 ? $0 : nil }) ?? next.durationMs
        onEvent?(.ended)
    }

    /// It stopped at what looks like this song's end, but has said PLAYING for long enough to have gone
    /// through the armed one as well. The cut is halfway into the armed song: played time is only known
    /// as well as the polls sample it.
    private func playedArmedToo(_ current: Song) -> Bool {
        guard let armed, armed.durationMs > 0 else { return false }
        let currentDuration = current.durationMs > 0 ? current.durationMs : durationMs
        guard currentDuration > 0 else { return false }
        return playedMs >= currentDuration + armed.durationMs / 2
    }

    private static func sameURL(_ a: String, _ b: String) -> Bool {
        func norm(_ u: String) -> String {
            let t = u.trimmingCharacters(in: .whitespacesAndNewlines)
            var d = t.removingPercentEncoding ?? t
            while d.hasSuffix("/") { d.removeLast() }
            return d.lowercased()
        }
        return norm(a) == norm(b)
    }
}
