package app.polyphony.core.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** contract/cases/release-type.json: the cases the Swift side (PolyphonyKit) must pass too. */
class ReleaseTypeContractTest {
    @Test fun `release types, as the contract has them`() {
        val cases = Json.parseToJsonElement(File("../../contract/cases/release-type.json").readText()).jsonObject.getValue("cases").jsonArray
        for (case in cases.map { it.jsonObject }) {
            val raw = case.getValue("types")
            val types = if (raw is JsonNull) null else raw.jsonArray.map { it.jsonPrimitive.content }
            val expected = case.getValue("type").jsonPrimitive.contentOrNull?.let(ReleaseType::valueOf)
            assertEquals("$types", expected, ReleaseType.of(types))
        }
    }
}
