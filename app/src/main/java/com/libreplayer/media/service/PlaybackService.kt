package com.libreplayer.media.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.libreplayer.BuildConfig
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.app.MainActivity
import com.libreplayer.media.playback.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container by lazy {
        (application as LibrePlayerApplication).appContainer
    }

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var saveJob: Job? = null
    private var periodicSaveJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (
                events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            ) {
                persistPlaybackState()
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { songId ->
                    serviceScope.launch {
                        container.libraryRepository.markSongPlayed(songId)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val playerBuilder = ExoPlayer.Builder(this)
        if (BuildConfig.DEBUG) {
            playerBuilder.setRenderersFactory(PlaybackAudioDiagnosticRenderersFactory(this))
        }
        player = playerBuilder
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(playerListener)
                if (BuildConfig.DEBUG) {
                    it.addAnalyticsListener(PlaybackAudioDiagnostics)
                }
            }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity())
            .build()
        startPeriodicPersistence()

        serviceScope.launch {
            restorePlaybackState()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        periodicSaveJob?.cancel()
        saveJob?.cancel()
        saveBlocking()
        mediaSession?.release()
        mediaSession = null
        player.removeListener(playerListener)
        if (BuildConfig.DEBUG) {
            player.removeAnalyticsListener(PlaybackAudioDiagnostics)
        }
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun persistPlaybackState() {
        saveJob?.cancel()
        saveJob = serviceScope.launch {
            saveSnapshot(buildSnapshot())
        }
    }

    private suspend fun restorePlaybackState() {
        val snapshot = container.playbackSnapshotStore.snapshot.first()
        if (snapshot.queueIds.isEmpty()) return
        val songs = container.libraryRepository.getSongsByIds(snapshot.queueIds)
        if (songs.isEmpty()) return
        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(song.contentUri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(song.resolvedTitle)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.artwork)
                        .build(),
                )
                .build()
        }
        val restoredSelection = restoredQueueSelection(
            queueIds = snapshot.queueIds,
            savedCurrentIndex = snapshot.currentIndex,
            restoredIds = songs.map { it.id },
            savedPositionMs = snapshot.positionMs,
        )
        player.setMediaItems(
            mediaItems,
            restoredSelection.index,
            restoredSelection.positionMs,
        )
        player.repeatMode = snapshot.repeatMode
        player.shuffleModeEnabled = snapshot.shuffleEnabled
        player.prepare()
        player.playWhenReady = snapshot.playWhenReady
    }

    private fun startPeriodicPersistence() {
        periodicSaveJob?.cancel()
        periodicSaveJob = serviceScope.launch {
            while (true) {
                delay(5_000L)
                if (player.mediaItemCount > 0 && (player.isPlaying || player.playWhenReady || player.currentPosition > 0L)) {
                    saveSnapshot(buildSnapshot())
                }
            }
        }
    }

    private fun buildSnapshot(): PlaybackSnapshot =
        PlaybackSnapshot(
            queueIds = List(player.mediaItemCount) { index ->
                player.getMediaItemAt(index).mediaId
            },
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            repeatMode = player.repeatMode,
            shuffleEnabled = player.shuffleModeEnabled,
            playWhenReady = player.playWhenReady,
        )

    private suspend fun saveSnapshot(snapshot: PlaybackSnapshot) {
        container.playbackSnapshotStore.save(snapshot)
    }

    private fun saveBlocking() {
        if (!::player.isInitialized) return
        runCatching {
            runBlocking {
                saveSnapshot(buildSnapshot())
            }
        }
    }
}

internal data class RestoredQueueSelection(
    val index: Int,
    val positionMs: Long,
)

internal fun restoredQueueSelection(
    queueIds: List<String>,
    savedCurrentIndex: Int,
    restoredIds: List<String>,
    savedPositionMs: Long,
): RestoredQueueSelection {
    if (restoredIds.isEmpty()) {
        return RestoredQueueSelection(index = -1, positionMs = 0L)
    }
    val savedCurrentId = queueIds.getOrNull(savedCurrentIndex)
    val savedOccurrence = savedCurrentId?.let { mediaId ->
        queueIds.take(savedCurrentIndex + 1).count { it == mediaId }
    } ?: 0
    var restoredOccurrence = 0
    val restoredSavedIndex = restoredIds.indexOfFirst { mediaId ->
        if (mediaId != savedCurrentId) {
            false
        } else {
            restoredOccurrence++
            restoredOccurrence == savedOccurrence
        }
    }
    return if (restoredSavedIndex >= 0) {
        RestoredQueueSelection(
            index = restoredSavedIndex,
            positionMs = savedPositionMs.coerceAtLeast(0L),
        )
    } else {
        RestoredQueueSelection(
            index = savedCurrentIndex.coerceIn(0, restoredIds.lastIndex),
            positionMs = 0L,
        )
    }
}
