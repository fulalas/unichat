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
    var currentMsgId: String = ""
        private set
    var earpiece: Boolean = false
        private set

    @Volatile var speed: Float = 1f
        private set

    var proximityNear: Boolean = false

    @Volatile var proximitySessionEnded: Boolean = false
        private set

    @Volatile var sessionActive: Boolean = false
        private set

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
        var fresh: ExoPlayer? = null
        try {
            val p = ExoPlayer.Builder(context).build()
            fresh = p
            p.setWakeMode(C.WAKE_MODE_LOCAL)
            applyRoute(commMode)
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
            fresh = null
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

    private fun playerListener(p: ExoPlayer, path: String) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (player !== p) return
            when (state) {
                Player.STATE_ENDED -> onClipEnded()
                Player.STATE_READY -> notifyState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player !== p) return
            android.util.Log.w("AudioPlayer", "playback error for $path", error)
            stopInternal(endSession = true)
            notifyState()
        }
    }

    private fun onClipEnded() {
        val finishedPath = currentPath
        val finishedChat = currentChatId
        val finishedMsg = currentMsgId
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

    private var ownsAudioMode = false

    fun endSession() {
        val wasActive = sessionActive
        sessionActive = false
        sessionChatId = ""
        earpiece = false
        releaseAudioMode()
        abandonFocus()
        if (wasActive) refreshServiceState()
    }

    val volumeStream: Int
        get() = if (ownsAudioMode) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    private var focusRequest: AudioFocusRequest? = null
    private var focusComm = false

    private var resumeOnFocusGain = false

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
        val held = focusRequest
        val req = if (held != null && focusComm == commMode) held else {
            abandonFocus()
            AudioFocusRequest.Builder(
                if (commMode) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
                .setAudioAttributes(focusAttributes(commMode))
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(newFocusListener())
                .build()
                .also { focusRequest = it; focusComm = commMode }
        }
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
        try { onStateChanged?.invoke() } catch (e: Exception) {
            android.util.Log.e("AudioPlayer", "onStateChanged listener threw", e)
        }
        try { onServiceStateChanged?.invoke() } catch (e: Exception) {
            android.util.Log.e("AudioPlayer", "onServiceStateChanged listener threw", e)
        }
    }
}
