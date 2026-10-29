package app.polyphony.ui.settings.components

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.polyphony.R

/** "5 minutes ago" style text, or "Never" for missing timestamps. */
@Composable
fun relativeTime(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0L) return stringResource(R.string.settings_never)
    return DateUtils.getRelativeTimeSpanString(
        epochMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}
