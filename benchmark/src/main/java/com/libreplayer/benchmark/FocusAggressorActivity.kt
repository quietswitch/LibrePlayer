package com.libreplayer.benchmark

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Test-APK-only top activity used to satisfy Android 15+ audio-focus request rules. */
class FocusAggressorActivity : Activity() {
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val focusChanges = CopyOnWriteArrayList<Int>()
    private var activeRequest: AudioFocusRequest? = null
    private var handledToken: String? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        focusChanges += change
        FocusAggressorRegistry.recordFocusChange(change)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Q2.4 focus aggressor"
    }

    override fun onResume() {
        super.onResume()
        dispatchCurrentIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchCurrentIntent()
    }

    override fun onDestroy() {
        activeRequest?.let(audioManager::abandonAudioFocusRequest)
        activeRequest = null
        super.onDestroy()
    }

    private fun dispatchCurrentIntent() {
        val commandIntent = intent ?: return
        val token = commandIntent.getStringExtra(EXTRA_TOKEN) ?: return
        if (handledToken == token) return
        handledToken = token
        window.decorView.post {
            when (commandIntent.action) {
                ACTION_REQUEST -> requestFocus(
                    token = token,
                    gain = commandIntent.getIntExtra(EXTRA_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT),
                )
                ACTION_ABANDON -> abandonFocus(token)
            }
        }
    }

    private fun requestFocus(token: String, gain: Int) {
        activeRequest?.let(audioManager::abandonAudioFocusRequest)
        focusChanges.clear()
        FocusAggressorRegistry.begin(token, gain)
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()
        activeRequest = request
        val result = audioManager.requestAudioFocus(request)
        FocusAggressorRegistry.recordRequestResult(token, gain, result)
    }

    private fun abandonFocus(token: String) {
        val result = activeRequest?.let(audioManager::abandonAudioFocusRequest)
            ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        activeRequest = null
        FocusAggressorRegistry.recordAbandonResult(token, result)
        finish()
    }

    companion object {
        const val ACTION_REQUEST = "com.libreplayer.benchmark.action.REQUEST_AUDIO_FOCUS"
        const val ACTION_ABANDON = "com.libreplayer.benchmark.action.ABANDON_AUDIO_FOCUS"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_GAIN = "gain"
    }
}

internal object FocusAggressorRegistry {
    data class Snapshot(
        val token: String = "",
        val gain: Int = 0,
        val requestResult: Int = Int.MIN_VALUE,
        val abandonResult: Int = Int.MIN_VALUE,
        val focusChanges: List<Int> = emptyList(),
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    @Synchronized
    fun begin(token: String, gain: Int) {
        snapshot = Snapshot(token = token, gain = gain)
    }

    @Synchronized
    fun recordRequestResult(token: String, gain: Int, result: Int) {
        snapshot = snapshot.copy(token = token, gain = gain, requestResult = result)
    }

    @Synchronized
    fun recordAbandonResult(token: String, result: Int) {
        snapshot = snapshot.copy(token = token, abandonResult = result)
    }

    @Synchronized
    fun recordFocusChange(change: Int) {
        snapshot = snapshot.copy(focusChanges = snapshot.focusChanges + change)
    }
}
