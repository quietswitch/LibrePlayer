package com.libreplayer.debug

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.media.service.PlaybackService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Debug-only Q2.3 authority over real Media3 playlist transitions. */
@UnstableApi
class TransitionProbeProvider : ContentProvider() {
    private var observer: MediaController? = null
    private val events = TransitionEvents()
    private var backgroundQueueIds: List<String> = emptyList()
    private var initialConnectionSettled = false

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            events.transitions += TransitionEvent(
                elapsedMs = SystemClock.elapsedRealtime(),
                mediaId = mediaItem?.mediaId,
                index = observer?.currentMediaItemIndex ?: C.INDEX_UNSET,
                reason = reason,
            )
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            events.discontinuities += DiscontinuityEvent(
                elapsedMs = SystemClock.elapsedRealtime(),
                oldIndex = oldPosition.mediaItemIndex,
                newIndex = newPosition.mediaItemIndex,
                oldPositionMs = oldPosition.positionMs,
                newPositionMs = newPosition.positionMs,
                reason = reason,
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            events.states += StateEvent(SystemClock.elapsedRealtime(), playbackState)
        }

        override fun onPlayerError(error: PlaybackException) {
            events.playerErrors.incrementAndGet()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            events.sessionDisconnects.incrementAndGet()
            events.disconnected.set(true)
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        runBlocking(Dispatchers.IO) {
            val application = requireNotNull(context).applicationContext as LibrePlayerApplication
            when (method) {
                METHOD_NATURAL_MATRIX -> naturalTransitionMatrix(application)
                METHOD_PREPARE_BACKGROUND -> prepareBackgroundTransition(application)
                METHOD_VERIFY_BACKGROUND -> verifyBackgroundTransition(application)
                METHOD_VERIFY_FOREGROUND -> verifyForegroundProjection()
                METHOD_RECONNECT_BOUNDARY -> reconnectAcrossBoundary(application)
                METHOD_FORMAT_PAIR -> observeFormatPair(application, requireNotNull(arg))
                METHOD_RESET -> resetPlayback()
                else -> error("Unsupported transition probe method: $method")
            }
        }

    private suspend fun naturalTransitionMatrix(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        val connection = application.appContainer.playbackConnection
        val mp3 = transitionSongs("mp3", 3)

        startQueue(connection, controller, mp3, 0, Player.REPEAT_MODE_OFF, shuffle = false)
        events.reset()
        val first = awaitSnapshot(controller, "ordinary A to B") {
            it.currentIndex == 1 && it.isContinuouslyPlaying(mp3[1].id) && it.positionMs < START_TOLERANCE_MS
        }
        checkLastTransition(mp3[1].id, 1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        checkLastDiscontinuity(0, 1, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        val persisted = awaitPersistedSnapshot(application, "ordinary successor") { snapshot ->
            snapshot.queueIds == mp3.map(Song::id) &&
                snapshot.currentIndex == 1 &&
                snapshot.playWhenReady
        }
        check(persisted.positionMs < FIXTURE_DURATION_MS) {
            "Old-item position leaked into persisted successor: $persisted"
        }

        val second = awaitSnapshot(controller, "ordinary B to C") {
            it.currentIndex == 2 && it.isContinuouslyPlaying(mp3[2].id) && it.positionMs < START_TOLERANCE_MS
        }
        checkLastTransition(mp3[2].id, 2, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        checkLastDiscontinuity(1, 2, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        check(events.transitions.count { it.reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO } == 2)
        val secondTransitionMs = requireNotNull(events.transitions.lastOrNull()).elapsedMs

        val ended = awaitSnapshot(controller, "final item ended with repeat off") {
            it.currentIndex == 2 &&
                it.mediaId == mp3[2].id &&
                it.playbackState == Player.STATE_ENDED &&
                !it.isPlaying
        }
        check(ended.mediaIds == mp3.map(Song::id))
        check(events.states.filter { it.state == Player.STATE_ENDED }.all { it.elapsedMs >= secondTransitionMs })
        check(events.playerErrors.get() == 0)
        check(events.sessionDisconnects.get() == 0)

        withContext(Dispatchers.Main.immediate) { connection.togglePlayPause() }
        awaitSnapshot(controller, "play action restarts ended final occurrence") {
            it.currentIndex == 2 &&
                it.mediaId == mp3[2].id &&
                it.isContinuouslyPlaying(mp3[2].id) &&
                it.positionMs < START_TOLERANCE_MS
        }

        withContext(Dispatchers.Main.immediate) {
            connection.playQueue(mp3.take(2), startIndex = 1, positionMs = 0L)
        }
        awaitSnapshot(controller, "replacement after ended state") {
            it.currentIndex == 1 &&
                it.mediaIds == mp3.take(2).map(Song::id) &&
                it.isContinuouslyPlaying(mp3[1].id) &&
                it.positionMs < START_TOLERANCE_MS
        }

        startQueue(connection, controller, mp3.take(2), 0, Player.REPEAT_MODE_OFF, shuffle = false)
        withContext(Dispatchers.Main.immediate) { controller.pause() }
        val paused = awaitSnapshot(controller, "paused before boundary") {
            it.currentIndex == 0 && !it.playWhenReady && !it.isPlaying
        }
        delay(PAUSED_OBSERVATION_MS)
        val stillPaused = withContext(Dispatchers.Main.immediate) { snapshot(controller) }
        check(stillPaused.currentIndex == 0 && stillPaused.mediaId == mp3[0].id)
        check(!stillPaused.playWhenReady && !stillPaused.isPlaying)
        check(kotlin.math.abs(stillPaused.positionMs - paused.positionMs) <= PAUSED_POSITION_TOLERANCE_MS)

        startQueue(connection, controller, mp3.take(2), 0, Player.REPEAT_MODE_ONE, shuffle = false)
        events.reset()
        val repeated = awaitSnapshot(controller, "repeat-one restarted item") {
            it.currentIndex == 0 &&
                it.repeatMode == Player.REPEAT_MODE_ONE &&
                it.isContinuouslyPlaying(mp3[0].id) &&
                it.positionMs < START_TOLERANCE_MS &&
                events.discontinuities.any { event ->
                    event.reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                }
        }
        check(events.transitions.isEmpty()) {
            "Repeat-one changed logical MediaItem identity unexpectedly: ${events.transitionSummary()}"
        }
        check(repeated.mediaIds == mp3.take(2).map(Song::id))
        withContext(Dispatchers.Main.immediate) { connection.skipNext() }
        awaitSnapshot(controller, "repeat-one manual next") {
            it.currentIndex == 1 && it.isContinuouslyPlaying(mp3[1].id) && it.positionMs < START_TOLERANCE_MS
        }
        checkLastTransition(mp3[1].id, 1, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        startQueue(connection, controller, mp3, 2, Player.REPEAT_MODE_ALL, shuffle = false)
        events.reset()
        awaitSnapshot(controller, "repeat-all final wrap") {
            it.currentIndex == 0 && it.isContinuouslyPlaying(mp3[0].id) && it.positionMs < START_TOLERANCE_MS
        }
        checkLastTransition(mp3[0].id, 0, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        startQueue(connection, controller, mp3, 0, Player.REPEAT_MODE_ALL, shuffle = true)
        val authoritativeShuffleNext = withContext(Dispatchers.Main.immediate) {
            controller.nextMediaItemIndex
        }
        check(authoritativeShuffleNext in mp3.indices && authoritativeShuffleNext != 0)
        events.reset()
        awaitSnapshot(controller, "automatic shuffle successor") {
            it.currentIndex == authoritativeShuffleNext &&
                it.isContinuouslyPlaying(mp3[authoritativeShuffleNext].id) &&
                it.positionMs < START_TOLERANCE_MS
        }
        checkLastTransition(
            mp3[authoritativeShuffleNext].id,
            authoritativeShuffleNext,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )

        startQueue(connection, controller, mp3.take(2), 0, Player.REPEAT_MODE_OFF, shuffle = false)
        events.reset()
        val durationMs = withContext(Dispatchers.Main.immediate) { controller.duration }
        check(durationMs > NEAR_END_REMAINING_MS)
        withContext(Dispatchers.Main.immediate) { connection.seekTo(durationMs - NEAR_END_REMAINING_MS) }
        awaitSnapshot(controller, "near-end natural completion") {
            it.currentIndex == 1 && it.isContinuouslyPlaying(mp3[1].id) && it.positionMs < START_TOLERANCE_MS
        }
        check(events.discontinuities.any { it.reason == Player.DISCONTINUITY_REASON_SEEK })
        checkLastTransition(mp3[1].id, 1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        checkLastDiscontinuity(0, 1, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)

        startQueue(connection, controller, mp3.take(2), 0, Player.REPEAT_MODE_OFF, shuffle = false)
        events.reset()
        val endDurationMs = withContext(Dispatchers.Main.immediate) { controller.duration }
        withContext(Dispatchers.Main.immediate) { connection.seekTo(endDurationMs) }
        val seekToEnd = awaitSnapshot(controller, "seek to normalized duration") {
            it.currentIndex == 1 &&
                it.isContinuouslyPlaying(mp3[1].id) &&
                it.positionMs < START_TOLERANCE_MS
        }
        check(events.discontinuities.any { it.reason == Player.DISCONTINUITY_REASON_SEEK })
        checkLastTransition(mp3[1].id, 1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        checkLastDiscontinuity(0, 1, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)

        check(events.playerErrors.get() == 0)
        return Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putString("firstMediaId", first.mediaId)
            putString("secondMediaId", second.mediaId)
            putInt("finalPlaybackState", ended.playbackState)
            putBoolean("finalPlayWhenReady", ended.playWhenReady)
            putString("seekToEndMediaId", seekToEnd.mediaId)
            putInt("seekToEndIndex", seekToEnd.currentIndex)
            putInt("seekToEndPlaybackState", seekToEnd.playbackState)
            putInt("seekToEndTransitionReason", events.transitions.lastOrNull()?.reason ?: Int.MIN_VALUE)
            putInt("seekToEndDiscontinuityReason", events.discontinuities.lastOrNull()?.reason ?: Int.MIN_VALUE)
            putInt("shuffleExpectedIndex", authoritativeShuffleNext)
            putString("transitionEvents", events.transitionSummary())
            putString("discontinuityEvents", events.discontinuitySummary())
        }
    }

    private suspend fun prepareBackgroundTransition(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        val queue = transitionSongs("mp3", 2)
        startQueue(
            application.appContainer.playbackConnection,
            controller,
            queue,
            0,
            Player.REPEAT_MODE_OFF,
            shuffle = false,
        )
        backgroundQueueIds = queue.map(Song::id)
        events.reset()
        return Bundle().apply { putBoolean(KEY_PASSED, true) }
    }

    private suspend fun verifyBackgroundTransition(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        check(backgroundQueueIds.size == 2)
        val transitioned = awaitSnapshot(controller, "background natural transition") {
            it.currentIndex == 1 &&
                it.mediaId == backgroundQueueIds[1] &&
                it.isContinuouslyPlaying(backgroundQueueIds[1]) &&
                it.positionMs < START_TOLERANCE_MS
        }
        checkLastTransition(backgroundQueueIds[1], 1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        val persisted = awaitPersistedSnapshot(application, "background successor") { snapshot ->
            snapshot.queueIds == backgroundQueueIds && snapshot.currentIndex == 1
        }
        check(persisted.positionMs < FIXTURE_DURATION_MS) {
            "Old-item position leaked into persisted background successor: $persisted"
        }
        return Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putString("mediaId", transitioned.mediaId)
            putLong("positionMs", transitioned.positionMs)
            putLong("persistedPositionMs", persisted.positionMs)
        }
    }

    private suspend fun verifyForegroundProjection(): Bundle {
        val controller = connectedObserver()
        check(backgroundQueueIds.size == 2)
        val snapshot = awaitSnapshot(controller, "foreground projection after background transition") {
            it.currentIndex == 1 &&
                it.mediaId == backgroundQueueIds[1] &&
                it.connected &&
                !it.hasPlayerError
        }
        return Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putString("mediaId", snapshot.mediaId)
            putInt("currentIndex", snapshot.currentIndex)
        }
    }

    private suspend fun reconnectAcrossBoundary(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        val queue = transitionSongs("mp3", 2)
        startQueue(
            application.appContainer.playbackConnection,
            controller,
            queue,
            0,
            Player.REPEAT_MODE_OFF,
            shuffle = false,
        )
        delay(RECONNECT_RELEASE_DELAY_MS)
        withContext(Dispatchers.Main.immediate) {
            observer?.removeListener(playerListener)
            observer?.release()
            observer = null
        }
        delay(RECONNECT_GAP_MS)
        val reconnected = connectedObserver()
        val snapshot = awaitSnapshot(reconnected, "controller reconnect after boundary") {
            it.currentIndex == 1 &&
                it.mediaId == queue[1].id &&
                it.isContinuouslyPlaying(queue[1].id) &&
                it.positionMs < RECONNECT_POSITION_TOLERANCE_MS
        }
        return Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putString("mediaId", snapshot.mediaId)
            putInt("currentIndex", snapshot.currentIndex)
            putLong("positionMs", snapshot.positionMs)
        }
    }

    private suspend fun observeFormatPair(
        application: LibrePlayerApplication,
        format: String,
    ): Bundle {
        require(format in SUPPORTED_FORMATS) { "Unsupported transition format: $format" }
        val controller = connectedObserver()
        val queue = transitionSongs(format, 2)
        startQueue(
            application.appContainer.playbackConnection,
            controller,
            queue,
            0,
            Player.REPEAT_MODE_OFF,
            shuffle = false,
        )
        events.reset()
        val transitioned = awaitSnapshot(controller, "$format same-format transition") {
            it.currentIndex == 1 &&
                it.isContinuouslyPlaying(queue[1].id) &&
                it.positionMs < START_TOLERANCE_MS
        }
        checkLastTransition(queue[1].id, 1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        checkLastDiscontinuity(0, 1, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        check(events.playerErrors.get() == 0)
        check(events.sessionDisconnects.get() == 0)
        check(events.states.none { it.state == Player.STATE_ENDED })
        return Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putString("format", format)
            putString("mediaId", transitioned.mediaId)
            putInt("transitionReason", events.transitions.last().reason)
            putInt("discontinuityReason", events.discontinuities.last().reason)
            putString("stateEvents", events.stateSummary())
        }
    }

    private suspend fun startQueue(
        connection: com.libreplayer.media.playback.PlaybackConnection,
        controller: MediaController,
        queue: List<Song>,
        startIndex: Int,
        repeatMode: Int,
        shuffle: Boolean,
    ) {
        withContext(Dispatchers.Main.immediate) {
            controller.repeatMode = repeatMode
            controller.shuffleModeEnabled = shuffle
            connection.playQueue(queue, startIndex = startIndex, positionMs = 0L)
        }
        awaitSnapshot(controller, "queue start") {
            it.currentIndex == startIndex &&
                it.mediaId == queue[startIndex].id &&
                it.mediaIds == queue.map(Song::id) &&
                it.isContinuouslyPlaying(queue[startIndex].id) &&
                it.positionMs < START_TOLERANCE_MS
        }
    }

    private fun transitionSongs(format: String, count: Int): List<Song> =
        (1..count).map { part ->
            Song(
                id = "q2.3:$format:$part",
                sourceType = SongSourceType.DOCUMENT,
                contentUri = "asset:///q2_3/transition-$format-$part.$format",
                title = "Q2.3 $format transition $part",
                artist = "Synthetic fixture",
                album = "Q2.3 transition authority",
                durationMs = FIXTURE_DURATION_MS,
                trackNumber = part,
                discNumber = 1,
                year = null,
                dateAddedEpochSeconds = 0L,
                dateModifiedEpochSeconds = 0L,
                displayName = "transition-$format-$part.$format",
                relativePath = null,
                mimeType = null,
                artworkUri = null,
                isFavorite = false,
            )
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
            events.disconnected.set(false)
        }
        if (!initialConnectionSettled) {
            delay(SERVICE_RESTORE_SETTLE_MS)
            initialConnectionSettled = true
        }
        return controller
    }

    private suspend fun awaitSnapshot(
        controller: MediaController,
        description: String,
        predicate: (TransitionSnapshot) -> Boolean,
    ): TransitionSnapshot {
        val deadline = SystemClock.elapsedRealtime() + TRANSITION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = withContext(Dispatchers.Main.immediate) { snapshot(controller) }
            if (predicate(snapshot)) return snapshot
            delay(POLL_MS)
        }
        error("Timed out waiting for $description: ${withContext(Dispatchers.Main.immediate) { snapshot(controller) }}")
    }

    private suspend fun awaitPersistedSnapshot(
        application: LibrePlayerApplication,
        description: String,
        predicate: (com.libreplayer.media.playback.PlaybackSnapshot) -> Boolean,
    ): com.libreplayer.media.playback.PlaybackSnapshot {
        val deadline = SystemClock.elapsedRealtime() + PERSISTENCE_TIMEOUT_MS
        var latest = application.appContainer.playbackSnapshotStore.snapshot.first()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = application.appContainer.playbackSnapshotStore.snapshot.first()
            if (predicate(latest)) return latest
            delay(PERSISTENCE_POLL_MS)
        }
        error("Timed out waiting for persisted $description: $latest")
    }

    private fun snapshot(controller: MediaController): TransitionSnapshot =
        TransitionSnapshot(
            mediaId = controller.currentMediaItem?.mediaId,
            currentIndex = controller.currentMediaItemIndex,
            mediaIds = List(controller.mediaItemCount) { controller.getMediaItemAt(it).mediaId },
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            playbackState = controller.playbackState,
            playWhenReady = controller.playWhenReady,
            isPlaying = controller.isPlaying,
            suppressionReason = controller.playbackSuppressionReason,
            repeatMode = controller.repeatMode,
            connected = controller.isConnected && !events.disconnected.get(),
            hasPlayerError = controller.playerError != null,
        )

    private fun TransitionSnapshot.isContinuouslyPlaying(expectedMediaId: String): Boolean =
        connected &&
            mediaId == expectedMediaId &&
            playbackState == Player.STATE_READY &&
            playWhenReady &&
            isPlaying &&
            suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            !hasPlayerError

    private fun checkLastTransition(mediaId: String, index: Int, reason: Int) {
        val event = requireNotNull(events.transitions.lastOrNull()) { "No transition event recorded" }
        check(event.mediaId == mediaId && event.index == index && event.reason == reason) {
            "Unexpected transition: $event"
        }
    }

    private fun checkLastDiscontinuity(oldIndex: Int, newIndex: Int, reason: Int) {
        val event = requireNotNull(events.discontinuities.lastOrNull()) { "No discontinuity recorded" }
        check(event.oldIndex == oldIndex && event.newIndex == newIndex && event.reason == reason) {
            "Unexpected discontinuity: $event"
        }
    }

    private suspend fun resetPlayback(): Bundle {
        withContext(Dispatchers.Main.immediate) {
            observer?.stop()
            observer?.clearMediaItems()
            observer?.removeListener(playerListener)
            observer?.release()
        }
        observer = null
        backgroundQueueIds = emptyList()
        events.reset()
        return Bundle().apply { putBoolean(KEY_PASSED, true) }
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

    private data class TransitionSnapshot(
        val mediaId: String?,
        val currentIndex: Int,
        val mediaIds: List<String>,
        val positionMs: Long,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val isPlaying: Boolean,
        val suppressionReason: Int,
        val repeatMode: Int,
        val connected: Boolean,
        val hasPlayerError: Boolean,
    )

    private data class TransitionEvent(
        val elapsedMs: Long,
        val mediaId: String?,
        val index: Int,
        val reason: Int,
    )

    private data class DiscontinuityEvent(
        val elapsedMs: Long,
        val oldIndex: Int,
        val newIndex: Int,
        val oldPositionMs: Long,
        val newPositionMs: Long,
        val reason: Int,
    )

    private data class StateEvent(val elapsedMs: Long, val state: Int)

    private class TransitionEvents {
        val transitions = CopyOnWriteArrayList<TransitionEvent>()
        val discontinuities = CopyOnWriteArrayList<DiscontinuityEvent>()
        val states = CopyOnWriteArrayList<StateEvent>()
        val playerErrors = AtomicInteger()
        val sessionDisconnects = AtomicInteger()
        val disconnected = AtomicBoolean()

        fun reset() {
            transitions.clear()
            discontinuities.clear()
            states.clear()
            playerErrors.set(0)
            sessionDisconnects.set(0)
            disconnected.set(false)
        }

        fun transitionSummary(): String = transitions.joinToString("|") {
            "${it.mediaId},${it.index},${it.reason},${it.elapsedMs}"
        }

        fun discontinuitySummary(): String = discontinuities.joinToString("|") {
            "${it.oldIndex}:${it.oldPositionMs}->${it.newIndex}:${it.newPositionMs},${it.reason},${it.elapsedMs}"
        }

        fun stateSummary(): String = states.joinToString("|") { "${it.state},${it.elapsedMs}" }
    }

    companion object {
        const val AUTHORITY = "com.libreplayer.transition-probe"
        const val METHOD_NATURAL_MATRIX = "natural-matrix"
        const val METHOD_PREPARE_BACKGROUND = "prepare-background"
        const val METHOD_VERIFY_BACKGROUND = "verify-background"
        const val METHOD_VERIFY_FOREGROUND = "verify-foreground"
        const val METHOD_RECONNECT_BOUNDARY = "reconnect-boundary"
        const val METHOD_FORMAT_PAIR = "format-pair"
        const val METHOD_RESET = "reset"
        const val KEY_PASSED = "passed"
        private val SUPPORTED_FORMATS = setOf("wav", "flac", "mp3", "m4a")
        private const val FIXTURE_DURATION_MS = 2_000L
        private const val START_TOLERANCE_MS = 900L
        private const val NEAR_END_REMAINING_MS = 400L
        private const val PAUSED_OBSERVATION_MS = 2_500L
        private const val PAUSED_POSITION_TOLERANCE_MS = 100L
        private const val RECONNECT_RELEASE_DELAY_MS = 1_400L
        private const val RECONNECT_GAP_MS = 900L
        private const val RECONNECT_POSITION_TOLERANCE_MS = 1_300L
        private const val TRANSITION_TIMEOUT_MS = 8_000L
        private const val PERSISTENCE_TIMEOUT_MS = 5_000L
        private const val PERSISTENCE_POLL_MS = 50L
        private const val POLL_MS = 20L
        private const val CONTROLLER_TIMEOUT_SECONDS = 10L
        private const val SERVICE_RESTORE_SETTLE_MS = 500L
    }
}
