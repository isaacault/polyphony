package app.polyphony.core.playback.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** contract/cases/receiver.json: the cases the Swift side (PolyphonyKit) must pass too. */
class ReceiverContractTest {
    private val cases = Json.parseToJsonElement(File("../../contract/cases/receiver.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test fun `receiver URLs, as the contract has them`() {
        for (case in cases) {
            fun text(key: String) = case[key]?.jsonPrimitive?.contentOrNull
            assertEquals(
                text("name"),
                text("expected"),
                rewriteForReceiver(text("url")!!, text("baseUrl")!!, text("override"), text("lanIp")),
            )
        }
    }
}
