package com.libreplayer.media.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.media.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackConnection(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(PlaybackUiState(isLoading = true))
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var cachedQueue: List<Song> = emptyList()
    private var positionTickerJob: Job? = null
    private var currentPlaybackErrorMessage: String? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                currentPlaybackErrorMessage = null
            }
            if (
                events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                scope.launch {
                    refreshQueue()
                }
            } else {
                refreshUiState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            currentPlaybackErrorMessage = playbackErrorMessage(error.errorCode)
            controller?.pause()
            refreshUiState()
        }
    }

    init {
        connect()
    }

    fun connect() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync().also { future ->
            future.addListener(
                {
                    val builtController = runCatching { future.get() }.getOrNull()
                    if (builtController == null) {
                        controllerFuture = null
                        _uiState.value = PlaybackUiState()
                        return@addListener
                    }
                    controller = builtController
                    controllerFuture = null
                    builtController.addListener(playerListener)
                    startPositionTicker()
                    scope.launch {
                        refreshQueue()
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    suspend fun playSong(queue: List<Song>, startIndex: Int) {
        val activeController = controller ?: awaitController() ?: return
        currentPlaybackErrorMessage = null
        cachedQueue = queue
        activeController.setMediaItems(queue.map(Song::toMediaItem), startIndex, 0L)
        activeController.prepare()
        activeController.playWhenReady = true
        activeController.play()
        refreshUiState()
    }

    suspend fun playQueue(queue: List<Song>, startIndex: Int = 0, positionMs: Long = 0L) {
        val activeController = controller ?: awaitController() ?: return
        currentPlaybackErrorMessage = null
        cachedQueue = queue
        activeController.setMediaItems(queue.map(Song::toMediaItem), startIndex, positionMs)
        activeController.prepare()
        activeController.playWhenReady = true
        activeController.play()
        refreshUiState()
    }

    suspend fun addToQueue(song: Song) {
        val activeController = controller ?: awaitController() ?: return
        activeController.addMediaItem(song.toMediaItem())
        refreshQueue()
    }

    fun togglePlayPause() {
        controller?.let { activeController ->
            if (activeController.isPlaying) {
                activeController.pause()
                refreshUiState()
                return
            }

            if (currentPlaybackErrorMessage != null) {
                scope.launch {
                    retryCurrentItemFromLibrary(activeController)
                }
                return
            }

            activeController.play()
            refreshUiState()
        }
    }

    private suspend fun retryCurrentItemFromLibrary(activeController: MediaController) {
        val currentIndex = activeController.currentMediaItemIndex
        val currentMediaId = activeController.currentMediaItem?.mediaId.orEmpty()

        if (currentIndex < 0 || currentMediaId.isBlank()) {
            refreshUiState()
            return
        }

        val staleSong = cachedQueue.getOrNull(currentIndex)
        val refreshedSong = libraryRepository.getSongById(currentMediaId)
            ?: staleSong?.let { stale ->
                findUniqueRediscoveredSong(
                    staleSong = stale,
                    currentSongs = libraryRepository.getAllSongs(),
                )
            }

        if (refreshedSong == null) {
            currentPlaybackErrorMessage = "This track file is no longer available."
            refreshUiState()
            return
        }

        activeController.replaceMediaItem(currentIndex, refreshedSong.toMediaItem())
        cachedQueue = cachedQueue.toMutableList().also { queue ->
            if (currentIndex in queue.indices) {
                queue[currentIndex] = refreshedSong
            }
        }

        currentPlaybackErrorMessage = null
        activeController.prepare()
        activeController.seekTo(currentIndex, 0L)
        activeController.playWhenReady = true
        activeController.play()
        refreshUiState()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        refreshUiState()
    }

    fun skipNext() {
        controller?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        controller?.let { activeController ->
            if (activeController.currentPosition > 5_000L) {
                activeController.seekTo(0L)
            } else {
                activeController.seekToPreviousMediaItem()
            }
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
        refreshUiState()
    }

    fun cycleRepeatMode() {
        val activeController = controller ?: return
        activeController.repeatMode = when (activeController.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        refreshUiState()
    }

    fun findLoadedSong(songId: String): Song? {
        val currentState = uiState.value
        return currentState.currentSong?.takeIf { it.id == songId }
            ?: currentState.queue.firstOrNull { it.id == songId }
    }

    private fun refreshUiState() {
        val activeController = controller
        if (activeController == null) {
            _uiState.value = PlaybackUiState()
            return
        }
        val currentSong = cachedQueue.getOrNull(activeController.currentMediaItemIndex)
        _uiState.value = PlaybackUiState(
            isConnected = true,
            isLoading = activeController.playbackState == Player.STATE_BUFFERING,
            isPlaying = activeController.isPlaying,
            currentSong = currentSong,
            queue = cachedQueue,
            currentIndex = activeController.currentMediaItemIndex,
            positionMs = activeController.currentPosition.coerceAtLeast(0L),
            durationMs = activeController.duration.takeIf { it > 0L } ?: currentSong?.durationMs ?: 0L,
            bufferedPositionMs = activeController.bufferedPosition,
            repeatMode = activeController.repeatMode,
            shuffleEnabled = activeController.shuffleModeEnabled,
            playWhenReady = activeController.playWhenReady,
            errorMessage = currentPlaybackErrorMessage,
        )
    }

    private suspend fun refreshQueue() {
        val activeController = controller ?: return
        val mediaItems = List(activeController.mediaItemCount) { index ->
            activeController.getMediaItemAt(index)
        }
        val songsById = libraryRepository.getSongsByIds(mediaItems.map(MediaItem::mediaId)).associateBy { it.id }
        cachedQueue = mediaItems.map { mediaItem ->
            songsById[mediaItem.mediaId] ?: mediaItem.asFallbackSong()
        }
        refreshUiState()
    }

    private suspend fun awaitController(): MediaController? {
        connect()
        return controller ?: controllerFuture?.await()
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            while (true) {
                delay(500L)
                if (controller != null) {
                    refreshUiState()
                }
            }
        }
    }
}

private fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(contentUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(resolvedTitle)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artwork)
                .build(),
        )
        .build()

private fun MediaItem.asFallbackSong(): Song {
    val metadata = mediaMetadata
    val title = metadata.title?.toString()
    val artist = metadata.artist?.toString()
    val album = metadata.albumTitle?.toString()
    return Song(
        id = mediaId,
        sourceType = if (mediaId.startsWith("media:")) SongSourceType.MEDIA_STORE else SongSourceType.DOCUMENT,
        contentUri = localConfiguration?.uri?.toString().orEmpty(),
        title = title,
        artist = artist,
        album = album,
        durationMs = 0L,
        trackNumber = null,
        discNumber = null,
        year = null,
        dateAddedEpochSeconds = 0L,
        dateModifiedEpochSeconds = 0L,
        displayName = title ?: mediaId,
        relativePath = null,
        mimeType = null,
        artworkUri = metadata.artworkUri?.toString(),
        isFavorite = false,
    )
}

internal fun findUniqueRediscoveredSong(
    staleSong: Song,
    currentSongs: List<Song>,
): Song? {
    val matches = currentSongs.filter { candidate ->
        candidate.id != staleSong.id &&
            candidate.sourceType == staleSong.sourceType &&
            candidate.displayName.equals(staleSong.displayName, ignoreCase = true) &&
            kotlin.math.abs(candidate.durationMs - staleSong.durationMs) <= 1_000L &&
            candidate.resolvedTitle.equals(staleSong.resolvedTitle, ignoreCase = true) &&
            candidate.resolvedArtist.equals(staleSong.resolvedArtist, ignoreCase = true) &&
            candidate.resolvedAlbum.equals(staleSong.resolvedAlbum, ignoreCase = true)
    }

    return matches.singleOrNull()
}

private suspend fun ListenableFuture<MediaController>.await(): MediaController? =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                continuation.resume(runCatching { get() }.getOrNull())
            },
            java.util.concurrent.Executor { command -> command.run() },
        )
        continuation.invokeOnCancellation { cancel(true) }
    }

internal fun playbackErrorMessage(errorCode: Int): String =
    when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "This track file is no longer available."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "LibrePlayer no longer has permission to read this track."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This track uses an unsupported audio format."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "This track appears to be malformed."
        else ->
            "Unable to play this track."
    }
