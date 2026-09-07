package org.unichat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class WmService : Service() {

    companion object {
        private const val CHANNEL_CONN = "connection"
        private const val CHANNEL_MEDIA = "media"
        private const val NOTIFICATION_CONN = 1
        private const val NOTIFICATION_MEDIA = 2

        const val ACTION_PLAY_PAUSE = "org.unichat.app.PLAY_PAUSE"
        const val ACTION_NEXT = "org.unichat.app.NEXT"
        const val ACTION_NOTIF_DISMISSED = "org.unichat.app.NOTIF_DISMISSED"

        private const val BLANK_HOLD_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WmService::class.java))
        }
    }

    private var mediaSession: MediaSession? = null
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var proximityRegistered = false
    private var lastNear = false
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONN, getString(R.string.channel_connection),
                NotificationManager.IMPORTANCE_MIN,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MEDIA, getString(R.string.channel_voice_playback),
                NotificationManager.IMPORTANCE_LOW,
            )
        )

        sensorManager = getSystemService(SensorManager::class.java)
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "unichat:proximity"
            )
        }

        setupMediaSession()
        AudioPlayer.onServiceStateChanged = { main.post { onPlaybackChanged() } }
        registerReceiver(screenReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> if (AudioPlayer.isPlaying) AudioPlayer.pause() else AudioPlayer.resume()
            ACTION_NEXT -> Bridge.skipToNextVoice()
            ACTION_NOTIF_DISMISSED ->
                intent.getStringExtra("chatId")?.let { Notifications.onDismissed(this, it) }
            else -> {
                val app = applicationContext
                Thread({
                    if (Bridge.init(app) && Bridge.hasSession()) Bridge.connect()
                }, "service-bridge-init").start()
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ensureForeground()
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioPlayer.onServiceStateChanged = null
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        unregisterProximity()
        mediaSession?.release()
        mediaSession = null
        stopPositionTicker()
        main.removeCallbacks(blankRelease)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_MEDIA)
        AudioPlayer.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground() {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_CONN)
            .setSmallIcon(R.drawable.ic_transparent)
            .setContentTitle(getString(R.string.service_notification))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        startForeground(
            NOTIFICATION_CONN, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun setupMediaSession() {
        val session = MediaSession(this, "unichat")
        sessionVolumeRoute = null
        syncSessionVolume(session)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = AudioPlayer.resume()
            override fun onPause() = AudioPlayer.pause()
            override fun onStop() = AudioPlayer.stop()
            override fun onSkipToNext() = Bridge.skipToNextVoice()
            override fun onSeekTo(pos: Long) = AudioPlayer.seekTo(pos.toInt())
        })
        mediaSession = session
    }

    private fun onPlaybackChanged() {
        val session = mediaSession ?: return
        if (!AudioPlayer.hasCurrent) {
            if (AudioPlayer.sessionActive) {
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_STOP)
                        .setState(PlaybackState.STATE_BUFFERING, 0, 0f)
                        .build()
                )
                return
            }
            session.isActive = false
            stopPositionTicker()
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_MEDIA)
            updateProximity()
            AudioPlayer.endSession()
            mediaTitleChatId = null
            return
        }

        val chatId = AudioPlayer.currentChatId
        val title = mediaTitle(chatId)
        if (title == null) {
            Io.executor.execute {
                val name = Bridge.db.displayName(chatId)
                main.post {
                    mediaTitleChatId = chatId
                    mediaTitleCache = name
                    onPlaybackChanged()
                }
            }
            return
        }

        session.isActive = true
        syncSessionVolume(session)
        val duration = AudioPlayer.durationMs.toLong()
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.voice_message))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build()
        )
        updatePlaybackState()
        postMediaNotification(title)
        updateProximity()
        if (AudioPlayer.isPlaying) startPositionTicker() else stopPositionTicker()
    }

    private var sessionVolumeRoute: Boolean? = null

    private fun syncSessionVolume(session: MediaSession) {
        val ear = AudioPlayer.earpiece
        if (sessionVolumeRoute == ear) return
        sessionVolumeRoute = ear
        session.setPlaybackToLocal(
            android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(
                    if (ear) android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else android.media.AudioAttributes.USAGE_MEDIA
                )
                .build()
        )
    }

    private fun updatePlaybackState() {
        val session = mediaSession ?: return
        val state = if (AudioPlayer.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP
                )
                .setState(
                    state, AudioPlayer.positionMs.toLong(),
                    if (AudioPlayer.isPlaying) AudioPlayer.speed else 0f
                )
                .build()
        )
    }

    private var mediaTitleChatId: String? = null
    private var mediaTitleCache: String = ""

    private fun mediaTitle(chatId: String): String? =
        if (chatId == mediaTitleChatId) mediaTitleCache else null

    private fun postMediaNotification(title: String) {
        val session = mediaSession ?: return
        val playing = AudioPlayer.isPlaying
        val chatId = AudioPlayer.currentChatId

        val contentIntent = Notifications.chatContentIntent(this, chatId)

        val playPause = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(
                this, if (playing) R.drawable.ic_pause else R.drawable.ic_play
            ),
            if (playing) getString(R.string.pause) else getString(R.string.play),
            actionIntent(ACTION_PLAY_PAUSE)
        ).build()
        val next = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_skip_next),
            getString(R.string.next), actionIntent(ACTION_NEXT)
        ).build()

        val notification = Notification.Builder(this, CHANNEL_MEDIA)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(getString(R.string.voice_message))
            .setContentIntent(contentIntent)
            .addAction(playPause)
            .addAction(next)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_MEDIA, notification)
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, WmService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!AudioPlayer.isPlaying) { ticking = false; return }
            updatePlaybackState()
            main.postDelayed(this, 1000)
        }
    }

    private fun startPositionTicker() {
        if (ticking) return
        ticking = true
        main.post(ticker)
    }

    private fun stopPositionTicker() {
        ticking = false
        main.removeCallbacks(ticker)
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensor = proximitySensor ?: return
            val near = event.values[0] < sensor.maximumRange && event.values[0] < 5f
            if (near == lastNear) return
            lastNear = near
            AudioPlayer.proximityNear = near
            if (near) {
                when {
                    AudioPlayer.isPlaying && !AudioPlayer.earpiece -> AudioPlayer.switchToEarpiece(1000)
                    AudioPlayer.hasCurrent && !AudioPlayer.isPlaying -> AudioPlayer.resume()
                    else -> return
                }
                acquireProximityWakeLock()
            } else {
                if (AudioPlayer.isPlaying) AudioPlayer.pause()
                releaseProximityWakeLock()
                updateProximity()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun updateProximity() {
        val screenUsable = getSystemService(PowerManager::class.java).isInteractive ||
            proximityWakeLock?.isHeld == true
        val eligible = AudioPlayer.sessionActive &&
            !AudioPlayer.proximitySessionEnded &&
            AudioPlayer.sessionChatId == Bridge.activeChatId &&
            screenUsable
        if (!eligible && lastNear && proximityWakeLock?.isHeld == true) {
            main.removeCallbacks(blankRelease)
            main.postDelayed(blankRelease, BLANK_HOLD_MS)
            return
        }
        main.removeCallbacks(blankRelease)
        if (eligible) registerProximity() else unregisterProximity()
    }

    private val blankRelease = Runnable {
        releaseProximityWakeLock()
        updateProximity()
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF ->
                    if (proximityWakeLock?.isHeld != true) unregisterProximity()
                Intent.ACTION_SCREEN_ON -> updateProximity()
            }
        }
    }

    private fun registerProximity() {
        val sensor = proximitySensor ?: return
        if (proximityRegistered) return
        proximityRegistered = true
        lastNear = false
        sensorManager?.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterProximity() {
        if (!proximityRegistered) return
        proximityRegistered = false
        AudioPlayer.proximityNear = false
        lastNear = false
        sensorManager?.unregisterListener(proximityListener)
        releaseProximityWakeLock()
    }

    private fun acquireProximityWakeLock() {
        val wl = proximityWakeLock ?: return
        if (!wl.isHeld) wl.acquire(30 * 60 * 1000L)
    }

    private fun releaseProximityWakeLock() {
        val wl = proximityWakeLock ?: return
        if (wl.isHeld) wl.release()
    }
}
