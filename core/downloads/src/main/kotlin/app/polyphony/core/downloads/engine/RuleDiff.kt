package app.polyphony.core.downloads.engine

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.DownloadRule
import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.DownloadedTrack
import app.polyphony.core.domain.transcode.TranscodeProfile

/** What a rule wants for one track. */
data class Desired(
    val track: Track,
    val ruleId: String,
    val profileId: String,
    val qualityRank: Int,
)

data class EnqueueItem(val track: Track, val ruleId: String, val profileId: String)

data class Plan(
    val enqueue: List<EnqueueItem> = emptyList(),
    val remove: List<String> = emptyList(),
    /** Existing downloads whose owning rule should change (trackId → ruleId). */
    val retag: List<Pair<String, String>> = emptyList(),
) {
    val isEmpty: Boolean get() = enqueue.isEmpty() && remove.isEmpty() && retag.isEmpty()
}

/** Pure diffing between what rules want and what is on disk. */
object RuleDiff {

    fun qualityRank(profileId: String?, profiles: Map<String, TranscodeProfile>): Int {
        val id = profileId ?: return 0
        return profiles[id]?.qualityRank ?: TranscodeProfile.builtIn(id)?.qualityRank ?: 0
    }

    /**
     * Merge each rule's track list (already in the rule's natural order, best-first) into one
     * desired map. [DownloadRule.maxTracks] caps a rule; a track wanted by several rules keeps the
     * highest-quality profile (ties go to the earlier rule).
     */
    fun mergeDesired(
        perRule: List<Pair<DownloadRule, List<Track>>>,
        defaultProfileId: String,
        profiles: Map<String, TranscodeProfile>,
    ): Map<String, Desired> {
        val out = LinkedHashMap<String, Desired>()
        for ((rule, tracks) in perRule) {
            if (!rule.enabled) continue
            val profileId = rule.profileId ?: defaultProfileId
            val rank = qualityRank(profileId, profiles)
            val limited = if (rule.maxTracks > 0) tracks.take(rule.maxTracks) else tracks
            for (track in limited) {
                val existing = out[track.id]
                if (existing == null || rank > existing.qualityRank) {
                    out[track.id] = Desired(track, rule.id, profileId, rank)
                }
            }
        }
        return out
    }

    fun compute(
        desired: Map<String, Desired>,
        current: List<DownloadedTrack>,
        removeWhenRuleNoLongerMatches: Boolean,
        profiles: Map<String, TranscodeProfile>,
    ): Plan {
        val byId = current.associateBy { it.trackId }
        val enqueue = ArrayList<EnqueueItem>()
        val retag = ArrayList<Pair<String, String>>()

        for ((trackId, want) in desired) {
            val have = byId[trackId]
            if (have == null) {
                enqueue += EnqueueItem(want.track, want.ruleId, want.profileId)
                continue
            }
            val haveRank = qualityRank(have.profileId, profiles)
            when (have.status) {
                DownloadStatus.COMPLETED, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                    if (want.qualityRank > haveRank) {
                        enqueue += EnqueueItem(want.track, want.ruleId, want.profileId)
                    } else if (!have.pinned && have.ruleId != want.ruleId) {
                        retag += trackId to want.ruleId
                    }
                }
                DownloadStatus.FAILED -> enqueue += EnqueueItem(want.track, want.ruleId, want.profileId)
                DownloadStatus.PAUSED -> Unit
            }
        }

        val remove = if (removeWhenRuleNoLongerMatches) {
            current.filter { !it.pinned && it.trackId !in desired }.map { it.trackId }
        } else {
            emptyList()
        }
        return Plan(enqueue = enqueue, remove = remove, retag = retag)
    }
}
