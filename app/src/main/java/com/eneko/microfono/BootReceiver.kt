package com.eneko.microfono

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tras reiniciar, Android olvida el micrófono elegido. Si Shizuku ya está
 * activo (con root) se vuelve a poner solo; si no, se avisa con una notificación.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SystemMicOverride.isWanted(context)) return
        if (SystemMicOverride.reapplyIfWanted(context)) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, context.getString(R.string.boot_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.boot_title))
            .setContentText(context.getString(R.string.boot_text))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.boot_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones.
        }
    }

    companion object {
        private const val CHANNEL_ID = "reinicio"
        const val NOTIFICATION_ID = 2
    }
}
