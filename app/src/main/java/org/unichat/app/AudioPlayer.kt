package org.unichat.app

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

@OptIn(UnstableApi::class)
object AudioPlayer {
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var player: ExoPlayer? = null

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

    /**
     * True from the first clip of a chain until the chain is over — including
     * the gap where one clip has finished and the next is still being looked
     * up. The player is null for that gap, so reading `hasCurrent` as "playback
     * over" released the proximity wake lock and the ear route between two
     * voice notes: the screen lit up against the user's face and the next clip
     * started on the speaker. Ended by [endSession], which every path that
     * stops playback for good already goes through.
     */
    @Volatile var sessionActive: Boolean = false
        private set

    /** Survives the gap, unlike [currentChatId], which the player owns. */
    @Volatile var sessionChatId: String = ""
        private set

    var onStateChanged: (() -> Unit)? = null
    var onServiceStateChanged: (() -> Unit)? = null
    var onCompleted: ((String, String, String) -> Unit)? = null
    var onPlayStarted: ((String, String, String) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    // Intent to play, not "audio is leaving the speaker right now": a player
    // that is still buffering has playWhenReady set and would otherwise read as
    // paused, flipping the button and re-triggering the proximity resume.
    val isPlaying: Boolean
        get() = player?.let {
            it.playWhenReady &&
                it.playbackState != Player.STATE_ENDED &&
                it.playbackState != Player.STATE_IDLE
        } == true
    val hasCurrent: Boolean get() = player != null
    val positionMs: Int get() = player?.currentPosition?.toInt() ?: 0
    val durationMs: Int
        get() = player?.duration?.takeIf { it != C.TIME_UNSET }?.toInt() ?: 0

    fun playPause(path: String, chatId: String, msgId: String) {
        if (player != null && currentMsgId == msgId && currentChatId == chatId) {
            if (isPlaying) pause() else resume()
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
     * setPreferredDevice instead kept the clip on the music stream, where the
     * keys had nothing to act on.
     *
     * The rate is applied in software ahead of the output track (ExoPlayer's
     * Sonic stage), which is what lets that route honour the speed pill: the
     * platform player asked its telephony track to stretch, the track quietly
     * refused, and every clip held to an ear played at 1x.
     */
    fun play(
        path: String,
        chatId: String,
        msgId: String,
        startMs: Int = 0,
        useEarpiece: Boolean = proximityNear,
    ) {
        stopInternal(endSession = false)
        val commMode = useEarpiece
        val context = appContext ?: return
        // Held outside the try so a failure part-way through setup still
        // releases the player: `player` is null here (stopInternal above
        // cleared it), so the catch's stopInternal cannot reach this instance.
        var fresh: ExoPlayer? = null
        try {
            val p = ExoPlayer.Builder(context).build()
            fresh = p
            p.setWakeMode(C.WAKE_MODE_LOCAL)
            applyRoute(commMode)
            // false: focus is this object's business (see requestFocus), and
            // ExoPlayer's own handling would duck where a voice note must pause
            p.setAudioAttributes(playerAttributes(commMode), false)
            if (commMode) preferEarpiece(p)
            p.setPlaybackSpeed(speed)
            p.addListener(playerListener(p, path))
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            p.prepare()
            if (startMs > 0) p.seekTo(startMs.toLong())
            requestFocus(commMode)
            p.play()
            player = p
            fresh = null // ownership transferred; stopInternal releases it now
            currentPath = path
            currentChatId = chatId
            currentMsgId = msgId
            earpiece = useEarpiece
            proximitySessionEnded = false
            sessionActive = true
            sessionChatId = chatId
            try { onPlayStarted?.invoke(path, chatId, msgId) } catch (e: Exception) {}
        } catch (e: Exception) {
            android.util.Log.w("AudioPlayer", "play failed for $path", e)
            try { fresh?.release() } catch (e2: Exception) {}
            stopInternal(endSession = true)
        }
        notifyState()
    }

    // Bound to the player it was attached to: a clip recreated on the other
    // output (the proximity switch) leaves the old instance briefly alive, and
    // its end-of-stream must not be taken for the new clip's.
    private fun playerListener(p: ExoPlayer, path: String) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (player !== p) return
            when (state) {
                Player.STATE_ENDED -> onClipEnded()
                // the duration is only known once the file is ready, and the
                // notification and seekbar are drawn from it
                Player.STATE_READY -> notifyState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player !== p) return
            // Errors have their own path: reported as an end of stream, a
            // decode failure or a file truncated mid-playback was
            // indistinguishable from a finished clip and silently advanced the
            // voice chain.
            android.util.Log.w("AudioPlayer", "playback error for $path", error)
            stopInternal(endSession = true)
            notifyState()
        }
    }

    private fun onClipEnded() {
        val finishedPath = currentPath
        val finishedChat = currentChatId
        val finishedMsg = currentMsgId
        // endSession = false: the session stays open across the gap, and the
        // chain ends it if there is nothing left to play
        stopInternal(endSession = false)
        notifyState()
        if (finishedPath != null) {
            try { onCompleted?.invoke(finishedPath, finishedChat, finishedMsg) } catch (e: Exception) {
                android.util.Log.e("AudioPlayer", "onCompleted listener threw", e)
            }
        }
    }

    fun cycleSpeed(): Float {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player?.setPlaybackSpeed(speed)
        notifyState()
        return speed
    }

    private fun preferEarpiece(p: ExoPlayer) {
        val device = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            ?: return
        try { p.setPreferredAudioDevice(device) } catch (e: Exception) {
            android.util.Log.w("AudioPlayer", "earpiece routing refused", e)
        }
    }

    fun seekTo(ms: Int) {
        try { player?.seekTo(ms.toLong()) } catch (e: Exception) {}
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
        if (isPlaying) {
            p.pause()
            if (userInitiated) abandonFocus()
            notifyState()
        }
    }

    fun resume() {
        val p = player ?: return
        if (isPlaying) return
        proximitySessionEnded = false
        if (earpiece != proximityNear) {
            val path = currentPath ?: return
            play(path, currentChatId, currentMsgId, p.currentPosition.toInt())
        } else {
            // re-requested, not assumed: a permanent loss (another app took over
            // the audio) is what paused this clip in the first place
            requestFocus(earpiece && ownsAudioMode)
            p.play()
            notifyState()
        }
    }

    fun switchToEarpiece(rewindMs: Int) {
        val p = player ?: return
        val path = currentPath ?: return
        val chatId = currentChatId
        val pos = (p.currentPosition.toInt() - rewindMs).coerceAtLeast(0)
        play(path, chatId, currentMsgId, pos, useEarpiece = true)
    }

    fun stop() {
        stopInternal(endSession = true)
        notifyState()
    }

    // True only while WE hold the device in MODE_IN_COMMUNICATION. The mode is
    // global: resetting it unconditionally meant finishing a voice note pulled
    // any other app's ongoing call (or VoIP session) back to MODE_NORMAL, and
    // starting one on the media route did the same before it even played.
    private var ownsAudioMode = false

    /**
     * Playback is over for good: drop the ear route and the audio focus. Called
     * by every path that ends a chain, so the service learns of it here — but
     * only when the session was in fact open, since the service's own cleanup
     * calls this too and an unconditional notify would bounce between them.
     */
    fun endSession() {
        val wasActive = sessionActive
        sessionActive = false
        sessionChatId = ""
        earpiece = false
        releaseAudioMode()
        // Focus follows the route: it is deliberately kept across a chain of
        // voice messages (stopInternal(endSession = false)), so whatever was
        // playing before doesn't resume for the gap between two clips.
        abandonFocus()
        if (wasActive) refreshServiceState()
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
                .setAudioAttributes(focusAttributes(commMode))
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

    private fun stopInternal(endSession: Boolean) {
        player?.release()
        player = null
        currentPath = null
        currentChatId = ""
        currentMsgId = ""
        if (endSession) endSession()
    }

    private fun applyRoute(commMode: Boolean) {
        val am = audioManager ?: return
        // Ear playback always takes this path (see play): it is what puts the
        // clip on the earpiece and the volume keys on the call stream, and the
        // only case that touches the global audio mode.
        if (commMode) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsAudioMode = true
            am.isSpeakerphoneOn = false
        } else {
            releaseAudioMode()
        }
    }

    private fun playerAttributes(commMode: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .setUsage(if (commMode) C.USAGE_VOICE_COMMUNICATION else C.USAGE_MEDIA)
        .build()

    private fun focusAttributes(commMode: Boolean): android.media.AudioAttributes =
        android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(
                if (commMode) android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                else android.media.AudioAttributes.USAGE_MEDIA
            )
            .build()

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
