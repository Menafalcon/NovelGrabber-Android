package com.novelgrabber.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps TTS playback alive while the user is in other apps / screen off,
 * and shows lock-screen + notification transport controls. It does NOT own the TTS — MainActivity
 * runs the native reading loop; this service just holds foreground priority + a MediaSession and
 * forwards control taps back to the activity.
 */
class TtsService : Service() {

    private lateinit var session: MediaSessionCompat
    private val channelId = "tts_playback"
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var silence: android.media.AudioTrack? = null

    companion object {
        var instance: TtsService? = null
        const val ACTION_TOGGLE = "com.novelgrabber.app.TOGGLE"
        const val ACTION_NEXT = "com.novelgrabber.app.NEXT"          // chapter (notification)
        const val ACTION_PREV = "com.novelgrabber.app.PREV"
        const val ACTION_PARA_NEXT = "com.novelgrabber.app.PARA_NEXT" // paragraph (earphone)
        const val ACTION_PARA_PREV = "com.novelgrabber.app.PARA_PREV"
        const val ACTION_STOP = "com.novelgrabber.app.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, TtsService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TtsService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        session = MediaSessionCompat(this, "NovelGrabberTTS").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = forward(ACTION_TOGGLE)
                override fun onPause() = forward(ACTION_TOGGLE)
                // earphone next/prev (and FF/rewind) move by PARAGRAPH — chapter jumps stay
                // on the notification buttons, which fire ACTION_NEXT/PREV directly
                override fun onSkipToNext() = forward(ACTION_PARA_NEXT)
                override fun onSkipToPrevious() = forward(ACTION_PARA_PREV)
                override fun onFastForward() = forward(ACTION_PARA_NEXT)
                override fun onRewind() = forward(ACTION_PARA_PREV)
                override fun onStop() = forward(ACTION_STOP)
            })
            isActive = true
        }
        // Media buttons route to the app holding audio focus — without this, earphone
        // controls keep going to whatever music app played last.
        requestFocus()
        // …and Android only elects a session whose OWN uid is playing audio. TTS audio is
        // played by the TTS engine's process, so we loop a silent track to count as playing.
        startSilence()
        // start foreground immediately with a placeholder so Android doesn't ANR the service
        startForeground(1, buildNotification("NovelGrabber", "Preparing…", true))
        // now that instance is set, pull the real title/state from the activity
        MainActivity.instance?.onTtsServiceReady()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> forward(ACTION_TOGGLE)
            ACTION_NEXT -> forward(ACTION_NEXT)
            ACTION_PREV -> forward(ACTION_PREV)
            ACTION_STOP -> forward(ACTION_STOP)
        }
        return START_NOT_STICKY
    }

    private fun forward(action: String) { MainActivity.instance?.onTtsControl(action) }

    private fun requestFocus() {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
            focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
            am.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun startSilence() {
        try {
            val sr = 8000
            val samples = ShortArray(sr)   // 1s of PCM silence, looped forever
            val at = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(android.media.AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            at.write(samples, 0, samples.size)
            at.setLoopPoints(0, samples.size, -1)
            at.setVolume(0f)
            at.play()
            silence = at
        } catch (e: Exception) { }
    }

    /** Called by MainActivity as playback state changes. */
    fun update(title: String, subtitle: String, playing: Boolean) {
        val state = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_STOP)
            .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, 0, 1f)
            .build()
        session.setPlaybackState(state)
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, title)
            .build())
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, buildNotification(title, subtitle, playing))
    }

    private fun pi(action: String): PendingIntent {
        val i = Intent(this, TtsService::class.java).setAction(action)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, action.hashCode(), i, flags)
    }

    private fun buildNotification(title: String, subtitle: String, playing: Boolean): Notification {
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        val b = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "Prev", pi(ACTION_PREV))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", pi(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_media_next, "Next", pi(ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi(ACTION_STOP))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
        return b.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "Read aloud", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        try { silence?.stop(); silence?.release(); silence = null } catch (e: Exception) {}
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am.abandonAudioFocus(null)
        } catch (e: Exception) {}
        try { session.isActive = false; session.release() } catch (e: Exception) {}
        instance = null
        super.onDestroy()
    }
}
