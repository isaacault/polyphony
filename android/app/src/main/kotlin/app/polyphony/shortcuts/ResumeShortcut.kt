package app.polyphony.shortcuts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.polyphony.R
import app.polyphony.core.domain.model.RecentContext
import app.polyphony.core.domain.repository.ListeningHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A launcher shortcut for the most recent collection, "Resume Parachutes", beside the static
 * ones (res/xml/shortcuts.xml). It opens `polyphony://resume`. Pushed only when that collection
 * changes, not on every play, so the launcher's rate limit for background updates is never hit.
 */
@Singleton
class ResumeShortcut @Inject constructor(
    @ApplicationContext private val context: Context,
    private val history: ListeningHistoryRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            history.recent(1)
                .map { it.firstOrNull() }
                .distinctUntilChangedBy { it?.context?.key to it?.context?.title }
                .collect { runCatching { update(it) } }
        }
    }

    private fun update(latest: RecentContext?) {
        if (latest == null) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(ID))
            return
        }
        val title = latest.context.title.ifBlank { return }
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(title.take(SHORT_LABEL_MAX))
            .setLongLabel(context.getString(R.string.shortcut_resume_long, title))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut_player))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("polyphony://resume")).setPackage(context.packageName))
            .setRank(0)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    private companion object {
        const val ID = "resume"
        const val SHORT_LABEL_MAX = 25
    }
}
