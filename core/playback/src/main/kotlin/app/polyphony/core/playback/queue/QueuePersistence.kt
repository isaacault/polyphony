package app.polyphony.core.playback.queue

import android.content.Context
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
)

/** Atomic JSON persistence of the queue for playback resumption after process death. */
@Singleton
class QueuePersistence @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.filesDir, "playback/queue.json")

    suspend fun save(queue: PersistedQueue) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(PlaybackJson.encodeToString(queue))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    suspend fun load(): PersistedQueue? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) null
            else PlaybackJson.decodeFromString(PersistedQueue.serializer(), file.readText())
        }.getOrNull()
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }
}
