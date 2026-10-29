package app.polyphony.ui.requests

import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent

/** Which requests the screen lists. */
enum class RequestScope { ACTIVE, ALL }

/** Pure rules for the Requests screen (#117), kept apart so they're unit-tested. */
object RequestLogic {

    /**
     * What a request offers, in display order. Mirrors what continuo accepts: search-now refuses
     * available and errored requests (use retry), drop-download needs a queue item.
     */
    fun actions(r: MusicRequest): List<RequestAction> = buildList {
        when (r.status) {
            "available" -> Unit
            "error", "needs_attention" -> add(RequestAction.RETRY)
            "pending", "searching" -> {
                add(RequestAction.SEARCH_NOW)
                if (r.stillLooking) add(RequestAction.RETRY)
            }
            "downloading" -> if (r.pipeline?.hasDownload == true) add(RequestAction.DROP_DOWNLOAD)
            else -> Unit   // importing, scanning: Lidarr/Navidrome are finishing it
        }
        add(RequestAction.REMOVE)
    }

    /**
     * Apply a live update to the list. Returns null when the update is for a request the list
     * doesn't hold (made elsewhere, or newly visible): the caller reloads instead.
     */
    fun apply(list: List<MusicRequest>, event: RequestEvent.Updated): List<MusicRequest>? {
        val index = list.indexOfFirst { it.id == event.id }
        if (index < 0) return null
        val updated = list[index].copy(
            status = event.status,
            stillLooking = event.stillLooking,
            pipeline = event.pipeline ?: list[index].pipeline,
            libraryAlbumId = event.libraryAlbumId ?: list[index].libraryAlbumId,
        )
        // A request that just became available stays in an Active list until the next reload,
        // so it doesn't vanish under the user's finger.
        return list.toMutableList().also { it[index] = updated }
    }

    /** Fraction of the stepper to fill: whole stages done, plus progress inside the current one. */
    fun progress(r: MusicRequest): Float {
        val p = r.pipeline ?: return 0f
        val stages = p.stages.size.takeIf { it > 1 } ?: DEFAULT_STAGES
        if (r.status == "available") return 1f
        val within = if (r.status == "downloading") (p.percent / 100f).coerceIn(0f, 1f) else 0f
        return ((p.stageIndex + within) / (stages - 1)).coerceIn(0f, 1f)
    }

    private const val DEFAULT_STAGES = 6
}
