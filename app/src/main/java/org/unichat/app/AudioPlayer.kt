package org.unichat.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.PowerManager

object AudioPlayer {
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var player: MediaPlayer? = null

    var currentPath: String? = null
        private set
    var currentChatId: String = ""
        private set
    /**
     * Message the current clip belongs to. The file path cannot identify it:
     * Telegram serves one file for every copy of the same voice note, so a note
     * forwarded twice into a chat gives several rows the SAME path.
     */
    var currentMsgId: String = ""
        private set
    var earpiece: Boolean = false
        private set

    @Volatile var speed: Float = 1f
        private set

    var proximityNear: Boolean = false

    @Volatile var proximitySessionEnded: Boolean = false
        private set

    var onStateChanged: (() -> Unit)? = null        // chat screen UI
    var onServiceStateChanged: (() -> Unit)? = null  // service (notification + proximity)
    var onCompleted: ((String, String, String) -> Unit)? = null
    var onPlayStarted: ((String, String, String) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    val isPlaying: Boolean get() = player?.isPlaying == true
    val hasCurrent: Boolean get() = player != null
    val positionMs: Int get() = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    val durationMs: Int get() = try { player?.duration ?: 0 } catch (e: Exception) { 0 }

    fun playPause(path: String, chatId: String, msgId: String) {
        val p = player
        if (p != null && currentMsgId == msgId && currentChatId == chatId) {
            if (p.isPlaying) pause() else resume()
            return
        }
        play(path, chatId, msgId)
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
        msgId: String,
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
                val finishedMsg = currentMsgId
                stopInternal(resetRoute = false)
                notifyState()
                if (finishedPath != null) onCompleted?.invoke(finishedPath, finishedChat, finishedMsg)
            }
            if (startMs > 0) p.seekTo(startMs)
            requestFocus(commMode)
            p.start()
            applySpeed(p)
            player = p
            fresh = null // ownership transferred; stopInternal releases it now
            currentPath = path
            currentChatId = chatId
            currentMsgId = msgId
            earpiece = useEarpiece
            proximitySessionEnded = false
            try { onPlayStarted?.invoke(path, chatId, msgId) } catch (e: Exception) {}
        } catch (e: Exception) {
            android.util.Log.w("AudioPlayer", "play failed for $path", e)
            try { fresh?.release() } catch (e2: Exception) {}
            stopInternal(resetRoute = true)
        } finally {
            routing = false
        }
        notifyState()
    }

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
        if (earpiece != proximityNear) {
            val path = currentPath ?: return
            play(path, currentChatId, currentMsgId, p.currentPosition)
        } else {
            // re-requested, not assumed: a permanent loss (another app took over
            // the audio) is what paused this clip in the first place
            requestFocus(earpiece && ownsAudioMode)
            p.start()
            applySpeed(p)
            notifyState()
        }
    }

    fun switchToEarpiece(rewindMs: Int) {
        val p = player ?: return
        val path = currentPath ?: return
        val chatId = currentChatId
        val pos = (p.currentPosition - rewindMs).coerceAtLeast(0)
        play(path, chatId, currentMsgId, pos, useEarpiece = true)
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

    private fun requestFocus(commMode: Boolean) {
        val am = audioManager ?: return
        // The request is reused while the route is unchanged: every clip of a
        // chain asks for focus, and abandoning in between would let the
        // interrupted app resume for the gap between two voice notes.
        val held = focusRequest
        val req = if (held != null && focusComm == commMode) held else {
            abandonFocus()
            AudioFocusRequest.Builder(
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
        currentMsgId = ""
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
