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

    @Test
    fun `with a storage cap, rules queue only what fits`() {
        val items = listOf("a", "b", "c").map { EnqueueItem(track(it), "r1", TranscodeProfile.ORIGINAL_ID) }
        assertEquals(listOf("a", "b"), RuleDiff.withinBudget(items, budgetBytes = 25, estimateBytes = 10).map { it.track.id })
        assertTrue(RuleDiff.withinBudget(items, budgetBytes = -5, estimateBytes = 10).isEmpty())
        assertEquals(3, RuleDiff.withinBudget(items, budgetBytes = 1_000, estimateBytes = 10).size)
    }

    @Test
    fun `a new download is estimated at the average finished one`() {
        val current = listOf(
            have("a").copy(sizeBytes = 6), have("b").copy(sizeBytes = 10),
            have("q", status = DownloadStatus.QUEUED).copy(sizeBytes = 0),
        )
        assertEquals(8, RuleDiff.estimateBytes(current))
        assertEquals(42, RuleDiff.estimateBytes(emptyList(), fallback = 42))
    }

    @Test
    fun `a rule that finds nothing keeps its downloads and is shown as finding nothing`() {
        val holding = RuleDiff.holding(
            found = mapOf(
                "deleted playlist" to emptyList(),
                "lookup failed" to null,
                "new rule, nothing yet" to emptyList(),
                "fine" to listOf(track("a")),
            ),
            owning = setOf("deleted playlist", "lookup failed", "fine"),
        )
        assertEquals(setOf("deleted playlist", "lookup failed"), holding.held)
        // A failed lookup says nothing about the rule; a new rule has nothing to keep.
        assertEquals(setOf("deleted playlist"), holding.empty)
    }

    @Test
    fun `a smart playlist matching nothing now lets its downloads go`() {
        val holding = RuleDiff.holding(
            found = mapOf("smart, nothing new" to emptyList()),
            owning = setOf("smart, nothing new"),
            mayBeEmpty = setOf("smart, nothing new"),
        )
        assertEquals(emptySet<String>(), holding.held)
        assertEquals(emptySet<String>(), holding.empty)
    }

    @Test
    fun `a pinned song a rule also wants is tied to it, stays pinned, and is untied when the rule lets go`() {
        val wants = RuleDiff.mergeDesired(listOf(rule("r1") to listOf(track("mine"))), TranscodeProfile.ORIGINAL_ID, profiles)
        val pinned = have("mine", pinned = true, ruleId = null)
        val tied = RuleDiff.compute(wants, listOf(pinned), removeWhenRuleNoLongerMatches = true, profiles)
        assertEquals(listOf("mine" to "r1"), tied.retag)
        assertTrue("nothing downloaded again", tied.enqueue.isEmpty())

        val none = RuleDiff.mergeDesired(listOf(rule("r1") to emptyList()), TranscodeProfile.ORIGINAL_ID, profiles)
        val linked = have("mine", pinned = true, ruleId = "r1")
        val untied = RuleDiff.compute(none, listOf(linked), removeWhenRuleNoLongerMatches = true, profiles)
        assertEquals(listOf("mine" to null), untied.retag)
        assertTrue("a pinned song is never removed by a rule", untied.remove.isEmpty())

        val heldRule = RuleDiff.compute(none, listOf(linked), removeWhenRuleNoLongerMatches = true, profiles, held = setOf("r1"))
        assertTrue("a rule that couldn't say keeps its tie", heldRule.retag.isEmpty())
    }
}
