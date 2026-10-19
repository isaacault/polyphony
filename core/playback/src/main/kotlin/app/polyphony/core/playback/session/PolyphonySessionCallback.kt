package app.polyphony.core.playback.session

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.playback.queue.MediaItemFactory
import app.polyphony.core.playback.queue.QueueStore
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/** Hooks the callback needs from the service. */
interface SessionActions {
    fun toggleShuffle()
    fun cycleRepeat()
    fun toggleFavorite()
    fun restoreQuality()
    fun refreshDecision()
    fun setSleepTimer(endsAtEpochMs: Long?, finishTrack: Boolean)
    fun setQueueTitle(title: String?)
    fun audioSessionId(): Int
    suspend fun restoreQueue(): MediaSession.MediaItemsWithStartPosition?
    fun buttons(): List<CommandButton>
}

class PolyphonySessionCallback(
    private val scope: CoroutineScope,
    private val queueStore: QueueStore,
    private val libraryRepository: LibraryRepository,
    private val actions: SessionActions,
) : MediaLibrarySession.Callback {

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .apply { CustomCommands.ALL.forEach(::add) }
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(sessionCommands)
            .setMediaButtonPreferences(actions.buttons())
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            CustomCommands.TOGGLE_SHUFFLE -> actions.toggleShuffle()
            CustomCommands.CYCLE_REPEAT -> actions.cycleRepeat()
            CustomCommands.TOGGLE_FAVORITE -> actions.toggleFavorite()
            CustomCommands.RESTORE_QUALITY -> actions.restoreQuality()
            CustomCommands.REFRESH_DECISION -> actions.refreshDecision()
            CustomCommands.SET_SLEEP_TIMER -> actions.setSleepTimer(
                args.getLong(CustomCommands.ARG_ENDS_AT, -1L).takeIf { it > 0 },
                args.getBoolean(CustomCommands.ARG_FINISH_TRACK, false),
            )
            CustomCommands.SET_QUEUE_TITLE -> actions.setQueueTitle(args.getString(CustomCommands.ARG_TITLE))
            CustomCommands.GET_AUDIO_SESSION_ID -> {
                val extras = Bundle().apply { putInt(CustomCommands.ARG_AUDIO_SESSION_ID, actions.audioSessionId()) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
            }
            else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future { rebuild(mediaItems) }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        MediaSession.MediaItemsWithStartPosition(rebuild(mediaItems), startIndex, startPositionMs)
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        actions.restoreQueue() ?: MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
    }

    private suspend fun rebuild(items: List<MediaItem>): List<MediaItem> = items.mapNotNull { item ->
        val cached = queueStore.get(item.mediaId) ?: MediaItemFactory.trackFrom(item)
        val track = cached ?: resolveFromLibrary(item.mediaId)
        if (track == null) {
            if (MediaItemFactory.needsRebuild(item)) null else item
        } else {
            queueStore.put(track)
            if (MediaItemFactory.needsRebuild(item) || cached == null) MediaItemFactory.fromTrack(track) else item
        }
    }

    private suspend fun resolveFromLibrary(mediaId: String): Track? {
        return when {
            mediaId.startsWith(PREFIX_ALBUM) || mediaId.startsWith(PREFIX_PLAYLIST) || mediaId == ROOT -> null
            else -> runCatching { libraryRepository.getTrack(mediaId) }.getOrNull()
        }
    }

    // ---- Browse tree for Android Auto / Bluetooth / Wear ----

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Polyphony", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params))

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val track = queueStore.get(mediaId) ?: runCatching { libraryRepository.getTrack(mediaId) }.getOrNull()
        if (track != null) LibraryResult.ofItem(MediaItemFactory.fromTrack(track), null)
        else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val children: List<MediaItem> = runCatching {
            when {
                parentId == ROOT -> listOf(
                    folder(FOLDER_RECENT, "Recently added", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                    folder(FOLDER_FAVORITES, "Favourites", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                    folder(FOLDER_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                )
                parentId == FOLDER_RECENT -> libraryRepository.recentlyAddedAlbums(50).first().map(::albumItem)
                parentId == FOLDER_FAVORITES -> libraryRepository.starredTracks().first().also(queueStore::putAll).map { MediaItemFactory.fromTrack(it) }
                parentId == FOLDER_PLAYLISTS -> libraryRepository.playlists().first().map(::playlistItem)
                parentId.startsWith(PREFIX_ALBUM) -> libraryRepository.tracksForAlbum(parentId.removePrefix(PREFIX_ALBUM)).first()
                    .also(queueStore::putAll).map { MediaItemFactory.fromTrack(it) }
                parentId.startsWith(PREFIX_PLAYLIST) -> libraryRepository.tracksForPlaylist(parentId.removePrefix(PREFIX_PLAYLIST)).first()
                    .also(queueStore::putAll).map { MediaItemFactory.fromTrack(it) }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        val from = (page * pageSize).coerceAtMost(children.size)
        val to = if (pageSize <= 0) children.size else (from + pageSize).coerceAtMost(children.size)
        LibraryResult.ofItemList(children.subList(from, to), params)
    }

    private fun folder(id: String, title: String, mediaType: Int): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(mediaType).build(),
        ).build()

    private fun albumItem(album: Album): MediaItem = MediaItem.Builder()
        .setMediaId(PREFIX_ALBUM + album.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(album.name)
                .setArtist(album.artistName)
                .setArtworkUri(album.artworkUrl?.let(android.net.Uri::parse))
                .setIsBrowsable(true).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .build(),
        ).build()

    private fun playlistItem(playlist: Playlist): MediaItem = MediaItem.Builder()
        .setMediaId(PREFIX_PLAYLIST + playlist.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(playlist.name)
                .setArtworkUri(playlist.artworkUrl?.let(android.net.Uri::parse))
                .setIsBrowsable(true).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        ).build()

    companion object {
        const val ROOT = "root"
        const val FOLDER_RECENT = "folder:recent"
        const val FOLDER_FAVORITES = "folder:favorites"
        const val FOLDER_PLAYLISTS = "folder:playlists"
        const val PREFIX_ALBUM = "album:"
        const val PREFIX_PLAYLIST = "playlist:"
    }
}
