package com.eneko.microfono

import android.app.Application
import android.app.NotificationManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

class MicrofonoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Permite usar las funciones internas de audio de Android.
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (_: Throwable) {
            // Si falla, el modo "todas las apps" avisará con un error al activarlo.
        }
        // Cuando Shizuku se (re)inicia, se vuelve a poner el micrófono elegido.
        Shizuku.addBinderReceivedListenerSticky {
            if (SystemMicOverride.reapplyIfWanted(this)) {
                getSystemService(NotificationManager::class.java)
                    .cancel(BootReceiver.NOTIFICATION_ID)
            }
        }
    }
}
