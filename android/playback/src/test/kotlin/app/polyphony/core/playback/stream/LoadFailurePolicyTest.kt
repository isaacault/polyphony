package app.polyphony.core.playback.stream

import androidx.media3.common.PlaybackException
import app.polyphony.core.playback.stream.LoadFailurePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadFailurePolicyTest {
    private val badStatus = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    private val noConnection = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

    private fun failure(
        errorCode: Int = badStatus,
        httpStatus: Int? = 503,
        online: Boolean = true,
        adaptive: Boolean = false,
        ordinaryTried: Boolean = false,
        downloaded: Boolean = false,
        retries: Int = 0,
        originalQualityLeft: Boolean = false,
    ) = LoadFailurePolicy.Failure(errorCode, httpStatus, online, adaptive, ordinaryTried, downloaded, retries, originalQualityLeft)

    private fun decide(f: LoadFailurePolicy.Failure) = LoadFailurePolicy.decide(f)

    @Test fun `a failure is retried twice, backing off`() {
        assertEquals(Action.Retry(1, 1_500), decide(failure(retries = 0)))
        assertEquals(Action.Retry(2, 3_000), decide(failure(retries = 1)))
    }

    @Test fun `a song gone from the server is retried once, then skipped`() {
        assertEquals("a server restarting can say 404 for a moment", Action.Retry(1, 1_500), decide(failure(httpStatus = 404)))
        assertEquals(Action.SkipGone, decide(failure(httpStatus = 404, retries = 1)))
        assertEquals(Action.SkipGone, decide(failure(httpStatus = 410, retries = 1)))
    }

    @Test fun `an adaptive song gone from continuo tries its ordinary stream first`() {
        assertEquals(Action.PlayOrdinaryStream, decide(failure(httpStatus = 404, retries = 1, adaptive = true)))
        assertEquals(Action.SkipGone, decide(failure(httpStatus = 404, retries = 1, adaptive = true, ordinaryTried = true)))
    }

    @Test fun `offline, a 404 isn't believed`() {
        assertEquals(Action.WaitForNetwork, decide(failure(httpStatus = 404, retries = 2, online = false)))
    }

    @Test fun `an adaptive song keeps its stream through a lost connection`() {
        assertEquals(Action.WaitForNetwork, decide(failure(adaptive = true, retries = 2, errorCode = noConnection, httpStatus = null)))
        assertEquals(Action.WaitForNetwork, decide(failure(adaptive = true, retries = 2, online = false)))
    }

    @Test fun `an adaptive song continuo can't serve plays its ordinary stream, once`() {
        assertEquals(Action.PlayOrdinaryStream, decide(failure(adaptive = true, retries = 2)))
        assertEquals(
            Action.PlayOrdinaryStream,
            decide(failure(adaptive = true, retries = 2, errorCode = PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, httpStatus = null)),
        )
        assertEquals(Action.WaitForNetwork, decide(failure(adaptive = true, retries = 2, ordinaryTried = true)))
    }

    @Test fun `an adaptive song downloaded since takes the ordinary path, even offline`() {
        assertEquals(Action.PlayOrdinaryStream, decide(failure(adaptive = true, retries = 2, online = false, downloaded = true)))
    }

    @Test fun `a transcoded song tries original quality, then waits on the server`() {
        assertEquals(Action.PlayOriginalQuality, decide(failure(retries = 2, originalQualityLeft = true)))
        assertEquals(Action.WaitForNetwork, decide(failure(retries = 2)))
        assertEquals(Action.WaitForNetwork, decide(failure(retries = 2, errorCode = noConnection, httpStatus = null)))
    }

    @Test fun `a broken file with nothing left to try stops`() {
        assertEquals(Action.Stop, decide(failure(retries = 2, errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, httpStatus = null)))
    }
}
