package app.polyphony.core.playback.audio

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.ReplayGainMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** contract/cases/replaygain.json: the cases the Swift side (PolyphonyKit) must pass too. */
class ReplayGainContractTest {
    private val cases = Json.parseToJsonElement(File("../../contract/cases/replaygain.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.double(key: String) = get(key)?.jsonPrimitive?.doubleOrNull

    @Test fun `volumes, as the contract has them`() {
        for (case in cases) {
            val track = Track(
                id = "t", serverId = "s", title = "t", albumId = "a",
                replayGainTrackDb = case.double("trackGain"), replayGainAlbumDb = case.double("albumGain"),
                replayGainTrackPeak = case.double("trackPeak"), replayGainAlbumPeak = case.double("albumPeak"),
            )
            val volume = ReplayGain.volumeFor(
                track = track,
                mode = ReplayGainMode.valueOf(case.getValue("mode").jsonPrimitive.content.uppercase()),
                preampDb = (case.double("preampDb") ?: 0.0).toFloat(),
                preventClipping = case["preventClipping"]?.jsonPrimitive?.booleanOrNull ?: true,
                sameAlbumAsNeighbour = case["sameAlbum"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            assertEquals(case.getValue("name").jsonPrimitive.content, case.double("volume")!!.toFloat(), volume, 0.002f)
        }
    }
}
