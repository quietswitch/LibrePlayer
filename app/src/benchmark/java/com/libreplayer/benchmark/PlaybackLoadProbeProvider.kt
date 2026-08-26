package com.libreplayer.benchmark

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.media.service.PlaybackService
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Benchmark-variant-only observation of real MediaSession playback during real library work. */
@UnstableApi
class PlaybackLoadProbeProvider : ContentProvider() {
    private var observer: MediaController? = null
    private var playingMediaId: String? = null
    private val events = PlaybackEvents()

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            events.playerErrors.incrementAndGet()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            events.mediaTransitions.incrementAndGet()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            events.positionDiscontinuities.incrementAndGet()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            events.playbackStateChanges.incrementAndGet()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            events.playWhenReadyChanges.incrementAndGet()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            events.suppressionReasonChanges.incrementAndGet()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            events.isPlayingChanges.incrementAndGet()
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
                METHOD_PREPARE -> preparePlayback(application)
                METHOD_SYNC -> observeLoad(application.appContainer.libraryRepository, false, arg)
                METHOD_REBUILD -> observeLoad(application.appContainer.libraryRepository, true, arg)
                METHOD_STOP -> stopPlayback()
                else -> error("Unsupported playback-load probe method: $method")
            }
        }

    private suspend fun preparePlayback(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        val repository = application.appContainer.libraryRepository
        val song = repository.getAllSongs().singleOrNull { it.fixtureIdentity() == PLAYBACK_FIXTURE_IDENTITY }
            ?: error("Playback fixture is not uniquely present: $PLAYBACK_FIXTURE_IDENTITY")
        check(song.durationMs >= MINIMUM_TRACK_DURATION_MS) {
            "Playback fixture is too short: ${song.durationMs}ms"
        }

        withContext(Dispatchers.Main.immediate) {
            application.appContainer.playbackConnection.playSong(listOf(song), 0)
        }
        awaitActivePlayback(controller, song.id)
        delay(STABILIZATION_MS)
        val snapshot = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
        check(snapshot.isContinuouslyActive(song.id)) { "Playback did not stabilize: $snapshot" }
        check(snapshot.positionMs < song.durationMs - NATURAL_COMPLETION_GUARD_MS) {
            "Playback fixture is too close to natural completion: $snapshot"
        }
        playingMediaId = song.id
        events.reset()
        return Bundle().apply {
            putBoolean(KEY_PREPARED, true)
            putString(KEY_PLAYBACK_FIXTURE_IDENTITY, PLAYBACK_FIXTURE_IDENTITY)
            putLong(KEY_TRACK_DURATION_MS, song.durationMs)
            putSnapshot("before", snapshot)
        }
    }

    private suspend fun observeLoad(
        repository: LibraryRepository,
        rebuild: Boolean,
        inspectedIdentity: String?,
    ): Bundle {
        val controller = requireNotNull(observer)
        check(withContext(Dispatchers.Main.immediate) { controller.isConnected }) {
            "Playback observer is not connected"
        }
        val expectedMediaId = requireNotNull(playingMediaId)
        events.reset()
        val before = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
        check(before.isContinuouslyActive(expectedMediaId)) { "Playback inactive before load: $before" }

        val observationStartedNanos = SystemClock.elapsedRealtimeNanos()
        val synchronizationStartedNanos = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection(TRACE_SECTION)
        try {
            if (rebuild) repository.rebuildLibrary() else repository.rescanLibrary()
        } finally {
            Trace.endSection()
        }
        val synchronizationElapsedNanos = SystemClock.elapsedRealtimeNanos() - synchronizationStartedNanos
        val remainingNanos = OBSERVATION_NANOS -
            (SystemClock.elapsedRealtimeNanos() - observationStartedNanos)
        if (remainingNanos > 0L) {
            delay((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
        }
        val observationElapsedNanos = SystemClock.elapsedRealtimeNanos() - observationStartedNanos
        val after = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
        val eventSnapshot = events.snapshot()
        val catalog = catalogBundle(repository.getAllSongs(), inspectedIdentity)
        val result = Bundle().apply {
            putLong(KEY_SYNCHRONIZATION_ELAPSED_NANOS, synchronizationElapsedNanos)
            putLong(KEY_OBSERVATION_ELAPSED_NANOS, observationElapsedNanos)
            putLong(KEY_POSITION_ADVANCEMENT_MS, after.positionMs - before.positionMs)
            putString(KEY_PLAYBACK_FIXTURE_IDENTITY, PLAYBACK_FIXTURE_IDENTITY)
            putSnapshot("before", before)
            putSnapshot("after", after)
            putEvents(eventSnapshot)
            putAll(catalog)
        }
        Log.i(LOG_TAG, "record=${encodeRecord(result, if (rebuild) METHOD_REBUILD else METHOD_SYNC)}")
        return result
    }

    private suspend fun stopPlayback(): Bundle {
        withContext(Dispatchers.Main.immediate) {
            observer?.stop()
            observer?.removeListener(playerListener)
            observer?.release()
        }
        observer = null
        playingMediaId = null
        events.reset()
        return Bundle().apply { putBoolean(KEY_STOPPED, true) }
    }

    private suspend fun connectedObserver(): MediaController {
        observer?.let { existing ->
            if (withContext(Dispatchers.Main.immediate) { existing.isConnected }) return existing
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    existing.removeListener(playerListener)
                    existing.release()
                }
            }
        }
        val appContext = requireNotNull(context).applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = withContext(Dispatchers.Main.immediate) {
            MediaController.Builder(appContext, token)
                .setListener(controllerListener)
                .buildAsync()
        }
        return future.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS).also { controller ->
            withContext(Dispatchers.Main.immediate) {
                controller.addListener(playerListener)
            }
            observer = controller
        }
    }

    private suspend fun awaitActivePlayback(controller: MediaController, mediaId: String) {
        val deadline = SystemClock.elapsedRealtime() + ACTIVE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val active = withContext(Dispatchers.Main.immediate) {
                playbackSnapshot(controller).isContinuouslyActive(mediaId)
            }
            if (active) return
            delay(ACTIVE_POLL_MS)
        }
        val snapshot = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
        error("Playback did not become active: $snapshot")
    }

    private fun playbackSnapshot(controller: MediaController): PlaybackSnapshot =
        PlaybackSnapshot(
            monotonicNanos = SystemClock.elapsedRealtimeNanos(),
            mediaId = controller.currentMediaItem?.mediaId,
            playbackState = controller.playbackState,
            playWhenReady = controller.playWhenReady,
            isPlaying = controller.isPlaying,
            suppressionReason = controller.playbackSuppressionReason,
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            speed = controller.playbackParameters.speed,
            connected = controller.isConnected && !events.disconnected.get(),
            hasPlayerError = controller.playerError != null,
        )

    private fun PlaybackSnapshot.isContinuouslyActive(expectedMediaId: String): Boolean =
        connected &&
            mediaId == expectedMediaId &&
            playbackState == Player.STATE_READY &&
            playWhenReady &&
            isPlaying &&
            suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            !hasPlayerError

    private fun catalogBundle(allSongs: List<Song>, inspectedIdentity: String?): Bundle {
        val fixtureSongs = allSongs.filter { song ->
            song.sourceType == SongSourceType.MEDIA_STORE && song.fixtureIdentity() != null
        }
        val identities = fixtureSongs.mapNotNull { it.fixtureIdentity() }.sorted()
        val duplicates = identities.groupingBy { it }.eachCount().count { it.value > 1 }
        val inspected = inspectedIdentity?.let { identity ->
            fixtureSongs.singleOrNull { it.fixtureIdentity() == identity }
        }
        return Bundle().apply {
            putInt(KEY_FIXTURE_COUNT, fixtureSongs.size)
            putInt(KEY_UNIQUE_IDENTITIES, identities.distinct().size)
            putInt(KEY_DUPLICATE_IDENTITIES, duplicates)
            putString(KEY_IDENTITY_SHA256, identityFingerprint(identities))
            putBoolean(KEY_INSPECTED_PRESENT, inspected != null)
            putString(KEY_INSPECTED_TITLE, inspected?.title)
        }
    }

    private fun Song.fixtureIdentity(): String? {
        val directory = relativePath?.replace('\\', '/') ?: return null
        if (!directory.startsWith(FIXTURE_RELATIVE_ROOT)) return null
        return directory.removePrefix(FIXTURE_RELATIVE_ROOT) + displayName
    }

    private fun identityFingerprint(identities: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        identities.forEach { identity ->
            digest.update(identity.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun Bundle.putSnapshot(prefix: String, snapshot: PlaybackSnapshot) {
        putLong("${prefix}MonotonicNanos", snapshot.monotonicNanos)
        putString("${prefix}MediaId", snapshot.mediaId)
        putInt("${prefix}PlaybackState", snapshot.playbackState)
        putBoolean("${prefix}PlayWhenReady", snapshot.playWhenReady)
        putBoolean("${prefix}IsPlaying", snapshot.isPlaying)
        putInt("${prefix}SuppressionReason", snapshot.suppressionReason)
        putLong("${prefix}PositionMs", snapshot.positionMs)
        putFloat("${prefix}Speed", snapshot.speed)
        putBoolean("${prefix}Connected", snapshot.connected)
        putBoolean("${prefix}HasPlayerError", snapshot.hasPlayerError)
    }

    private fun Bundle.putEvents(snapshot: PlaybackEventSnapshot) {
        putInt(KEY_PLAYER_ERRORS, snapshot.playerErrors)
        putInt(KEY_MEDIA_TRANSITIONS, snapshot.mediaTransitions)
        putInt(KEY_POSITION_DISCONTINUITIES, snapshot.positionDiscontinuities)
        putInt(KEY_SESSION_DISCONNECTS, snapshot.sessionDisconnects)
        putInt(KEY_PLAYBACK_STATE_CHANGES, snapshot.playbackStateChanges)
        putInt(KEY_PLAY_WHEN_READY_CHANGES, snapshot.playWhenReadyChanges)
        putInt(KEY_SUPPRESSION_REASON_CHANGES, snapshot.suppressionReasonChanges)
        putInt(KEY_IS_PLAYING_CHANGES, snapshot.isPlayingChanges)
    }

    private fun encodeRecord(bundle: Bundle, method: String): String {
        val json = JSONObject().apply {
            put("method", method)
            bundle.keySet().sorted().forEach { key -> put(key, bundle.get(key)) }
        }
        return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
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

    private data class PlaybackSnapshot(
        val monotonicNanos: Long,
        val mediaId: String?,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val isPlaying: Boolean,
        val suppressionReason: Int,
        val positionMs: Long,
        val speed: Float,
        val connected: Boolean,
        val hasPlayerError: Boolean,
    )

    private class PlaybackEvents {
        val playerErrors = AtomicInteger()
        val mediaTransitions = AtomicInteger()
        val positionDiscontinuities = AtomicInteger()
        val sessionDisconnects = AtomicInteger()
        val playbackStateChanges = AtomicInteger()
        val playWhenReadyChanges = AtomicInteger()
        val suppressionReasonChanges = AtomicInteger()
        val isPlayingChanges = AtomicInteger()
        val disconnected = AtomicBoolean()

        fun reset() {
            playerErrors.set(0)
            mediaTransitions.set(0)
            positionDiscontinuities.set(0)
            sessionDisconnects.set(0)
            playbackStateChanges.set(0)
            playWhenReadyChanges.set(0)
            suppressionReasonChanges.set(0)
            isPlayingChanges.set(0)
            disconnected.set(false)
        }

        fun snapshot(): PlaybackEventSnapshot = PlaybackEventSnapshot(
            playerErrors = playerErrors.get(),
            mediaTransitions = mediaTransitions.get(),
            positionDiscontinuities = positionDiscontinuities.get(),
            sessionDisconnects = sessionDisconnects.get(),
            playbackStateChanges = playbackStateChanges.get(),
            playWhenReadyChanges = playWhenReadyChanges.get(),
            suppressionReasonChanges = suppressionReasonChanges.get(),
            isPlayingChanges = isPlayingChanges.get(),
        )
    }

    private data class PlaybackEventSnapshot(
        val playerErrors: Int,
        val mediaTransitions: Int,
        val positionDiscontinuities: Int,
        val sessionDisconnects: Int,
        val playbackStateChanges: Int,
        val playWhenReadyChanges: Int,
        val suppressionReasonChanges: Int,
        val isPlayingChanges: Int,
    )

    companion object {
        const val AUTHORITY = "com.libreplayer.playback-load-probe"
        const val METHOD_PREPARE = "prepare"
        const val METHOD_SYNC = "sync"
        const val METHOD_REBUILD = "rebuild"
        const val METHOD_STOP = "stop"
        const val TRACE_SECTION = "LibrePlayerPlaybackUnderLoad"
        const val LOG_TAG = "LibrePlayerPlaybackLoad"
        const val PLAYBACK_FIXTURE_IDENTITY =
            "audio/artist-00010/album-00010/disc-01/track-00010.mp3"
        const val KEY_PREPARED = "prepared"
        const val KEY_STOPPED = "stopped"
        const val KEY_PLAYBACK_FIXTURE_IDENTITY = "playbackFixtureIdentity"
        const val KEY_TRACK_DURATION_MS = "trackDurationMs"
        const val KEY_SYNCHRONIZATION_ELAPSED_NANOS = "synchronizationElapsedNanos"
        const val KEY_OBSERVATION_ELAPSED_NANOS = "observationElapsedNanos"
        const val KEY_POSITION_ADVANCEMENT_MS = "positionAdvancementMs"
        const val KEY_PLAYER_ERRORS = "playerErrors"
        const val KEY_MEDIA_TRANSITIONS = "mediaTransitions"
        const val KEY_POSITION_DISCONTINUITIES = "positionDiscontinuities"
        const val KEY_SESSION_DISCONNECTS = "sessionDisconnects"
        const val KEY_PLAYBACK_STATE_CHANGES = "playbackStateChanges"
        const val KEY_PLAY_WHEN_READY_CHANGES = "playWhenReadyChanges"
        const val KEY_SUPPRESSION_REASON_CHANGES = "suppressionReasonChanges"
        const val KEY_IS_PLAYING_CHANGES = "isPlayingChanges"
        const val KEY_FIXTURE_COUNT = "fixtureCount"
        const val KEY_UNIQUE_IDENTITIES = "uniqueIdentities"
        const val KEY_DUPLICATE_IDENTITIES = "duplicateIdentities"
        const val KEY_IDENTITY_SHA256 = "identitySha256"
        const val KEY_INSPECTED_PRESENT = "inspectedPresent"
        const val KEY_INSPECTED_TITLE = "inspectedTitle"
        private const val FIXTURE_RELATIVE_ROOT = "Music/LibrePlayerBenchmark/MEDIUM/"
        private const val MINIMUM_TRACK_DURATION_MS = 30_000L
        private const val NATURAL_COMPLETION_GUARD_MS = 5_000L
        private const val OBSERVATION_NANOS = 2_000_000_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val STABILIZATION_MS = 750L
        private const val ACTIVE_TIMEOUT_MS = 15_000L
        private const val ACTIVE_POLL_MS = 50L
        private const val CONTROLLER_TIMEOUT_SECONDS = 15L
    }
}
