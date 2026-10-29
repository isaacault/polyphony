package app.polyphony.core.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import app.polyphony.core.domain.playback.ResumePoint
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** contract/cases (artist-credits, resume): the cases the Swift side (PolyphonyKit) must pass too. */
class ContractCasesTest {
    private val cases: JsonObject =
        Json.parseToJsonElement(File("../../contract/cases/artist-credits.json").readText()).jsonObject

    @Test fun `artist names, as the contract has them`() {
        for (case in cases.getValue("names").jsonArray.map { it.jsonObject }) {
            val tag = case.getValue("tag").jsonPrimitive.content
            assertEquals(tag, case.getValue("names").jsonArray.map { it.jsonPrimitive.content }, ArtistCredits.names(tag))
        }
    }

    @Test fun `credits, as the contract has them`() {
        for (case in cases.getValue("credits").jsonArray.map { it.jsonObject }) {
            val tag = case.getValue("tag").jsonPrimitive.contentOrNull
            val artist = case.getValue("artist").jsonPrimitive.content
            assertEquals("$tag / $artist", case.getValue("credits").jsonPrimitive.boolean, ArtistCredits.credits(tag, artist))
        }
    }

    @Test fun `resume points, as the contract has them`() {
        val resume = Json.parseToJsonElement(File("../../contract/cases/resume.json").readText()).jsonObject
        for (case in resume.getValue("cases").jsonArray.map { it.jsonObject }) {
            val tracks = case.getValue("tracks").jsonArray.map {
                Track(id = it.jsonPrimitive.content, serverId = "s", title = it.jsonPrimitive.content, durationMs = 200_000)
            }
            val entry = RecentContext(
                context = PlayContext(PlayContext.Kind.ALBUM, "al", "s", "Album"),
                trackId = case.getValue("trackId").jsonPrimitive.contentOrNull,
                trackIndex = case.getValue("trackIndex").jsonPrimitive.int,
                positionMs = case.getValue("positionMs").jsonPrimitive.long,
            )
            val expected = ResumePoint(case.getValue("index").jsonPrimitive.int, case.getValue("resumeMs").jsonPrimitive.long)
            assertEquals(case.getValue("about").jsonPrimitive.content, expected, ResumePoint.of(tracks, entry))
        }
    }
}
