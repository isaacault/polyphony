package app.polyphony.core.downloads.engine

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.DownloadRule
import app.polyphony.core.domain.repository.DownloadRuleTarget
import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.DownloadedTrack
import app.polyphony.core.domain.transcode.TranscodeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDiffTest {

    private val profiles = TranscodeProfile.BUILT_INS.associateBy { it.id }
    private fun track(id: String) = Track(id = id, serverId = "s1", title = "T$id")
    private fun rule(id: String, profileId: String? = null, maxTracks: Int = 0) =
        DownloadRule(id = id, serverId = "s1", target = DownloadRuleTarget.Favorites, profileId = profileId, maxTracks = maxTracks)
    private fun have(
        id: String,
        status: DownloadStatus = DownloadStatus.COMPLETED,
        pinned: Boolean = false,
        ruleId: String? = "r1",
        profileId: String = TranscodeProfile.ORIGINAL_ID,
    ) = DownloadedTrack(trackId = id, serverId = "s1", status = status, filePath = "/f/$id", profileId = profileId, pinned = pinned, ruleId = ruleId)

    @Test
    fun `new desired tracks are enqueued`() {
        val desired = RuleDiff.mergeDesired(listOf(rule("r1") to listOf(track("a"), track("b"))), TranscodeProfile.ORIGINAL_ID, profiles)
        val plan = RuleDiff.compute(desired, emptyList(), removeWhenRuleNoLongerMatches = true, profiles)
        assertEquals(listOf("a", "b"), plan.enqueue.map { it.track.id })
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun `rule-managed tracks no longer desired are removed, pinned ones are kept`() {
        val desired = RuleDiff.mergeDesired(listOf(rule("r1") to listOf(track("a"))), TranscodeProfile.ORIGINAL_ID, profiles)
        val current = listOf(have("a"), have("gone"), have("mine", pinned = true, ruleId = null))
        val plan = RuleDiff.compute(desired, current, removeWhenRuleNoLongerMatches = true, profiles)
        assertEquals(listOf("gone"), plan.remove)
        assertTrue(plan.enqueue.isEmpty())
    }

    @Test
    fun `removal is suppressed when the setting is off`() {
        val desired = RuleDiff.mergeDesired(listOf(rule("r1") to listOf(track("a"))), TranscodeProfile.ORIGINAL_ID, profiles)
        val plan = RuleDiff.compute(desired, listOf(have("gone")), removeWhenRuleNoLongerMatches = false, profiles)
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun `track wanted by several rules keeps the highest quality`() {
        val desired = RuleDiff.mergeDesired(
            listOf(
                rule("saver", profileId = TranscodeProfile.SAVER_ID) to listOf(track("a")),
                rule("hq", profileId = TranscodeProfile.ORIGINAL_ID) to listOf(track("a")),
            ),
            TranscodeProfile.BALANCED_ID,
            profiles,
        )
        assertEquals("hq", desired.getValue("a").ruleId)
        assertEquals(TranscodeProfile.ORIGINAL_ID, desired.getValue("a").profileId)
    }

    @Test
    fun `maxTracks caps a rule in its natural order`() {
        val desired = RuleDiff.mergeDesired(listOf(rule("r1", maxTracks = 2) to listOf(track("a"), track("b"), track("c"))), TranscodeProfile.ORIGINAL_ID, profiles)
        assertEquals(setOf("a", "b"), desired.keys)
    }

    @Test
    fun `existing lower quality download is upgraded, equal quality is left alone`() {
        val desired = RuleDiff.mergeDesired(listOf(rule("r1", profileId = TranscodeProfile.ORIGINAL_ID) to listOf(track("a"), track("b"))), TranscodeProfile.ORIGINAL_ID, profiles)
        val current = listOf(have("a", profileId = TranscodeProfile.SAVER_ID), have("b", profileId = TranscodeProfile.ORIGINAL_ID))
        val plan = RuleDiff.compute(desired, current, removeWhenRuleNoLongerMatches = true, profiles)
        assertEquals(listOf("a"), plan.enqueue.map { it.track.id })
    }

    @Test
    fun `failed downloads are retried and orphaned rule-managed files are retagged`() {
        val desired = RuleDiff.mergeDesired(listOf(rule("r2") to listOf(track("a"), track("b"))), TranscodeProfile.ORIGINAL_ID, profiles)
        val current = listOf(have("a", status = DownloadStatus.FAILED), have("b", ruleId = "deleted-rule"))
        val plan = RuleDiff.compute(desired, current, removeWhenRuleNoLongerMatches = true, profiles)
        assertEquals(listOf("a"), plan.enqueue.map { it.track.id })
        assertEquals(listOf("b" to "r2"), plan.retag)
    }

    @Test
    fun `disabled rules contribute nothing`() {
        val desired = RuleDiff.mergeDesired(listOf(rule("r1").copy(enabled = false) to listOf(track("a"))), TranscodeProfile.ORIGINAL_ID, profiles)
        assertTrue(desired.isEmpty())
    }

    @Test
    fun `a held rule's downloads are kept though nothing wants them`() {
        val desired = RuleDiff.mergeDesired(listOf(rule("r1") to emptyList(), rule("r2") to listOf(track("b"))), TranscodeProfile.ORIGINAL_ID, profiles)
        val current = listOf(have("a", ruleId = "r1"), have("b", ruleId = "r2"), have("c", ruleId = "r2"))
        val plan = RuleDiff.compute(desired, current, removeWhenRuleNoLongerMatches = true, profiles, held = setOf("r1"))
        assertEquals(listOf("c"), plan.remove)
    }
}
