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
    private var loaded: LoadedTrack? = null
    private var armedNext: LoadedTrack? = null
    private val resolvedCache = HashMap<Long, ResolvedStream>()

    private data class LoadedTrack(val index: Int, val uid: Long, val url: String)

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
        _state.update { it.copy(playbackState = EnginePlaybackState.BUFFERING, isLoading = true, error = null, durationMs = -1) }
        try {
            val resolved = resolve(item) ?: throw DlnaException(716, "No playable stream for this track")
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
            fail("Could not start playback on renderer", t)
        }
    }

    private fun markPlaying() {
        val now = clock()
        playStartedAt = now
        lastCommandAt = now
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
        if (wantedUid != armed.uid) armedNext = null
        else if (wanted != armed.index) armedNext = armed.copy(index = wanted)
    }

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

        positionResult.getOrNull()?.let { p ->
            val armed = armedNext
            if (armed != null && p.trackUri != null && sameUrl(p.trackUri, armed.url) && !sameUrl(p.trackUri, current.url)) {
                // Renderer advanced to the pre-loaded next track on its own (gapless).
                loaded = armed
                armedNext = null
                playStartedAt = now
                _state.update {
                    it.copy(
                        currentIndex = armed.index,
                        positionMs = p.positionMs ?: 0,
                        positionSampledAtMs = now,
                        durationMs = p.durationMs ?: resolvedCache[armed.uid]?.durationMs ?: -1,
                        playbackState = EnginePlaybackState.READY,
                        isLoading = false,
                        discontinuityCounter = it.discontinuityCounter + 1,
                    )
                }
            } else {
                if (p.durationMs != null && p.durationMs > 0 && p.durationMs != s0.durationMs) {
                    _state.update { it.copy(durationMs = p.durationMs) }
                }
                if (p.positionMs != null && sinceCommand > SETTLE_MS) {
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
            if (setNextSupported == null) setNextSupported = true
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Renderer does not do SetNextAVTransportURI (or not now); fall back to stop-detection.
            setNextSupported = false
            armedNext = null
        }
    }

    private fun sameUrl(a: String, b: String): Boolean {
        fun norm(u: String): String = runCatching { URLDecoder.decode(u.trim(), "UTF-8") }.getOrDefault(u.trim()).trimEnd('/').lowercase()
        return norm(a) == norm(b)
    }

    private companion object {
        const val MAX_POLL_FAILURES = 5
        const val SETTLE_MS = 1_800L
        const val END_TOLERANCE_MS = 3_500L
        const val UNKNOWN_DURATION_MIN_PLAY_MS = 5_000L
    }
}
