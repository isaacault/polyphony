package app.polyphony.core.playback.queue

import app.polyphony.core.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** In-process cache of tracks referenced by queue items (id → Track). */
@Singleton
class QueueStore @Inject constructor() {
    private val tracks = ConcurrentHashMap<String, Track>()
    private val _changes = MutableStateFlow(0)

    /** Bumps whenever a cached track's fields change (e.g. favourite toggled). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun put(track: Track) { tracks[track.id] = track }
    fun putAll(list: Collection<Track>) = list.forEach { tracks[it.id] = it }
    fun get(id: String): Track? = tracks[id]
    fun update(id: String, transform: (Track) -> Track) {
        tracks[id]?.let { tracks[id] = transform(it) }
        _changes.update { it + 1 }
    }

    /** Drop everything not in [keepIds] (called after queue changes to bound memory). */
    fun retain(keepIds: Collection<String>) {
        val keep = keepIds.toHashSet()
        tracks.keys.retainAll(keep)
    }
    fun clear() = tracks.clear()
}
