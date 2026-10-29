package app.polyphony.ui.browse.album

import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.qualityBadgeLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** contract/cases/quality.json: the cases the Swift side (PolyphonyKit) must pass too. */
class ContractCasesTest {
    private val cases: JsonObject =
        Json.parseToJsonElement(File("../../contract/cases/quality.json").readText()).jsonObject

    private fun track(o: JsonObject, i: Int) = Track(
        id = "t$i", serverId = "s", title = "t$i",
        container = o.getValue("container").jsonPrimitive.contentOrNull,
        bitDepth = o.getValue("bitDepth").jsonPrimitive.intOrNull,
        sampleRateHz = o.getValue("sampleRate").jsonPrimitive.intOrNull,
    )

    @Test fun `badges, as the contract has them`() {
        cases.getValue("badges").jsonArray.map { it.jsonObject }.forEachIndexed { i, case ->
            assertEquals(case.toString(), case.getValue("badge").jsonPrimitive.contentOrNull, qualityBadgeLabel(track(case, i)))
        }
    }

    @Test fun `album badges and track chips, as the contract has them`() {
        for (album in cases.getValue("albums").jsonArray.map { it.jsonObject }) {
            val specs = album.getValue("tracks").jsonArray.map { it.jsonObject }
            val tracks = specs.mapIndexed { i, o -> track(o, i) }
            val badge = AlbumTrackDetails.albumQuality(tracks)
            assertEquals(album.toString(), album.getValue("badge").jsonPrimitive.contentOrNull, badge)
            specs.forEachIndexed { i, o ->
                assertEquals("track $i of $album", o.getValue("chip").jsonPrimitive.contentOrNull,
                             AlbumTrackDetails.trackQuality(tracks[i], badge))
            }
        }
    }

    @Test fun `album page credits, as the contract has them`() {
        val credits = Json.parseToJsonElement(File("../../contract/cases/album-credit.json").readText()).jsonObject
        for (case in credits.getValue("cases").jsonArray.map { it.jsonObject }) {
            val track = Track(id = "t", serverId = "s", title = case.getValue("title").jsonPrimitive.content,
                              artistName = case.getValue("artist").jsonPrimitive.contentOrNull)
            val expected = (case["credit"] as? JsonObject)?.let { c ->
                c["featuring"]?.jsonPrimitive?.content?.let { AlbumTrackDetails.Credit.Featuring(it) }
                    ?: AlbumTrackDetails.Credit.Other(c.getValue("other").jsonPrimitive.content)
            }
            assertEquals(case.getValue("name").jsonPrimitive.content, expected,
                         AlbumTrackDetails.credit(track, case.getValue("albumArtist").jsonPrimitive.contentOrNull))
        }
    }
}
