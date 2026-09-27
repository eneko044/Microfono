package com.eneko.microfono

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder

/**
 * Modo global experimental: mantiene el móvil en "llamada con altavoz" para
 * que el sistema use el micrófono del altavoz también en otras apps.
 *
 * Android devuelve el modo a normal si la app que lo pidió no reproduce ni
 * graba nada, así que se reproduce silencio como "llamada" mientras esté activo.
 */
class GlobalSpeakerMicService : Service() {

    companion object {
        private const val CHANNEL_ID = "speaker_mic"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.eneko.microfono.STOP"
        private const val SAMPLE_RATE = 16000

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GlobalSpeakerMicService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GlobalSpeakerMicService::class.java))
        }
    }

    @Volatile
    private var keepPlaying = false
    private var silenceThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        if (!isRunning) {
            isRunning = true
            MicRouting.enterSpeakerCallMode(getSystemService(AudioManager::class.java))
            startSilence()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keepPlaying = false
        silenceThread?.join(1000)
        silenceThread = null
        if (isRunning) {
            MicRouting.exitSpeakerCallMode(getSystemService(AudioManager::class.java))
        }
        isRunning = false
        super.onDestroy()
    }

    private fun startSilence() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()

        keepPlaying = true
        silenceThread = Thread {
            val silence = ByteArray(bufferSize)
            try {
                track.play()
                while (keepPlaying) {
                    if (track.write(silence, 0, silence.size) < 0) break
                }
            } finally {
                track.stop()
                track.release()
            }
        }.apply { name = "silence"; start() }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            )
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GlobalSpeakerMicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build()
            )
            .build()
    }
}
