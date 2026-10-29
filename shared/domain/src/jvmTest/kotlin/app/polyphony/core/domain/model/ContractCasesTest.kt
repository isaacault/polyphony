package app.polyphony.core.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** contract/cases/artist-credits.json: the cases the Swift side (PolyphonyKit) must pass too. */
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
}
