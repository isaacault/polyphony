package app.polyphony.ui.requests

import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent
import app.polyphony.core.domain.model.RequestPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestLogicTest {

    private val stages = listOf("requested", "searching", "downloading", "importing", "scanning", "available")

    private fun pipeline(index: Int, percent: Float = 0f, hasDownload: Boolean = false) =
        RequestPipeline(stages, index, percent, "detail", null, hasDownload, stalled = false, attentionReason = null)

    private fun request(id: Long, status: String, stillLooking: Boolean = false, p: RequestPipeline? = pipeline(1)) =
        MusicRequest(id, "Title $id", "Artist", null, null, status, stillLooking, p, null, "rg-$id", null, "isaac", null)

    @Test
    fun `actions follow what continuo accepts`() {
        assertEquals(listOf(RequestAction.SEARCH_NOW, RequestAction.REMOVE), RequestLogic.actions(request(1, "searching")))
        assertEquals(
            listOf(RequestAction.SEARCH_NOW, RequestAction.RETRY, RequestAction.REMOVE),
            RequestLogic.actions(request(1, "searching", stillLooking = true)),
        )
        assertEquals(listOf(RequestAction.RETRY, RequestAction.REMOVE), RequestLogic.actions(request(1, "error")))
        assertEquals(listOf(RequestAction.RETRY, RequestAction.REMOVE), RequestLogic.actions(request(1, "needs_attention")))
        assertEquals(
            listOf(RequestAction.DROP_DOWNLOAD, RequestAction.REMOVE),
            RequestLogic.actions(request(1, "downloading", p = pipeline(2, hasDownload = true))),
        )
        // Soulseek downloads have no Lidarr queue item to drop.
        assertEquals(listOf(RequestAction.REMOVE), RequestLogic.actions(request(1, "downloading", p = pipeline(2))))
        assertEquals(listOf(RequestAction.REMOVE), RequestLogic.actions(request(1, "scanning")))
        assertEquals(listOf(RequestAction.REMOVE), RequestLogic.actions(request(1, "available")))
    }

    @Test
    fun `live updates replace the request in place`() {
        val list = listOf(request(1, "searching"), request(2, "downloading", p = pipeline(2, 10f)))
        val out = RequestLogic.apply(list, RequestEvent.Updated(2, "available", false, pipeline(5), "nd-2"))!!
        assertEquals(listOf(1L, 2L), out.map { it.id })
        assertEquals("available", out[1].status)
        assertEquals("nd-2", out[1].libraryAlbumId)
        assertEquals(5, out[1].pipeline!!.stageIndex)
    }

    @Test
    fun `an update for an unknown request asks for a reload`() {
        assertNull(RequestLogic.apply(listOf(request(1, "searching")), RequestEvent.Updated(99, "searching", false, null, null)))
    }

    @Test
    fun `progress fills whole stages plus download percent`() {
        assertEquals(0.2f, RequestLogic.progress(request(1, "searching", p = pipeline(1))), 0.001f)
        assertEquals(0.5f, RequestLogic.progress(request(1, "downloading", p = pipeline(2, 50f))), 0.001f)
        assertEquals(1f, RequestLogic.progress(request(1, "available", p = pipeline(5))), 0.001f)
        assertEquals(0f, RequestLogic.progress(request(1, "pending", p = null)), 0.001f)
    }
}
