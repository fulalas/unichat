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

    var onStateChanged: (() -> Unit)? = null
    var onServiceStateChanged: (() -> Unit)? = null
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
                if (finishedPath != null) {
                    try { onCompleted?.invoke(finishedPath, finishedChat, finishedMsg) } catch (e: Exception) {
                        android.util.Log.e("AudioPlayer", "onCompleted listener threw", e)
                    }
                }
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
        // Reading p.playbackParams first — as the getter — throws on a freshly
        // start()ed player on many devices; the exception was swallowed,
        // silently dropping the speed and resetting playback to 1x whenever a
        // clip was recreated (e.g. the proximity switch to earpiece).
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

    val volumeStream: Int
        get() = if (ownsAudioMode) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    private var focusRequest: AudioFocusRequest? = null
    private var focusComm = false

    // A clip paused because something more important took focus resumes by
    // itself once that is over — but only for a transient loss. A permanent one
    // means the user moved to another audio app, where a voice note starting
    // again on its own would be a surprise.
    private var resumeOnFocusGain = false

    // A listener of its own per focus request, and only the current generation
    // acts. play() rebuilding the player on another output (the proximity switch
    // to the earpiece) abandons the focus request and takes a new one, and the
    // loss the framework then reports lands on the abandoned request's listener,
    // after play() has already returned — acting on it paused the clip the user
    // had just put to their ear. Suppressing it by a flag cleared on the next
    // main-loop turn instead swallowed genuine losses delivered in that turn,
    // leaving the clip playing over whatever had taken the audio.
    private var focusGen = 0

    private fun newFocusListener(): AudioManager.OnAudioFocusChangeListener {
        val gen = ++focusGen
        return AudioManager.OnAudioFocusChangeListener { change ->
            if (gen != focusGen) return@OnAudioFocusChangeListener
            onFocusChange(change)
        }
    }

    private fun onFocusChange(change: Int) {
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
                .setOnAudioFocusChangeListener(newFocusListener())
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
        focusGen++
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
        // Ear playback always takes this path (see play): only the media
        // pipeline honors playback speed, so speaker playback must not use it,
        // and it is the only case that touches the global audio mode.
        if (commMode) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsAudioMode = true
            am.isSpeakerphoneOn = false
        } else {
            releaseAudioMode()
        }
    }

    private fun buildAttributes(commMode: Boolean): AudioAttributes {
        // The media route needs USAGE_MEDIA for tempo changes to take effect.
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
