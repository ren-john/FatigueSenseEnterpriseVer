package com.example.fatiguesense.enterprise.ml

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.fatiguesense.enterprise.R

/** One looping phone alarm per monitoring session, serialized on the main thread. */
internal class PhoneEarAlarm(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var player: MediaPlayer? = null
    private var alertActive = false
    private var focusRequested = false
    @Volatile private var closed = false

    /** Read on the main thread, including native playback checks on a device. */
    internal val isPlaying: Boolean get() = player?.isPlaying == true

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onMain {
            if (!closed && alertActive) {
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        try { player?.start() } catch (e: Exception) { playbackFailed(e) }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> releasePlayer()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        try { player?.pause() } catch (e: Exception) { playbackFailed(e) }
                    }
                }
            }
        }
    }
    private val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .setWillPauseWhenDucked(true)
            .build()
    } else null

    fun setAlertActive(active: Boolean) = onMain {
        if (closed || active == alertActive) return@onMain
        alertActive = active
        if (!active) {
            releasePlayer()
            return@onMain
        }
        try {
            @Suppress("DEPRECATION")
            val focus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(checkNotNull(focusRequest))
            } else {
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
            if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Phone EAR alarm could not obtain audio focus")
                return@onMain
            }
            focusRequested = true
            // create() prepares the small bundled clip and returns a ready player.
            val candidate = checkNotNull(MediaPlayer.create(appContext, R.raw.ndrrmc_alert_1,
                attributes, 0)) { "Unable to prepare phone EAR alarm" }
            player = candidate
            candidate.isLooping = true
            candidate.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Phone EAR alarm playback failed: $what/$extra")
                releasePlayer()
                true
            }
            candidate.start()
        } catch (e: Exception) {
            playbackFailed(e)
        }
    }

    private fun playbackFailed(error: Exception) {
        Log.e(TAG, "Phone EAR alarm unavailable", error)
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        if (focusRequested) {
            focusRequested = false
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(checkNotNull(focusRequest))
            } else {
                audioManager.abandonAudioFocus(focusListener)
            }
        }
    }

    override fun close() {
        // Prevent any queued alert update from restarting playback after Stop.
        closed = true
        onMain {
            alertActive = false
            releasePlayer()
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    private companion object { const val TAG = "PhoneEarAlarm" }
}
