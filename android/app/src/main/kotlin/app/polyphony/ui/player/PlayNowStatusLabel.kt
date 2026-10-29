package app.polyphony.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.polyphony.R
import app.polyphony.core.domain.model.PlayNowStream

/** A Play now song still on its way, in a few words; null once it's ready (continuo#12). */
@Composable
fun playNowStatusLabel(stream: PlayNowStream?): String? = when (stream?.status) {
    null, "ready" -> null
    "queued" -> stringResource(R.string.playnow_status_queued)
    "searching" -> stringResource(R.string.playnow_status_searching)
    "downloading", "streaming" -> stringResource(R.string.playnow_status_downloading, stream.percent.toInt())
    "failed" -> stringResource(R.string.playnow_status_failed)
    else -> null
}
