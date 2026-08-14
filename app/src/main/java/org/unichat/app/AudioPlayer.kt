package org.unichat.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.PowerManager

/**
 * Plays one voice message at a time, independent of any activity: playback
 * survives screen lock and chaining to the next voice message is driven by
 * Bridge, not the chat screen.
 *
 * Supports two output routes: the loudspeaker (default) and the earpiece
 * (for privacy when the phone is held to the ear). Route changes recreate the
 * player, which is what makes the "rewind 1s on ear" transition possible.
 */
object AudioPlayer {
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var player: MediaPlayer? = null

    var currentPath: String? = null
        private set
    var currentChatId: String = ""
        private set
    var earpiece: Boolean = false
        private set

    // Playback speed, cycled by the UI: 1x → 1.5x → 2x → 1x. Applied to the
    // current clip live and to every clip that follows (it is global, like
    // WhatsApp's speed toggle).
    @Volatile var speed: Float = 1f
        private set

    // Live proximity state, updated by the service's sensor listener. Manual
    // play/resume route by this so the earpiece is used only when the phone is
    // actually at the ear right now (not because a previous clip was).
    var proximityNear: Boolean = false

    // True after a paused message was reset to the start: the user is done
    // with it, so the proximity sensor must not act on it anymore. Starting
    // or resuming playback re-arms the session.
    @Volatile var proximitySessionEnded: Boolean = false
        private set

    var onStateChanged: (() -> Unit)? = null        // chat screen UI
    var onServiceStateChanged: (() -> Unit)? = null  // service (notification + proximity)
    var onCompleted: ((String, String) -> Unit)? = null
    var onPlayStarted: ((String, String) -> Unit)? = null // (path, chatId) when playback begins

    fun init(context: Context) {
        appContext = context.applicationContext
        audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    val isPlaying: Boolean get() = player?.isPlaying == true
    val hasCurrent: Boolean get() = player != null
    val positionMs: Int get() = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    val durationMs: Int get() = try { player?.duration ?: 0 } catch (e: Exception) { 0 }

    /** Play a new file, or toggle pause/resume when already on this file. */
    fun playPause(path: String, chatId: String) {
        val p = player
        if (p != null && currentPath == path) {
            if (p.isPlaying) pause() else resume()
            return
        }
        play(path, chatId)
    }

    /**
     * Ear playback uses the communication/telephony path, the same one the
     * official clients use: it reliably reaches the earpiece, and — the reason
     * it is not merely a fallback — it puts playback on the voice-call stream,
     * which is the stream the hardware volume keys drive while the screen is
     * blanked against your face. Routing media to the earpiece with
     * setPreferredDevice kept the clip on the music stream, where the keys had
     * nothing to act on. The cost is a fixed 1x rate at the ear; the speaker
     * route still honours the speed pill.
     */
    fun play(
        path: String,
        chatId: String,
        startMs: Int = 0,
        useEarpiece: Boolean = proximityNear,
    ) {
        routing = true
        stopInternal(resetRoute = false)
        val commMode = useEarpiece
        // Held outside the try so a failure part-way through setup still
        // releases the native player: `player` is null here (stopInternal above
        // cleared it), so the catch's stopInternal cannot reach this instance.
        var fresh: MediaPlayer? = null
        try {
            val p = MediaPlayer()
            fresh = p
            appContext?.let { p.setWakeMode(it, PowerManager.PARTIAL_WAKE_LOCK) }
            applyRoute(commMode)
            p.setAudioAttributes(buildAttributes(commMode))
            p.setDataSource(path)
            p.prepare()
            p.setOnErrorListener { _, what, extra ->
                // Without a listener the framework reports runtime errors (a
                // decode failure, a file truncated mid-playback, a dead media
                // server) to the completion listener instead, so a broken clip
                // was indistinguishable from a finished one and silently
                // auto-advanced the voice chain. Returning true keeps that from
                // happening; playback of this clip is over either way.
                android.util.Log.w("AudioPlayer", "playback error what=$what extra=$extra for $path")
                stopInternal(resetRoute = true)
                notifyState()
                true
            }
            p.setOnCompletionListener {
                val finishedPath = currentPath
                val finishedChat = currentChatId
                // keep route for a possible immediate chain to the next voice
                stopInternal(resetRoute = false)
                notifyState()
                if (finishedPath != null) onCompleted?.invoke(finishedPath, finishedChat)
            }
            if (startMs > 0) p.seekTo(startMs)
            requestFocus(commMode)
            p.start()
            applySpeed(p)
            player = p
            fresh = null // ownership transferred; stopInternal releases it now
            currentPath = path
            currentChatId = chatId
            earpiece = useEarpiece
            proximitySessionEnded = false
            try { onPlayStarted?.invoke(path, chatId) } catch (e: Exception) {}
        } catch (e: Exception) {
            // e.g. the file was deleted or truncated: setDataSource/prepare throw
            android.util.Log.w("AudioPlayer", "play failed for $path", e)
            try { fresh?.release() } catch (e2: Exception) {}
            stopInternal(resetRoute = true)
        } finally {
            routing = false
        }
        notifyState()
    }

    /** Advances the playback speed (1x → 1.5x → 2x → 1x); returns the new speed. */
    fun cycleSpeed(): Float {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        // Only a playing player accepts new PlaybackParams (setting them on a
        // paused one starts playback on many devices); a speed picked while
        // paused is applied by resume() instead, so the pill never lies.
        player?.let { if (it.isPlaying) applySpeed(it) }
        notifyState()
        return speed
    }

    private fun applySpeed(p: MediaPlayer) {
        // Build a fresh PlaybackParams with only the speed field set (pitch
        // stays 1.0 so voices don't turn chipmunky). Reading p.playbackParams
        // first — as the getter — throws on a freshly start()ed player on many
        // devices; the exception was swallowed, silently dropping the speed and
        // resetting playback to 1x whenever a clip was recreated (e.g. the
        // proximity switch to earpiece).
        try { p.playbackParams = PlaybackParams().setSpeed(speed) } catch (e: Exception) {}
    }

    fun seekTo(ms: Int) {
        try { player?.seekTo(ms) } catch (e: Exception) {}
        // dragging a PAUSED message back to the start means "done with it":
        // its proximity session ends (a playing message dragged to the start
        // is just a replay and stays eligible)
        if (ms == 0 && player != null && !isPlaying && !proximitySessionEnded) {
            proximitySessionEnded = true
            notifyState()
        }
    }

    /**
     * [userInitiated] false is the focus listener pausing us because something
     * else took the audio — that must KEEP the request, since regaining focus is
     * what resumes the clip. A real pause gives the focus back instead, so the
     * music or podcast this voice note interrupted is not left silent for as
     * long as the clip sits paused.
     */
    fun pause(userInitiated: Boolean = true) {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            if (userInitiated) abandonFocus()
            notifyState()
        }
    }

    fun resume() {
        val p = player ?: return
        if (p.isPlaying) return
        proximitySessionEnded = false
        // route by the phone's current position, recreating on the right output
        // if it no longer matches (e.g. paused at ear, resumed with phone away)
        if (earpiece != proximityNear) {
            val path = currentPath ?: return
            play(path, currentChatId, p.currentPosition)
        } else {
            // re-requested, not assumed: a permanent loss (another app took over
            // the audio) is what paused this clip in the first place
            requestFocus(earpiece && ownsAudioMode)
            p.start()
            // pick up a speed the user chose while this clip was paused
            applySpeed(p)
            notifyState()
        }
    }

    // --- proximity-driven transitions --------------------------------------

    /** Bring-to-ear while playing: switch to earpiece and rewind. */
    fun switchToEarpiece(rewindMs: Int) {
        val p = player ?: return
        val path = currentPath ?: return
        val chatId = currentChatId
        val pos = (p.currentPosition - rewindMs).coerceAtLeast(0)
        play(path, chatId, pos, useEarpiece = true)
    }

    fun stop() {
        stopInternal(resetRoute = true)
        notifyState()
    }

    // True only while WE hold the device in MODE_IN_COMMUNICATION. The mode is
    // global: resetting it unconditionally meant finishing a voice note pulled
    // any other app's ongoing call (or VoIP session) back to MODE_NORMAL, and
    // starting one on the media route did the same before it even played.
    private var ownsAudioMode = false

    /** Called by the chain logic when no further voice message follows. */
    fun resetRoute() {
        earpiece = false
        releaseAudioMode()
        // Focus follows the route: it is deliberately kept across a chain of
        // voice messages (stopInternal(resetRoute = false)), so whatever was
        // playing before doesn't resume for the gap between two clips.
        abandonFocus()
    }

    /**
     * The stream the hardware volume keys must control while this player owns
     * the audio: the media stream normally, the call stream on the earpiece
     * fallback (which plays through the telephony path).
     */
    val volumeStream: Int
        get() = if (ownsAudioMode) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    // --- audio focus --------------------------------------------------------

    private var focusRequest: AudioFocusRequest? = null
    private var focusComm = false

    // A clip paused because something more important took focus resumes by
    // itself once that is over — but only for a transient loss. A permanent one
    // means the user moved to another audio app, where a voice note starting
    // again on its own would be a surprise.
    private var resumeOnFocusGain = false

    // Set while play() tears the player down and rebuilds it on another output
    // (the proximity switch to the earpiece does exactly this). The rebuild
    // abandons and re-takes focus, and any loss reported across that window is
    // our own doing — acting on it would pause the clip the user just put to
    // their ear.
    @Volatile private var routing = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (routing) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause(userInitiated = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocusGain = isPlaying
                pause(userInitiated = false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                resume()
            }
        }
    }

    /**
     * Takes focus for one clip, so a voice note interrupts music or a podcast
     * instead of playing on top of it — and so this player is told to get out of
     * the way of a call or an alarm. Transient: playback is short and what was
     * interrupted is expected to come back afterwards.
     */
    private fun requestFocus(commMode: Boolean) {
        val am = audioManager ?: return
        // The request is reused while the route is unchanged: every clip of a
        // chain asks for focus, and abandoning in between would let the
        // interrupted app resume for the gap between two voice notes.
        val held = focusRequest
        val req = if (held != null && focusComm == commMode) held else {
            abandonFocus()
            AudioFocusRequest.Builder(
                // at the ear on the telephony route nothing else may be audible
                if (commMode) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
                .setAudioAttributes(buildAttributes(commMode))
                // ducked speech is speech the user has to replay, so ask to be
                // paused instead of turned down
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it; focusComm = commMode }
        }
        // A refusal is not treated as a failure to play: it is rare (something
        // holds focus exclusively), the user just tapped play, and refusing
        // would leave a dead button with nothing on screen to explain it.
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            android.util.Log.w("AudioPlayer", "audio focus denied")
        }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        resumeOnFocusGain = false
        audioManager?.abandonAudioFocusRequest(req)
    }

    /** Undoes the communication mode, but only when this player established it. */
    private fun releaseAudioMode() {
        if (!ownsAudioMode) return
        ownsAudioMode = false
        audioManager?.let {
            it.mode = AudioManager.MODE_NORMAL
            it.isSpeakerphoneOn = false
        }
    }

    private fun stopInternal(resetRoute: Boolean) {
        player?.release()
        player = null
        currentPath = null
        currentChatId = ""
        if (resetRoute) resetRoute()
    }

    private fun applyRoute(commMode: Boolean) {
        val am = audioManager ?: return
        // The default (media) route selects the earpiece per-player via
        // setPreferredDevice, staying on the media pipeline that honors playback
        // speed. Communication mode is only the fallback for devices that won't
        // route media to the earpiece — and it is the only case that touches the
        // global audio mode, so the media route just releases a mode we own
        // rather than forcing MODE_NORMAL on whatever owns it now.
        if (commMode) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsAudioMode = true
            am.isSpeakerphoneOn = false
        } else {
            releaseAudioMode()
        }
    }

    private fun buildAttributes(commMode: Boolean): AudioAttributes {
        // Communication mode uses the voice-call usage (voice volume stream);
        // the media route uses USAGE_MEDIA so tempo changes take effect.
        val b = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        b.setUsage(if (commMode) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        return b.build()
    }

    /**
     * Re-runs the service-side state evaluation (notification + proximity
     * gate) without a playback change — e.g. when the foreground chat
     * changes, which affects whether the proximity sensor may act.
     */
    fun refreshServiceState() {
        try { onServiceStateChanged?.invoke() } catch (e: Exception) {
            android.util.Log.e("AudioPlayer", "onServiceStateChanged listener threw", e)
        }
    }

    private fun notifyState() {
        // A throwing listener is a bug in that listener, not a reason to
        // silently detach it: dropping the chat-screen hook here used to freeze
        // the play/pause icon and seekbar for the rest of the screen's life,
        // with nothing logged.
        try { onStateChanged?.invoke() } catch (e: Exception) {
            android.util.Log.e("AudioPlayer", "onStateChanged listener threw", e)
        }
        try { onServiceStateChanged?.invoke() } catch (e: Exception) {
            android.util.Log.e("AudioPlayer", "onServiceStateChanged listener threw", e)
        }
    }
}
