package app.polyphony.ui.browse.album

import app.polyphony.core.domain.model.CatalogAlbumTrack
import app.polyphony.core.domain.model.LibraryAlbumTracklist
import app.polyphony.core.domain.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** contract/cases/album-tracklist.json: the cases the Swift side (PolyphonyKit) must pass too. */
class AlbumTracklistContractTest {
    @Test fun `album pages, as the contract has them`() {
        val cases = Json.parseToJsonElement(File("../../contract/cases/album-tracklist.json").readText())
            .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        for (case in cases) {
            val library = case.getValue("library").jsonArray.mapIndexed { i, t ->
                Track(id = "t$i", serverId = "s", title = t.jsonPrimitive.content, discNumber = 1)
            }
            val whole = (case["tracklist"] as? JsonArray)?.let { list ->
                LibraryAlbumTracklist("rg", list.mapIndexed { i, t -> CatalogAlbumTrack(i + 1, 1, t.jsonPrimitive.content, 1_000, "rec-$i") })
            }
            val rows = AlbumTracklist.entries(library, whole, null, null).map {
                when (it) {
                    is AlbumEntry.Have -> "have:" + it.track.title
                    is AlbumEntry.Missing -> "missing:" + it.track.title
                }
            }
            assertEquals(case.getValue("name").jsonPrimitive.content,
                         case.getValue("rows").jsonArray.map { it.jsonPrimitive.content }, rows)
        }
    }
}
