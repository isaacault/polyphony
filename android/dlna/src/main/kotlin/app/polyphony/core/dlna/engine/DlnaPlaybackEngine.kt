package app.polyphony.core.dlna.engine

import app.polyphony.core.dlna.service.DidlLite
import app.polyphony.core.dlna.service.TransportState
import app.polyphony.core.dlna.soap.DlnaException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.net.URLDecoder
import kotlin.random.Random

/**
 * Pure-Kotlin playback state machine for a UPnP renderer. Generic over the queue item type so the
 * logic is testable without Android; the Media3 adapter uses `T = MediaItem`.
 *
 * All mutations run under one mutex inside [scope]; commands return the [Job] so callers can await
 * completion. A polling loop samples GetPositionInfo/GetTransportInfo every [pollIntervalMs] while a
 * track is loaded, handles end-of-track → next, gapless hand-off via SetNextAVTransportURI (when the
 * renderer accepts it), and mirrors play/pause done on the device itself.
 */
class DlnaPlaybackEngine<T : Any>(
    private val transport: DlnaTransport,
    private val resolver: suspend (T) -> ResolvedStream?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 1_000L,
    private val commandTimeoutMs: Long = 10_000L,
    private val random: Random = Random.Default,
    /** One line per notable renderer event (hand-offs, skipped tracks); the owner routes it to the log. */
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(EngineState<T>())
    val state: StateFlow<EngineState<T>> = _state.asStateFlow()

    private val mutex = Mutex()
    private var uidCounter = 0L
    private var prepared = false
    private var userStopped = false
    private var pausedByStop = false
    private var pendingSeekMs = 0L
    private var lastCommandAt = 0L
    private var playStartedAt = 0L
    private var pollJob: Job? = null
    private var consecutivePollFailures = 0
    private var setNextSupported: Boolean? = null
    private var mediaInfoSupported: Boolean? = null
    /** The armed track's uid once GetMediaInfo has shown it as the queued NextURI. */
    private var mediaInfoShowedArmed: Long? = null
    /** When the current armed next track was handed to the renderer. */
    private var armedAt = 0L
    private var loaded: LoadedTrack? = null
    private var armedNext: LoadedTrack? = null
    private val resolvedCache = HashMap<Long, ResolvedStream>()

    /** Wall time of the previous poll, and how long the renderer has reported PLAYING since the current item was loaded (pauses excluded). */
    private var lastPollAt = 0L
    private var playedMsSinceLoad = 0L
    private var lastPollPlaying = false

    /** The position the renderer last reported while PLAYING, and since when it has not moved. */
    private var lastReportedPositionMs: Long? = null
    private var reportUnchangedSinceMs = 0L

    /**
     * Until then, a reported position past the first few seconds is the previous track's: the Yamaha
     * RX-V483 names the next track a few seconds before the hand-off, its clock still counting the
     * old one, then starts it from 0.
     */
    private var handoffPositionUntil = 0L
    /** The last reason a renderer's report couldn't be read, logged once each. */
    private var lastReportError: String? = null

    /**
     * The frozen position a renderer kept reporting after a gapless hand-off (the Yamaha RX-V483
     * does this). Reports of it are ignored, and the engine's own clock is used, until it changes.
     */
    private var staleReportedPositionMs: Long? = null

    private data class LoadedTrack(val index: Int, val uid: Long, val url: String)

    /** What the renderer said last, for logging only when it changes (see [logReport]). */
    private var lastReportLine: String? = null

    // ---- public commands -------------------------------------------------------------------------

    fun setMediaItems(items: List<T>, startIndex: Int = 0, startPositionMs: Long = 0L): Job = command {
        stopRendererQuietly()
        val engineItems = items.map { EngineItem(nextUid(), it) }
        val index = if (engineItems.isEmpty()) -1 else startIndex.coerceIn(0, engineItems.lastIndex)
        resolvedCache.clear()
        loaded = null
        armedNext = null
        pendingSeekMs = startPositionMs.coerceAtLeast(0)
        _state.update {
            it.copy(
                items = engineItems,
                currentIndex = index,
                positionMs = pendingSeekMs,
                positionSampledAtMs = clock(),
                durationMs = -1,
                playbackState = when {
                    engineItems.isEmpty() -> if (prepared) EnginePlaybackState.ENDED else EnginePlaybackState.IDLE
                    prepared -> EnginePlaybackState.BUFFERING
                    else -> EnginePlaybackState.IDLE
                },
                shuffleOrder = shuffleOrderFor(engineItems.size, index, it.shuffleEnabled),
                error = null,
                discontinuityCounter = it.discontinuityCounter + 1,
            )
        }
        if (prepared && index >= 0) loadCurrent(pendingSeekMs, play = _state.value.playWhenReady)
    }

    fun prepare(): Job = command {
        prepared = true
        userStopped = false
        _state.update { it.copy(error = null) }
        val s = _state.value
        if (s.items.isEmpty()) {
            _state.update { it.copy(playbackState = EnginePlaybackState.ENDED) }
        } else if (loaded == null) {
            loadCurrent(pendingSeekMs.takeIf { it > 0 } ?: s.positionMs, play = s.playWhenReady)
        }
    }

    fun setPlayWhenReady(playWhenReady: Boolean): Job = command {
        val s = _state.value
        if (s.playWhenReady == playWhenReady && (loaded != null || !playWhenReady)) return@command
        freezePosition()
        _state.update { it.copy(playWhenReady = playWhenReady) }
        if (!prepared) return@command
        if (s.playbackState == EnginePlaybackState.ENDED && playWhenReady && s.items.isNotEmpty()) {
            // Restart from the top, like a user pressing play after the queue finished.
            val first = if (s.shuffleEnabled && s.shuffleOrder.isNotEmpty()) s.shuffleOrder.first() else 0
            _state.update { it.copy(currentIndex = first) }
            loadCurrent(0, play = true)
            return@command
        }
        val current = loaded
        if (current == null) {
            if (playWhenReady && s.items.isNotEmpty()) loadCurrent(_state.value.positionMs, play = true)
            return@command
        }
        try {
            if (playWhenReady) {
                if (pausedByStop) {
                    pausedByStop = false
                    loadCurrent(_state.value.positionMs, play = true)
                } else {
                    tx { transport.play() }
                    if (pendingSeekMs > 0) {
                        val target = pendingSeekMs
                        pendingSeekMs = 0
                        seekWithRetry(target)
                    }
                    markPlaying()
                }
            } else {
                try {
                    tx { transport.pause() }
                } catch (e: DlnaException) {
                    // Renderers without Pause (some AVRs): stop and re-cue on resume.
                    tx { transport.stop() }
                    pausedByStop = true
                }
                _state.update { it.copy(playbackState = EnginePlaybackState.READY, isLoading = false) }
            }
            lastCommandAt = clock()
        } catch (t: Throwable) {
            fail("Renderer rejected ${if (playWhenReady) "play" else "pause"}", t)
        }
    }

    fun seekTo(index: Int, positionMs: Long): Job = command {
        val s = _state.value
        if (index !in s.items.indices) return@command
        val target = positionMs.coerceAtLeast(0)
        if (index != s.currentIndex || loaded == null || loaded?.index != index) {
            _state.update { it.copy(currentIndex = index, positionMs = target, positionSampledAtMs = clock(), discontinuityCounter = it.discontinuityCounter + 1) }
            userStopped = false
            if (prepared) loadCurrent(target, play = s.playWhenReady) else pendingSeekMs = target
            return@command
        }
        _state.update { it.copy(positionMs = target, positionSampledAtMs = clock(), discontinuityCounter = it.discontinuityCounter + 1) }
        if (s.playbackState == EnginePlaybackState.ENDED) {
            loadCurrent(target, play = s.playWhenReady)
            return@command
        }
        if (s.isPlayingNow || s.playbackState == EnginePlaybackState.READY) {
            if (pausedByStop) {
                pendingSeekMs = target
            } else {
                seekWithRetry(target)
            }
        } else {
            pendingSeekMs = target
        }
    }

    fun next(): Job = command {
        val idx = _state.value.nextIndex() ?: return@command
        jumpTo(idx)
    }

    fun previous(): Job = command {
        val idx = _state.value.previousIndex() ?: return@command
        jumpTo(idx)
    }

    fun stop(): Job = command {
        userStopped = true
        prepared = false
        freezePosition()
        stopPolling()
        stopRendererQuietly()
        loaded = null
        armedNext = null
        _state.update { it.copy(playbackState = EnginePlaybackState.IDLE, isLoading = false) }
    }

    fun release(): Job = command {
        stopPolling()
        if (loaded != null) stopRendererQuietly()
        loaded = null
        armedNext = null
        prepared = false
        _state.update { it.copy(released = true, playbackState = EnginePlaybackState.IDLE, isLoading = false) }
    }

    fun addMediaItems(index: Int, items: List<T>): Job = command {
        if (items.isEmpty()) return@command
        val s = _state.value
        val at = index.coerceIn(0, s.items.size)
        val newItems = items.map { EngineItem(nextUid(), it) }
        val list = s.items.toMutableList().apply { addAll(at, newItems) }
        val newCurrent = when {
            s.currentIndex < 0 -> 0
            at <= s.currentIndex -> s.currentIndex + newItems.size
            else -> s.currentIndex
        }
        _state.update {
            it.copy(
                items = list,
                currentIndex = newCurrent,
                shuffleOrder = shuffleOrderFor(list.size, newCurrent, it.shuffleEnabled, insertedRange = at until at + newItems.size, previous = it.shuffleOrder, previousCurrent = s.currentIndex),
                playbackState = if (it.playbackState == EnginePlaybackState.ENDED && s.items.isEmpty()) EnginePlaybackState.BUFFERING else it.playbackState,
            )
        }
        remapLoaded(s.currentIndex, newCurrent)
        invalidateArmedNext()
        if (prepared && loaded == null && _state.value.items.isNotEmpty()) {
            loadCurrent(0, play = _state.value.playWhenReady)
        }
    }

    fun removeMediaItems(fromIndex: Int, toIndex: Int): Job = command {
        val s = _state.value
        val from = fromIndex.coerceIn(0, s.items.size)
        val to = toIndex.coerceIn(from, s.items.size)
        if (from == to) return@command
        val list = s.items.toMutableList().apply { subList(from, to).clear() }
        val currentRemoved = s.currentIndex in from until to
        val newCurrent = when {
            list.isEmpty() -> -1
            currentRemoved -> from.coerceAtMost(list.lastIndex)
            s.currentIndex >= to -> s.currentIndex - (to - from)
            else -> s.currentIndex
        }
        _state.update {
            it.copy(
                items = list,
                currentIndex = newCurrent,
                shuffleOrder = shuffleOrderFor(list.size, newCurrent, it.shuffleEnabled),
                playbackState = if (list.isEmpty()) (if (prepared) EnginePlaybackState.ENDED else EnginePlaybackState.IDLE) else it.playbackState,
                positionMs = if (currentRemoved) 0 else it.positionMs,
                discontinuityCounter = if (currentRemoved) it.discontinuityCounter + 1 else it.discontinuityCounter,
            )
        }
        if (currentRemoved) {
            loaded = null
            armedNext = null
            if (list.isEmpty()) {
                stopPolling()
                stopRendererQuietly()
            } else if (prepared) {
                loadCurrent(0, play = s.playWhenReady)
            }
        } else {
            remapLoaded(s.currentIndex, newCurrent)
            invalidateArmedNext()
        }
    }

    fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): Job = command {
        val s = _state.value
        val from = fromIndex.coerceIn(0, s.items.size)
        val to = toIndex.coerceIn(from, s.items.size)
        if (from == to) return@command
        val moving = s.items.subList(from, to).toList()
        val remaining = s.items.toMutableList().apply { subList(from, to).clear() }
        val insertAt = newIndex.coerceIn(0, remaining.size)
        remaining.addAll(insertAt, moving)
        val currentUid = s.currentItem?.uid
        val newCurrent = remaining.indexOfFirst { it.uid == currentUid }
        _state.update { it.copy(items = remaining, currentIndex = newCurrent, shuffleOrder = shuffleOrderFor(remaining.size, newCurrent, it.shuffleEnabled)) }
        remapLoaded(s.currentIndex, newCurrent)
        invalidateArmedNext()
    }

    fun replaceMediaItems(fromIndex: Int, toIndex: Int, items: List<T>): Job = command {
        val s = _state.value
        val from = fromIndex.coerceIn(0, s.items.size)
        val to = toIndex.coerceIn(from, s.items.size)
        val newItems = items.map { EngineItem(nextUid(), it) }
        val list = s.items.toMutableList().apply {
            subList(from, to).clear()
            addAll(from, newItems)
        }
        val currentReplaced = s.currentIndex in from until to
        val newCurrent = when {
            list.isEmpty() -> -1
            currentReplaced -> from.coerceAtMost(list.lastIndex)
            s.currentIndex >= to -> s.currentIndex - (to - from) + newItems.size
            else -> s.currentIndex
        }
        _state.update {
            it.copy(
                items = list,
                currentIndex = newCurrent,
                shuffleOrder = shuffleOrderFor(list.size, newCurrent, it.shuffleEnabled),
                playbackState = if (list.isEmpty()) (if (prepared) EnginePlaybackState.ENDED else EnginePlaybackState.IDLE) else it.playbackState,
                discontinuityCounter = if (currentReplaced) it.discontinuityCounter + 1 else it.discontinuityCounter,
            )
        }
        if (currentReplaced) {
            loaded = null
            armedNext = null
            if (list.isEmpty()) {
                stopPolling(); stopRendererQuietly()
            } else if (prepared) {
                loadCurrent(0, play = s.playWhenReady)
            }
        } else {
            remapLoaded(s.currentIndex, newCurrent)
            invalidateArmedNext()
        }
    }

    fun setRepeatMode(mode: EngineRepeatMode): Job = command {
        _state.update { it.copy(repeatMode = mode) }
        invalidateArmedNext()
    }

    fun setShuffleModeEnabled(enabled: Boolean): Job = command {
        _state.update { it.copy(shuffleEnabled = enabled, shuffleOrder = shuffleOrderFor(it.items.size, it.currentIndex, enabled)) }
        invalidateArmedNext()
    }

    fun setVolume(percent: Int): Job = command {
        val v = percent.coerceIn(0, 100)
        try {
            tx { transport.setVolume(v) }
            _state.update { it.copy(volumePercent = v, muted = false) }
        } catch (t: Throwable) {
            // Volume failures are not fatal to playback.
        }
    }

    fun setMuted(muted: Boolean): Job = command {
        try {
            tx { transport.setMute(muted) }
            _state.update { it.copy(muted = muted) }
        } catch (_: Throwable) {
        }
    }

    /** Seed the volume shown before the first SetVolume (read via GetVolume on connect). */
    fun setInitialVolume(percent: Int?) {
        if (percent != null) _state.update { it.copy(volumePercent = percent.coerceIn(0, 100)) }
    }

    // ---- internals -------------------------------------------------------------------------------

    private fun command(block: suspend () -> Unit): Job = scope.launch {
        mutex.withLock {
            if (_state.value.released) return@withLock
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(t.message ?: "Renderer error", t)
            }
        }
    }

    private suspend fun <R> tx(block: suspend () -> R): R = withTimeout(commandTimeoutMs) { block() }

    private fun nextUid(): Long = ++uidCounter

    private suspend fun resolve(item: EngineItem<T>): ResolvedStream? =
        resolvedCache[item.uid] ?: resolver(item.item)?.also { resolvedCache[item.uid] = it }

    private suspend fun jumpTo(index: Int) {
        _state.update { it.copy(currentIndex = index, positionMs = 0, positionSampledAtMs = clock(), discontinuityCounter = it.discontinuityCounter + 1) }
        userStopped = false
        if (prepared) loadCurrent(0, play = _state.value.playWhenReady) else pendingSeekMs = 0
    }

    private suspend fun loadCurrent(startPositionMs: Long, play: Boolean) {
        val s = _state.value
        val item = s.currentItem ?: return
        armedNext = null
        pausedByStop = false
        playedMsSinceLoad = 0L
        lastPollAt = 0L
        lastPollPlaying = false
        lastReportedPositionMs = null
        staleReportedPositionMs = null
        _state.update { it.copy(playbackState = EnginePlaybackState.BUFFERING, isLoading = true, error = null, durationMs = -1) }
        var url: String? = null
        try {
            val resolved = resolve(item) ?: throw DlnaException(716, "No playable stream for this track")
            url = resolved.url
            val metadata = DidlLite.build(resolved, itemId = item.uid.toString())
            if (loaded != null) stopRendererQuietly()
            tx { transport.setUri(resolved.url, metadata) }
            loaded = LoadedTrack(s.currentIndex, item.uid, resolved.url)
            lastCommandAt = clock()
            _state.update { it.copy(durationMs = resolved.durationMs ?: -1, positionMs = startPositionMs, positionSampledAtMs = clock()) }
            if (play) {
                tx { transport.play() }
                if (startPositionMs > 0) seekWithRetry(startPositionMs)
                pendingSeekMs = 0
                markPlaying()
            } else {
                pendingSeekMs = startPositionMs
                _state.update { it.copy(playbackState = EnginePlaybackState.READY, isLoading = false) }
            }
            consecutivePollFailures = 0
            startPolling()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            fail(withHttpsHint("Could not start playback on renderer", url), t)
        }
    }

    private fun markPlaying() {
        val now = clock()
        playStartedAt = now
        lastCommandAt = now
        // Count played time from the Play command, not from the first poll, which may come late.
        lastPollAt = now
        lastPollPlaying = true
        _state.update { it.copy(playbackState = EnginePlaybackState.READY, isLoading = false, playWhenReady = true, positionSampledAtMs = now, error = null) }
    }

    private suspend fun seekWithRetry(positionMs: Long) {
        try {
            tx { transport.seek(positionMs) }
        } catch (e: DlnaException) {
            // Some renderers refuse Seek until they report PLAYING; try once more shortly after.
            delay(600)
            runCatching { tx { transport.seek(positionMs) } }
        }
        lastCommandAt = clock()
        _state.update { it.copy(positionMs = positionMs, positionSampledAtMs = clock()) }
    }

    private fun freezePosition() {
        val now = clock()
        _state.update { it.copy(positionMs = it.positionAt(now), positionSampledAtMs = now) }
    }

    private fun fail(message: String, cause: Throwable?) {
        val code = (cause as? DlnaException)?.code ?: 0
        val detail = when (cause) {
            is TimeoutCancellationException -> "$message (renderer timed out)"
            is DlnaException -> "$message: ${cause.message}"
            else -> message
        }
        stopPolling()
        loaded = null
        armedNext = null
        // Into the log too (never the URL: it carries the library credential), so diagnostics show it.
        log("renderer failed: $detail" + (if (code != 0) " (UPnP error $code)" else ""))
        _state.update { it.copy(playbackState = EnginePlaybackState.IDLE, isLoading = false, error = EngineError(detail, code, cause)) }
    }

    private suspend fun stopRendererQuietly() {
        runCatching { tx { transport.stop() } }
        lastCommandAt = clock()
    }

    private fun remapLoaded(oldIndex: Int, newIndex: Int) {
        val l = loaded ?: return
        if (l.index == oldIndex && newIndex >= 0) loaded = l.copy(index = newIndex)
    }

    /** Playlist or mode changed: the pre-armed next track may no longer be right. */
    private fun invalidateArmedNext() {
        val armed = armedNext ?: return
        val wanted = _state.value.autoAdvanceIndex()
        val wantedUid = wanted?.let { _state.value.items.getOrNull(it)?.uid }
        if (wantedUid != armed.uid) {
            // The renderer still holds it as NextURI: it plays it unless another is armed first.
            log("disarmed item ${armed.uid}: the queue now wants ${wanted ?: "nothing"} next")
            armedNext = null
        } else if (wanted != armed.index) {
            armedNext = armed.copy(index = wanted)
        }
    }

    /**
     * One line whenever the renderer's report changes in a way that matters (state, which song by
     * URI or item id, the duration, or the position going backwards), so diagnostics show what it
     * said around a hand-off. Never the URL, which carries the library credential: the song's id.
     */
    private fun logReport(state: TransportState?, p: TransportPosition, current: LoadedTrack) {
        val uri = p.trackUri?.let(::streamId) ?: "none"
        val which = when {
            p.trackUri == null -> ""
            sameUrl(p.trackUri, current.url) -> " (current)"
            armedNext?.let { sameUrl(p.trackUri, it.url) } == true -> " (armed)"
            else -> " (other)"
        }
        val line = "$state uri=$uri$which item=${p.trackItemId ?: "none"} dur=${p.durationMs ?: "?"}"
        val previous = lastReportedLinePosition
        lastReportedLinePosition = p.positionMs
        val jumpedBack = previous != null && p.positionMs != null && p.positionMs + REGRESSION_MS < previous
        if (line != lastReportLine || jumpedBack) {
            log("renderer: $line pos=${p.positionMs ?: "?"}" + (if (jumpedBack) " (was $previous)" else "") +
                " [current item ${current.uid}, armed ${armedNext?.uid ?: "none"}]")
            lastReportLine = line
        }
    }

    private var lastReportedLinePosition: Long? = null

    /** The song a stream URL is for, by its id parameter (never the whole URL: it has the credential). */
    private fun streamId(url: String): String =
        Regex("""[?&]id=([^&]+)""").find(url)?.groupValues?.get(1)?.take(12) ?: url.substringBefore('?').substringAfterLast('/').take(24)

    private fun shuffleOrderFor(
        size: Int,
        currentIndex: Int,
        enabled: Boolean,
        insertedRange: IntRange? = null,
        previous: List<Int> = emptyList(),
        previousCurrent: Int = -1,
    ): List<Int> {
        if (!enabled || size == 0) return emptyList()
        if (insertedRange != null && previous.size == size - (insertedRange.last - insertedRange.first + 1) && previousCurrent >= 0) {
            // Keep the existing order; shift old indices and slot new ones in random positions after the current.
            val shift = insertedRange.last - insertedRange.first + 1
            val shifted = previous.map { if (it >= insertedRange.first) it + shift else it }.toMutableList()
            val currentPos = shifted.indexOf(currentIndex).coerceAtLeast(0)
            for (i in insertedRange) {
                val at = if (shifted.size > currentPos + 1) currentPos + 1 + random.nextInt(shifted.size - currentPos) else shifted.size
                shifted.add(at.coerceIn(currentPos + 1, shifted.size), i)
            }
            return shifted
        }
        val order = (0 until size).shuffled(random).toMutableList()
        if (currentIndex in 0 until size) {
            order.remove(currentIndex)
            order.add(0, currentIndex)
        }
        return order
    }

    // ---- polling ---------------------------------------------------------------------------------

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                val keepGoing = mutex.withLock { pollOnce() }
                if (!keepGoing) break
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** One polling tick under the mutex. Returns false when polling should end. */
    private suspend fun pollOnce(): Boolean {
        val current = loaded ?: return false
        if (_state.value.released) return false
        val transportResult = runCatching { tx { transport.transportState() } }
        val positionResult = runCatching { tx { transport.positionInfo() } }
        if (transportResult.isFailure && positionResult.isFailure) {
            consecutivePollFailures++
            if (consecutivePollFailures >= MAX_POLL_FAILURES) {
                fail("Renderer stopped responding", transportResult.exceptionOrNull())
                return false
            }
            return true
        }
        consecutivePollFailures = 0
        val now = clock()
        val sinceCommand = now - lastCommandAt
        val s0 = _state.value
        val transportNow = transportResult.getOrNull()
        val playingNow = transportNow == TransportState.PLAYING
        if (lastPollAt > 0 && now - lastPollAt > LATE_POLL_MS) log("poll came ${now - lastPollAt} ms after the last (the phone dozed?)")
        positionResult.getOrNull()?.let { logReport(transportNow, it, current) }
        positionResult.exceptionOrNull()?.let { e ->
            val why = "${e.javaClass.simpleName}: ${e.message}"
            if (why != lastReportError) log("position report unreadable: $why")
            lastReportError = why
        } ?: run { lastReportError = null }
        if (playingNow && lastPollAt > 0) {
            val gap = (now - lastPollAt).coerceAtLeast(0)
            // PLAYING at both ends: the renderer played the whole gap, however late this poll came
            // (a phone with its screen off polls late). Otherwise it started somewhere in between.
            playedMsSinceLoad += if (lastPollPlaying) gap else gap.coerceAtMost(pollIntervalMs)
        }
        lastPollPlaying = playingNow
        lastPollAt = now

        positionResult.getOrNull()?.let { p ->
            val armed = armedNext
            val stale = isStaleReport(p)
            val frozenForMs = trackFrozenReport(p, transportNow, now)
            var reason = armed?.let { handoffReason(it, current, p, s0, now, sinceCommand, transportNow, frozenForMs, stale) }
            if (armed != null && reason == null && playingNow && s0.isPlayingNow && sinceCommand > SETTLE_MS &&
                s0.durationMs > 0 && clockPosition(s0, now) >= s0.durationMs - MEDIA_INFO_WINDOW_MS
            ) {
                reason = mediaInfoReason(armed, current, s0, now)
            }
            if (armed != null && reason != null) {
                advanceToArmed(armed, p, s0, now, reason, stale)
            } else if (stale) {
                // Still the frozen report from before the hand-off: keep the engine's own clock.
            } else if (armed == null || playingNow) {
                // While a hand-off may be under way, a STOPPED or TRANSITIONING sample must not
                // overwrite the duration and position the hand-off checks compare against.
                if (p.durationMs != null && p.durationMs > 0 && p.durationMs != s0.durationMs) {
                    _state.update { it.copy(durationMs = p.durationMs) }
                }
                val oldTracksClock = now < handoffPositionUntil && p.positionMs != null && p.positionMs > HANDOFF_FRESH_MS
                if (p.positionMs != null && sinceCommand > SETTLE_MS && !oldTracksClock) {
                    _state.update { it.copy(positionMs = p.positionMs, positionSampledAtMs = now) }
                }
            }
        }

        val s = _state.value
        when (transportResult.getOrNull()) {
            TransportState.PLAYING -> {
                if (s.playbackState == EnginePlaybackState.BUFFERING || s.isLoading) {
                    _state.update { it.copy(playbackState = EnginePlaybackState.READY, isLoading = false, positionSampledAtMs = now) }
                }
                if (!s.playWhenReady && sinceCommand > SETTLE_MS && !pausedByStop) {
                    // Resumed from the renderer's own controls.
                    _state.update { it.copy(playWhenReady = true, playbackState = EnginePlaybackState.READY, positionSampledAtMs = now) }
                    playStartedAt = now
                }
            }
            TransportState.PAUSED_PLAYBACK -> {
                if (s.playWhenReady && sinceCommand > SETTLE_MS) {
                    freezePosition()
                    _state.update { it.copy(playWhenReady = false, playbackState = EnginePlaybackState.READY, isLoading = false) }
                } else if (s.playbackState == EnginePlaybackState.BUFFERING && !s.playWhenReady) {
                    _state.update { it.copy(playbackState = EnginePlaybackState.READY, isLoading = false) }
                }
            }
            TransportState.TRANSITIONING -> {
                if (!s.isLoading) _state.update { it.copy(isLoading = true) }
            }
            TransportState.STOPPED, TransportState.NO_MEDIA_PRESENT -> {
                if (s.playWhenReady && s.playbackState == EnginePlaybackState.READY && sinceCommand > SETTLE_MS && !userStopped) {
                    val position = s.positionAt(now)
                    val nearEnd = (s.durationMs > 0 && position >= s.durationMs - END_TOLERANCE_MS) ||
                        (s.durationMs <= 0 && now - playStartedAt > UNKNOWN_DURATION_MIN_PLAY_MS)
                    if (nearEnd) {
                        skipArmedIfAlreadyPlayed(s)
                        onTrackEnded()
                        return loaded != null
                    } else {
                        // Stopped from the device (or a renderer that can't hold position); mirror as paused.
                        freezePosition()
                        pausedByStop = true
                        _state.update { it.copy(playWhenReady = false) }
                    }
                }
            }
            else -> Unit
        }

        armNextIfNeeded()
        return loaded != null
    }

    private suspend fun onTrackEnded() {
        val s = _state.value
        val nextIndex = s.autoAdvanceIndex()
        armedNext = null
        if (nextIndex == null) {
            loaded = null
            stopPolling()
            _state.update {
                it.copy(
                    playbackState = EnginePlaybackState.ENDED,
                    isLoading = false,
                    positionMs = if (it.durationMs > 0) it.durationMs else it.positionMs,
                    positionSampledAtMs = clock(),
                )
            }
            return
        }
        _state.update { it.copy(currentIndex = nextIndex, positionMs = 0, positionSampledAtMs = clock(), discontinuityCounter = it.discontinuityCounter + 1) }
        loadCurrent(0, play = true)
    }

    private suspend fun armNextIfNeeded() {
        if (setNextSupported == false) return
        val s = _state.value
        if (!s.isPlayingNow) return
        val nextIndex = s.autoAdvanceIndex() ?: return
        val nextItem = s.items.getOrNull(nextIndex) ?: return
        if (armedNext?.uid == nextItem.uid) return
        val resolved = runCatching { resolve(nextItem) }.getOrNull() ?: return
        try {
            tx { transport.setNextUri(resolved.url, DidlLite.build(resolved, itemId = nextItem.uid.toString())) }
            armedNext = LoadedTrack(nextIndex, nextItem.uid, resolved.url)
            armedAt = clock()
            if (setNextSupported == null) setNextSupported = true
            log("armed index $nextIndex as next (item ${nextItem.uid}, ${streamId(resolved.url)})")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Renderer does not do SetNextAVTransportURI (or not now); fall back to stop-detection.
            log("SetNext refused (${t.message}); each track starts when the last stops")
            setNextSupported = false
            armedNext = null
        }
    }

    /**
     * How long the renderer has reported the same position while PLAYING (0 when it is moving or
     * not playing). A renderer that is really playing moves on every poll.
     */
    private fun trackFrozenReport(p: TransportPosition, transportNow: TransportState?, now: Long): Long {
        val pos = p.positionMs
        if (transportNow != TransportState.PLAYING || pos == null) {
            lastReportedPositionMs = null
            return 0L
        }
        val last = lastReportedPositionMs
        if (last == null || kotlin.math.abs(pos - last) > FROZEN_JITTER_MS) {
            lastReportedPositionMs = pos
            reportUnchangedSinceMs = now
            return 0L
        }
        return now - reportUnchangedSinceMs
    }

    /** True while the renderer still repeats the frozen report from before a hand-off. */
    private fun isStaleReport(p: TransportPosition): Boolean {
        val stale = staleReportedPositionMs ?: return false
        val pos = p.positionMs ?: return true
        if (kotlin.math.abs(pos - stale) <= FROZEN_JITTER_MS) return true
        staleReportedPositionMs = null // the renderer reports live values again
        return false
    }

    /** The engine's own idea of the position: the last sample plus the time since, not capped at the end. */
    private fun clockPosition(s: EngineState<T>, now: Long): Long =
        if (s.isPlayingNow) s.positionMs + (now - s.positionSampledAtMs).coerceAtLeast(0) else s.positionMs

    /**
     * GetMediaInfo near the end of a track: a renderer that hides a hand-off in GetPositionInfo
     * may still show it here, as the armed URI becoming the current one or the queued next one
     * being used up. Null when it shows neither, or does not answer GetMediaInfo.
     */
    private suspend fun mediaInfoReason(armed: LoadedTrack, current: LoadedTrack, s0: EngineState<T>, now: Long): String? {
        if (mediaInfoSupported == false || now - armedAt <= SETTLE_MS) return null
        val media = try {
            tx { transport.mediaInfo() }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
        if (media == null) {
            mediaInfoSupported = false
            return null
        }
        mediaInfoSupported = true
        val currentUri = media.currentUri
        if (currentUri != null && sameUrl(currentUri, armed.url) && !sameUrl(currentUri, current.url)) return "GetMediaInfo current URI"
        if (media.nextUri != null && sameUrl(media.nextUri, armed.url)) mediaInfoShowedArmed = armed.uid
        // The queued next was taken: only meaningful if GetMediaInfo showed it queued before, and
        // once the current track should have ended.
        if (media.nextUri == null && mediaInfoShowedArmed == armed.uid &&
            clockPosition(s0, now) >= s0.durationMs - END_TOLERANCE_MS
        ) return "GetMediaInfo next URI taken"
        return null
    }

    /**
     * Why we believe the renderer moved on to the armed next track by itself, or null. The URI and
     * TrackMetaData's item id are authoritative, but some renderers keep reporting the previous
     * track after a SetNextAVTransportURI hand-off, so a position that jumps back near the end of
     * the current track, a position past its end, a position frozen near the end while PLAYING, or
     * a duration that becomes the next track's all count too. Once a renderer's report has frozen
     * ([stale]), its position and duration describe the old track, so only the engine's own clock
     * running past the current track's end counts.
     */
    private fun handoffReason(
        armed: LoadedTrack,
        current: LoadedTrack,
        p: TransportPosition,
        s0: EngineState<T>,
        now: Long,
        sinceCommand: Long,
        transportNow: TransportState?,
        frozenForMs: Long,
        stale: Boolean,
    ): String? {
        if (p.trackUri != null && sameUrl(p.trackUri, armed.url) && !sameUrl(p.trackUri, current.url)) return "uri"
        val itemId = p.trackItemId
        if (itemId != null && itemId == armed.uid.toString() && itemId != current.uid.toString()) return "track metadata"
        // Just after the renderer named the song it's moving to, its clock still counts the old
        // one: past a short new song's end, that would read as it ending too, and each poll would
        // move on again (1, 5, 9 on the RX-V483). Until the grace is over, only a URI or item id
        // says it moved on.
        if (now < handoffPositionUntil) return null
        if (sinceCommand <= SETTLE_MS || transportNow != TransportState.PLAYING || !s0.isPlayingNow) return null
        if (stale) {
            return if (s0.durationMs > 0 && clockPosition(s0, now) >= s0.durationMs + CLOCK_GRACE_MS) REASON_CLOCK else null
        }
        val pos = p.positionMs
        if (pos != null && s0.durationMs > 0) {
            val expected = s0.positionAt(now)
            val nearEnd = expected >= s0.durationMs - NEAR_END_WINDOW_MS
            if (nearEnd && pos + REGRESSION_MS < expected) return "position jumped back"
            if (pos > s0.durationMs + END_TOLERANCE_MS) return "position past the end"
            if (pos >= s0.durationMs - NEAR_END_WINDOW_MS && frozenForMs >= FROZEN_HANDOFF_MS) return REASON_FROZEN
        }
        val armedDuration = resolvedCache[armed.uid]?.durationMs ?: return null
        if (p.durationMs != null && p.durationMs > 0 && s0.durationMs > 0 && armedDuration > 0 &&
            kotlin.math.abs(p.durationMs - s0.durationMs) > DURATION_MATCH_MS &&
            kotlin.math.abs(p.durationMs - armedDuration) <= DURATION_MATCH_MS
        ) return "duration changed"
        return null
    }

    private fun advanceToArmed(armed: LoadedTrack, p: TransportPosition, s0: EngineState<T>, now: Long, reason: String, stale: Boolean) {
        val frozen = reason == REASON_FROZEN
        val reportDescribesOldTrack = frozen || stale
        // Named by URI or item id while the clock still counts the old track's end: it starts from 0.
        val earlyName = (reason == "uri" || reason == "track metadata") && p.positionMs != null &&
            s0.durationMs > 0 && p.positionMs >= s0.durationMs - NEAR_END_WINDOW_MS
        if (earlyName) handoffPositionUntil = now + HANDOFF_POSITION_GRACE_MS
        val position = when {
            earlyName -> 0L
            // The report stopped moving when the previous track ended; the next one has played since.
            frozen -> now - reportUnchangedSinceMs
            // The report is stale: the time past the old track's end on the engine's clock.
            stale -> (clockPosition(s0, now) - s0.durationMs).coerceAtLeast(0)
            // A renderer reporting time past the old end is still counting from the previous track's start.
            else -> p.positionMs?.let { if (s0.durationMs > 0 && it > s0.durationMs + END_TOLERANCE_MS) it - s0.durationMs else it } ?: 0L
        }
        val itemIdSeen = when (p.trackItemId) {
            null -> "none"
            armed.uid.toString() -> "next"
            loaded?.uid?.toString() -> "previous"
            else -> "other"
        }
        log("renderer moved to the next track on its own ($reason); now at index ${armed.index} " +
            "[report frozen=$reportDescribesOldTrack, TrackMetaData item=$itemIdSeen, GetMediaInfo=${mediaInfoSupported ?: "unasked"}]")
        if (frozen) {
            // Its reports stay pinned to the old track: ignore them, and follow later hand-offs by
            // TrackMetaData, GetMediaInfo or the engine's clock. SetNext stays armed (gapless).
            staleReportedPositionMs = p.positionMs
        }
        loaded = armed
        armedNext = null
        playStartedAt = now
        playedMsSinceLoad = position
        val reportedDuration = p.durationMs?.takeIf { d -> d > 0 && !reportDescribesOldTrack }
        _state.update {
            it.copy(
                currentIndex = armed.index,
                positionMs = position,
                positionSampledAtMs = now,
                durationMs = reportedDuration ?: resolvedCache[armed.uid]?.durationMs ?: -1,
                playbackState = EnginePlaybackState.READY,
                isLoading = false,
                discontinuityCounter = it.discontinuityCounter + 1,
            )
        }
    }

    /**
     * The renderer stopped at what looks like the end of the current track, but it has reported
     * PLAYING for long enough to have gone through the armed next track as well without telling
     * us. Count that track as finished so it is not started a second time. Played time is only
     * known as well as the polls sample it, so the cut is halfway into the armed track: nearer
     * the current track's length, it stopped there; nearer both lengths, it played both.
     */
    private fun skipArmedIfAlreadyPlayed(s: EngineState<T>) {
        val armed = armedNext ?: return
        val armedDuration = resolvedCache[armed.uid]?.durationMs ?: return
        val currentDuration = loaded?.let { resolvedCache[it.uid]?.durationMs }?.takeIf { it > 0 } ?: s.durationMs
        if (currentDuration <= 0 || armedDuration <= 0) return
        if (playedMsSinceLoad >= currentDuration + armedDuration / 2) {
            log("renderer stopped after also playing the queued track (index ${armed.index}) unnoticed; skipping it")
            _state.update { it.copy(currentIndex = armed.index) }
        }
    }

    /** The same stream, however the renderer echoes it: decoded, case aside, and without a default port (the Yamaha adds ":80"). */
    private fun sameUrl(a: String, b: String): Boolean {
        fun norm(u: String): String = runCatching { URLDecoder.decode(u.trim(), "UTF-8") }.getOrDefault(u.trim()).trimEnd('/').lowercase()
            .replaceFirst(Regex("""^(http://[^/:?#]+):80(?=[/?#]|$)"""), "$1")
            .replaceFirst(Regex("""^(https://[^/:?#]+):443(?=[/?#]|$)"""), "$1")
        return norm(a) == norm(b)
    }

    private companion object {
        /** Many speakers (the Yamaha RX-V483 among them) fetch only plain-HTTP links. */
        fun withHttpsHint(message: String, url: String?): String =
            if (url?.startsWith("https://", ignoreCase = true) == true) {
                "$message. This speaker may not play HTTPS links: give the server a plain-HTTP receiver address " +
                    "(Settings → Servers → Receiver address)."
            } else {
                message
            }

        const val MAX_POLL_FAILURES = 5
        const val SETTLE_MS = 1_800L
        const val END_TOLERANCE_MS = 3_500L
        const val REGRESSION_MS = 5_000L
        const val NEAR_END_WINDOW_MS = 20_000L
        const val DURATION_MATCH_MS = 2_000L
        /** Reported positions this close count as "not moving" (renderers round to whole seconds). */
        const val FROZEN_JITTER_MS = 500L
        /** A PLAYING renderer whose position has not moved for this long near a track's end has handed off. */
        const val FROZEN_HANDOFF_MS = 4_000L
        const val REASON_FROZEN = "position frozen at the end"
        const val REASON_CLOCK = "clock past the end"
        /** With a stale report, the engine's clock this far past a track's end means the next one is playing. */
        const val CLOCK_GRACE_MS = 2_500L
        /** GetMediaInfo is asked only this close to a track's end, while a next track is armed. */
        const val MEDIA_INFO_WINDOW_MS = 5_000L
        const val UNKNOWN_DURATION_MIN_PLAY_MS = 5_000L
        /** A poll this late means the phone wasn't running the loop (asleep, frozen): worth a log line. */
        const val LATE_POLL_MS = 5_000L
        /** How long after an early-named hand-off a position past [HANDOFF_FRESH_MS] is still the old track's. */
        const val HANDOFF_POSITION_GRACE_MS = 8_000L
        const val HANDOFF_FRESH_MS = 5_000L
    }
}
