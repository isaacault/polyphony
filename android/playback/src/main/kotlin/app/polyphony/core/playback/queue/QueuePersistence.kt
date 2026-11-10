package app.polyphony.core.playback.queue

import android.content.Context
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Track
import app.polyphony.core.playback.util.PlaybackJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PersistedQueue(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val queueTitle: String? = null,
    val savedAt: Long = 0L,
    val context: PlayContext? = null,
)

/**
 * The queue on disk, for playback resumption after process death or a reboot ([AtomicTextFile]:
 * a kill mid-save keeps the last complete queue, and overlapping saves keep the newest).
 */
@Singleton
class QueuePersistence @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicTextFile(File(context.filesDir, "playback/queue.json"))

    /** Saves [queue]. Call it where the snapshot is taken: saves land in the order they're called. */
    suspend fun save(queue: PersistedQueue) {
        val ticket = file.ticket()
        withContext(Dispatchers.IO) {
            runCatching { file.write(ticket, PlaybackJson.encodeToString(queue)) }
        }
    }

    suspend fun load(): PersistedQueue? = withContext(Dispatchers.IO) {
        file.read { PlaybackJson.decodeFromString(PersistedQueue.serializer(), it) }
    }

    suspend fun clear() {
        val ticket = file.ticket()
        withContext(Dispatchers.IO) { runCatching { file.clear(ticket) } }
    }
}
