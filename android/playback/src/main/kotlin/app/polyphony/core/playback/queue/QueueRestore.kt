package app.polyphony.core.playback.queue

import androidx.media3.common.Player
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Track

/**
 * How a saved queue ([PersistedQueue]) comes back after the process died or the phone restarted.
 * Pure; unit-tested. The service builds the player's items from [Plan] and applies it paused: a
 * restored queue never starts playing by itself (only a play command, or Media3's resumption on a
 * media button, does).
 */
object QueueRestore {
    data class Plan(
        val tracks: List<Track>,
        /** Per track: one you added, "Next in queue" (UserQueue). */
        val queued: List<Boolean>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
        /** The saved shuffle order over [tracks], null when there is none to put back. */
        val shuffleOrder: List<Int>?,
        val queueTitle: String?,
        val context: PlayContext?,
    )

    fun plan(saved: PersistedQueue): Plan? {
        val kept = Kept(saved.tracks.size) { true }
        val (index, _) = kept.current(saved.index.coerceIn(0, (saved.tracks.size - 1).coerceAtLeast(0))) ?: return null
        val queued = saved.queuedIndices.mapNotNull { kept.newIndex(it).takeIf { n -> n >= 0 } }.toHashSet()
        val tracks = kept.old.map { saved.tracks[it] }
        return Plan(
            tracks = tracks,
            queued = tracks.indices.map { it in queued },
            index = index,
            positionMs = saved.positionMs.coerceAtLeast(0L),
            shuffle = saved.shuffle,
            repeatMode = saved.repeatMode.takeIf { it in REPEAT_MODES } ?: Player.REPEAT_MODE_OFF,
            shuffleOrder = kept.order(saved.shuffleOrder).takeIf { it.isNotEmpty() },
            queueTitle = saved.queueTitle,
            context = saved.context,
        )
    }

    private val REPEAT_MODES = setOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)
}

/**
 * The saved shuffle order, waiting for the restored list to reach the player. Setting a list makes
 * the player shuffle it afresh, and a restored list can be set twice (put back at start, then once
 * more by Media3 when a media button's resumption raced it), so the order is put back each time the
 * player holds exactly the restored list in another order, until the list changes or playing starts.
 */
class PendingShuffleOrder(private val ids: List<String>, val order: List<Int>) {
    enum class Step {
        /** The player holds the restored list in another order: set [order]. */
        APPLY,
        /** The player holds the restored list in [order], or no list yet: nothing to do. */
        NOTHING,
        /** The player holds another list: forget this. */
        FORGET,
    }

    fun step(currentIds: List<String>, currentOrder: List<Int>): Step = when {
        currentIds.isEmpty() -> Step.NOTHING
        currentIds != ids -> Step.FORGET
        currentOrder == order -> Step.NOTHING
        else -> Step.APPLY
    }
}
