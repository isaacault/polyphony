package app.polyphony.core.downloads.engine

import app.polyphony.core.domain.repository.DownloadedTrack

/** Picks rule-managed, completed downloads to delete when over the storage cap. Never touches pinned ones. */
object Eviction {

    fun plan(
        current: List<DownloadedTrack>,
        usedBytes: Long,
        limitBytes: Long,
        evictLeastRecentlyPlayed: Boolean,
    ): List<String> {
        if (limitBytes <= 0L || usedBytes <= limitBytes) return emptyList()
        val candidates = current
            .filter { !it.pinned && it.isComplete }
            .sortedWith(
                if (evictLeastRecentlyPlayed) compareBy<DownloadedTrack> { it.lastAccessAt }.thenBy { it.createdAt }
                else compareBy<DownloadedTrack> { it.createdAt }.thenBy { it.lastAccessAt },
            )
        var remaining = usedBytes
        val evict = ArrayList<String>()
        for (c in candidates) {
            if (remaining <= limitBytes) break
            evict += c.trackId
            remaining -= c.sizeBytes
        }
        return evict
    }
}
