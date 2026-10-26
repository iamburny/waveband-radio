package com.iamburny.waveband.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Media audio focus for the radio. Other players, navigation prompts and calls use focus to take
 * the speakers; head units also use it to switch their own sources (the built-in FM radio
 * gives the speakers back when another app gains focus).
 *
 * [onChanged] is called on [looper] for each focus change from the system while [held]; its
 * argument is true when another app took focus for good.
 */
class AudioFocus(context: Context, looper: Looper, private val onChanged: (lostForGood: Boolean) -> Unit) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(looper)
    private val changeListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A change posted before an abandon must not apply to a later request.
        if (!held) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                lentOut = false
                ducked = false
            }
            // Gone for good: the system expects us to give the request up.
            AudioManager.AUDIOFOCUS_LOSS -> abandon()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> lentOut = true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> ducked = true
        }
        onChanged(change == AudioManager.AUDIOFOCUS_LOSS)
    }

    // On Android 8+ the system lowers our volume itself for prompts that allow ducking, and only
    // calls the listener when it cannot.
    private val request: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(changeListener, handler)
            .build()
    } else {
        null
    }

    /** Whether our request is granted (including while it is [lentOut] or [ducked]). */
    var held = false
        private set

    /** Another app (a call, a prompt) has the speakers for a while; stay silent until it is back. */
    var lentOut = false
        private set

    /** Another app plays briefly over us and the system won't lower our volume itself. */
    var ducked = false
        private set

    /**
     * Requests focus; returns false if another app (such as an ongoing call) refuses it. Asking
     * again while [held] changes nothing: a loss in progress still has to end with a gain.
     */
    fun request(): Boolean {
        if (held) return true
        val result = if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(changeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun abandon() {
        lentOut = false
        ducked = false
        if (!held) return
        held = false
        if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(changeListener)
        }
    }
}
