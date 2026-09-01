package com.libreplayer.debug

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.app.MainActivity
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.media.service.PlaybackService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Debug-only Q2.5 observation and control through a real external MediaController. */
@UnstableApi
class SystemControlProbeProvider : ContentProvider() {
    private var observer: MediaController? = null
    private var initialConnectionSettled = false
    private val playerErrors = AtomicInteger()
    private val sessionDisconnects = AtomicInteger()
    private val disconnected = AtomicBoolean()
    private val deliberateControllerRelease = AtomicBoolean()
    private val activityCreates = AtomicInteger()
    private val activityDestroys = AtomicInteger()
    private val activityResumes = AtomicInteger()
    private val lastActivityIdentity = AtomicInteger()

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            playerErrors.incrementAndGet()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (!deliberateControllerRelease.get()) {
                sessionDisconnects.incrementAndGet()
                disconnected.set(true)
            }
        }
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) {
                activityCreates.incrementAndGet()
                lastActivityIdentity.set(System.identityHashCode(activity))
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity is MainActivity) activityDestroys.incrementAndGet()
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity) activityResumes.incrementAndGet()
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)
            ?.registerActivityLifecycleCallbacks(activityCallbacks)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        runBlocking(Dispatchers.IO) {
            val application = requireNotNull(context).applicationContext as LibrePlayerApplication
            when (method) {
                METHOD_PREPARE_LONG -> prepareQueue(application, LONG_QUEUE, startIndex = 1)
                METHOD_PREPARE_SHORT -> prepareQueue(application, TRANSITION_QUEUE, startIndex = 0)
                METHOD_PREPARE_ENDED -> prepareEnded(application)
                METHOD_SNAPSHOT -> snapshotBundle(application, connectedObserver())
                METHOD_EXTERNAL_PLAY -> command(application) { play() }
                METHOD_EXTERNAL_PAUSE -> command(application) { pause() }
                METHOD_EXTERNAL_NEXT -> command(application) { seekToNext() }
                METHOD_EXTERNAL_PREVIOUS -> command(application) { seekToPrevious() }
                METHOD_EXTERNAL_SEEK -> command(application) {
                    seekTo(requireNotNull(arg).toLong())
                }
                METHOD_RELEASE_CONTROLLER -> releaseObserver()
                METHOD_RECONNECT -> snapshotBundle(application, connectedObserver())
                METHOD_RESET_ACTIVITY -> resetActivityCounters()
                METHOD_ACTIVITY -> activityBundle()
                METHOD_RESET -> resetPlayback()
                else -> error("Unsupported system-control probe method: $method")
            }
        }

    private suspend fun prepareQueue(
        application: LibrePlayerApplication,
        queue: List<Song>,
        startIndex: Int,
    ): Bundle {
        val controller = connectedObserver()
        playerErrors.set(0)
        sessionDisconnects.set(0)
        disconnected.set(false)
        withContext(Dispatchers.Main.immediate) {
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
            application.appContainer.playbackConnection.playQueue(
                queue = queue,
                startIndex = startIndex,
                positionMs = 0L,
            )
        }
        val expectedId = queue[startIndex].id
        val prepared = awaitSnapshot(application, controller, "active $expectedId") {
            it.mediaId == expectedId && it.isPlaying && it.playWhenReady
        }
        return prepared.toBundle(playerErrors.get(), sessionDisconnects.get())
    }

    private suspend fun prepareEnded(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        withContext(Dispatchers.Main.immediate) {
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
            application.appContainer.playbackConnection.playQueue(
                queue = ENDED_QUEUE,
                startIndex = ENDED_QUEUE.lastIndex,
                positionMs = 0L,
            )
        }
        val ended = awaitSnapshot(application, controller, "final ended state") {
            it.mediaId == ENDED_QUEUE.last().id && it.playbackState == Player.STATE_ENDED
        }
        return ended.toBundle(playerErrors.get(), sessionDisconnects.get())
    }

    private suspend fun command(
        application: LibrePlayerApplication,
        action: MediaController.() -> Unit,
    ): Bundle {
        val controller = connectedObserver()
        withContext(Dispatchers.Main.immediate) { controller.action() }
        delay(COMMAND_SETTLE_MS)
        return snapshotBundle(application, controller)
    }

    private suspend fun releaseObserver(): Bundle {
        deliberateControllerRelease.set(true)
        withContext(Dispatchers.Main.immediate) {
            observer?.removeListener(playerListener)
            observer?.release()
            observer = null
        }
        deliberateControllerRelease.set(false)
        return Bundle().apply { putBoolean(KEY_PASSED, true) }
    }

    private suspend fun resetPlayback(): Bundle {
        deliberateControllerRelease.set(true)
        withContext(Dispatchers.Main.immediate) {
            observer?.stop()
            observer?.clearMediaItems()
            observer?.removeListener(playerListener)
            observer?.release()
            observer = null
        }
        deliberateControllerRelease.set(false)
        playerErrors.set(0)
        sessionDisconnects.set(0)
        disconnected.set(false)
        return Bundle().apply { putBoolean(KEY_PASSED, true) }
    }

    private fun resetActivityCounters(): Bundle {
        activityCreates.set(0)
        activityDestroys.set(0)
        activityResumes.set(0)
        lastActivityIdentity.set(0)
        return activityBundle()
    }

    private fun activityBundle(): Bundle = Bundle().apply {
        putBoolean(KEY_PASSED, true)
        putInt("activityCreates", activityCreates.get())
        putInt("activityDestroys", activityDestroys.get())
        putInt("activityResumes", activityResumes.get())
        putInt("lastActivityIdentity", lastActivityIdentity.get())
    }

    private suspend fun connectedObserver(): MediaController {
        observer?.let { existing ->
            if (withContext(Dispatchers.Main.immediate) { existing.isConnected }) return existing
        }
        val appContext = requireNotNull(context).applicationContext
        val future = withContext(Dispatchers.Main.immediate) {
            MediaController.Builder(
                appContext,
                SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
            ).setListener(controllerListener).buildAsync()
        }
        val controller = future.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS).also { controller ->
            withContext(Dispatchers.Main.immediate) { controller.addListener(playerListener) }
            observer = controller
            disconnected.set(false)
        }
        if (!initialConnectionSettled) {
            delay(SERVICE_RESTORE_SETTLE_MS)
            initialConnectionSettled = true
        }
        return controller
    }

    private suspend fun awaitSnapshot(
        application: LibrePlayerApplication,
        controller: MediaController,
        description: String,
        predicate: (SystemSnapshot) -> Boolean,
    ): SystemSnapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = withContext(Dispatchers.Main.immediate) {
                snapshot(application, controller)
            }
            if (predicate(snapshot)) return snapshot
            delay(POLL_MS)
        }
        error(
            "Timed out waiting for $description: " +
                withContext(Dispatchers.Main.immediate) { snapshot(application, controller) },
        )
    }

    private suspend fun snapshotBundle(
        application: LibrePlayerApplication,
        controller: MediaController,
    ): Bundle = withContext(Dispatchers.Main.immediate) {
        snapshot(application, controller).toBundle(playerErrors.get(), sessionDisconnects.get())
    }

    private fun snapshot(
        application: LibrePlayerApplication,
        controller: MediaController,
    ): SystemSnapshot {
        val uiState = application.appContainer.playbackConnection.uiState.value
        return SystemSnapshot(
            mediaId = controller.currentMediaItem?.mediaId,
            title = controller.mediaMetadata.title?.toString(),
            currentIndex = controller.currentMediaItemIndex,
            mediaIds = List(controller.mediaItemCount) { index ->
                controller.getMediaItemAt(index).mediaId
            },
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            durationMs = controller.duration.coerceAtLeast(0L),
            playbackState = controller.playbackState,
            playWhenReady = controller.playWhenReady,
            suppressionReason = controller.playbackSuppressionReason,
            isPlaying = controller.isPlaying,
            repeatMode = controller.repeatMode,
            shuffleEnabled = controller.shuffleModeEnabled,
            connected = controller.isConnected && !disconnected.get(),
            hasPlayerError = controller.playerError != null,
            commandPlayPause = controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
            commandSeek = controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            commandNext = controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT),
            commandPrevious = controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS),
            commandRepeat = controller.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE),
            commandShuffle = controller.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE),
            sessionCommandCount = controller.availableSessionCommands.commands.size,
            uiState = uiState,
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private data class SystemSnapshot(
        val mediaId: String?,
        val title: String?,
        val currentIndex: Int,
        val mediaIds: List<String>,
        val positionMs: Long,
        val durationMs: Long,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val suppressionReason: Int,
        val isPlaying: Boolean,
        val repeatMode: Int,
        val shuffleEnabled: Boolean,
        val connected: Boolean,
        val hasPlayerError: Boolean,
        val commandPlayPause: Boolean,
        val commandSeek: Boolean,
        val commandNext: Boolean,
        val commandPrevious: Boolean,
        val commandRepeat: Boolean,
        val commandShuffle: Boolean,
        val sessionCommandCount: Int,
        val uiState: PlaybackUiState,
    ) {
        fun toBundle(playerErrors: Int, sessionDisconnects: Int): Bundle = Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putString("mediaId", mediaId)
            putString("title", title)
            putInt("currentIndex", currentIndex)
            putStringArrayList("mediaIds", ArrayList(mediaIds))
            putLong("positionMs", positionMs)
            putLong("durationMs", durationMs)
            putInt("playbackState", playbackState)
            putBoolean("playWhenReady", playWhenReady)
            putInt("suppressionReason", suppressionReason)
            putBoolean("isPlaying", isPlaying)
            putInt("repeatMode", repeatMode)
            putBoolean("shuffleEnabled", shuffleEnabled)
            putBoolean("connected", connected)
            putBoolean("hasPlayerError", hasPlayerError)
            putInt("playerErrors", playerErrors)
            putInt("sessionDisconnects", sessionDisconnects)
            putBoolean("commandPlayPause", commandPlayPause)
            putBoolean("commandSeek", commandSeek)
            putBoolean("commandNext", commandNext)
            putBoolean("commandPrevious", commandPrevious)
            putBoolean("commandRepeat", commandRepeat)
            putBoolean("commandShuffle", commandShuffle)
            putInt("sessionCommandCount", sessionCommandCount)
            putBoolean("uiConnected", uiState.isConnected)
            putString("uiMediaId", uiState.currentSong?.id)
            putInt("uiCurrentIndex", uiState.currentIndex)
            putLong("uiPositionMs", uiState.positionMs)
            putBoolean("uiPlayWhenReady", uiState.playWhenReady)
            putBoolean("uiIsPlaying", uiState.isPlaying)
            putInt("uiPlaybackState", uiState.playbackState)
            putInt("uiSuppressionReason", uiState.playbackSuppressionReason)
            putBoolean("uiPrimaryControlShowsPause", uiState.primaryControlShowsPause)
            putInt("uiRepeatMode", uiState.repeatMode)
            putBoolean("uiShuffleEnabled", uiState.shuffleEnabled)
        }
    }

    companion object {
        const val AUTHORITY = "com.libreplayer.system-control-probe"
        const val METHOD_PREPARE_LONG = "prepare-long"
        const val METHOD_PREPARE_SHORT = "prepare-short"
        const val METHOD_PREPARE_ENDED = "prepare-ended"
        const val METHOD_SNAPSHOT = "snapshot"
        const val METHOD_EXTERNAL_PLAY = "external-play"
        const val METHOD_EXTERNAL_PAUSE = "external-pause"
        const val METHOD_EXTERNAL_NEXT = "external-next"
        const val METHOD_EXTERNAL_PREVIOUS = "external-previous"
        const val METHOD_EXTERNAL_SEEK = "external-seek"
        const val METHOD_RELEASE_CONTROLLER = "release-controller"
        const val METHOD_RECONNECT = "reconnect"
        const val METHOD_RESET_ACTIVITY = "reset-activity"
        const val METHOD_ACTIVITY = "activity"
        const val METHOD_RESET = "reset"
        const val KEY_PASSED = "passed"
        private const val CONTROLLER_TIMEOUT_SECONDS = 10L
        private const val STATE_TIMEOUT_MS = 8_000L
        private const val POLL_MS = 20L
        private const val COMMAND_SETTLE_MS = 150L
        private const val SERVICE_RESTORE_SETTLE_MS = 500L

        private val LONG_QUEUE = (1..3).map { part ->
            syntheticSong(
                id = "q2.5:long:$part",
                uri = "asset:///q2_4/audio-focus.flac",
                title = "Q2.5 long $part",
                durationMs = 20_000L,
                mimeType = "audio/flac",
            )
        }
        private val ENDED_QUEUE = (1..2).map { part ->
            syntheticSong(
                id = "q2.5:short:$part",
                uri = "asset:///q2_3/transition-mp3-$part.mp3",
                title = "Q2.5 short $part",
                durationMs = 2_000L,
                mimeType = "audio/mpeg",
            )
        }
        private val TRANSITION_QUEUE = listOf(
            ENDED_QUEUE.first(),
            syntheticSong(
                id = "q2.5:short:2",
                uri = "asset:///q2_4/audio-focus.flac",
                title = "Q2.5 short 2",
                durationMs = 20_000L,
                mimeType = "audio/flac",
            ),
        )

        private fun syntheticSong(
            id: String,
            uri: String,
            title: String,
            durationMs: Long,
            mimeType: String,
        ): Song = Song(
            id = id,
            sourceType = SongSourceType.DOCUMENT,
            contentUri = uri,
            title = title,
            artist = "Synthetic fixture",
            album = "Q2.5 system-control authority",
            durationMs = durationMs,
            trackNumber = id.substringAfterLast(':').toInt(),
            discNumber = 1,
            year = null,
            dateAddedEpochSeconds = 0L,
            dateModifiedEpochSeconds = 0L,
            displayName = uri.substringAfterLast('/'),
            relativePath = null,
            mimeType = mimeType,
            artworkUri = null,
            isFavorite = false,
        )
    }
}
