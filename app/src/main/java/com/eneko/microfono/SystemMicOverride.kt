package com.eneko.microfono

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * Cambia el micrófono que usan TODAS las apps (WhatsApp, Instagram, Telegram…).
 *
 * Android tiene una opción interna, "dispositivo preferido para cada fuente de
 * grabación" (setPreferredDevicesForCapturePreset), que solo puede usar el
 * sistema o ADB. Shizuku nos deja llamarla con permisos de ADB, sin root.
 *
 * El ajuste dura hasta que se reinicia el móvil.
 */
object SystemMicOverride {

    /** Fuentes de grabación que usan las apps para notas de voz, vídeos, etc. */
    private val PRESETS = intArrayOf(
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_PERFORMANCE,
    )

    private const val PREFS = "ajustes"
    private const val KEY_ENABLED = "sistema_activo"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST = 42

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun status(context: Context): Status {
        if (!isShizukuInstalled(context)) return Status.NOT_INSTALLED
        if (!Shizuku.pingBinder()) return Status.NOT_RUNNING
        if (Shizuku.isPreV11()) return Status.NOT_RUNNING
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return Status.NO_PERMISSION
        }
        return Status.READY
    }

    private fun isShizukuInstalled(context: Context) = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        // Con root, Shizuku puede estar activo sin la app oficial.
        Shizuku.pingBinder()
    }

    fun isWanted(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setWanted(context: Context, wanted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, wanted).apply()
    }

    private class Api(
        val service: Any,
        val set: Method,
        val clear: Method,
        val get: Method,
    )

    private fun api(): Api {
        val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("audio"))
        val service = Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
        val iface = Class.forName("android.media.IAudioService")
        val int = Int::class.javaPrimitiveType
        return Api(
            service,
            iface.getMethod("setPreferredDevicesForCapturePreset", int, List::class.java),
            iface.getMethod("clearPreferredDevicesForCapturePreset", int),
            iface.getMethod("getPreferredDevicesForCapturePreset", int),
        )
    }

    /**
     * Hace que todas las apps graben con [device].
     * Devuelve el número de fuentes de grabación cambiadas.
     */
    fun apply(device: AudioDeviceInfo): Int {
        val api = api()
        val attributes = Class.forName("android.media.AudioDeviceAttributes")
            .getConstructor(AudioDeviceInfo::class.java)
            .newInstance(device)
        var ok = 0
        for (preset in PRESETS) {
            try {
                val status = api.set.invoke(api.service, preset, listOf(attributes)) as Int
                if (status == 0) ok++
            } catch (_: Exception) {
                // Alguna fuente puede no estar soportada en este móvil.
            }
        }
        if (ok == 0) error("El sistema rechazó el cambio")
        return ok
    }

    /** Devuelve el micrófono a como estaba (lo decide Android). */
    fun clear() {
        val api = api()
        for (preset in PRESETS) {
            try {
                api.clear.invoke(api.service, preset)
            } catch (_: Exception) {
            }
        }
    }

    /** Dirección del micrófono forzado para la fuente MIC ("back", "top"…), o null si no hay. */
    fun currentAddress(): String? {
        val api = api()
        val list = api.get.invoke(api.service, MediaRecorder.AudioSource.MIC) as? List<*>
        val first = list?.firstOrNull() ?: return null
        return first.javaClass.getMethod("getAddress").invoke(first) as? String
    }

    /** Aplica el ajuste si el usuario lo tenía activado y ahora mismo no está puesto. */
    fun reapplyIfWanted(context: Context): Boolean {
        if (!isWanted(context) || status(context) != Status.READY) return false
        return try {
            if (currentAddress() != null) return true
            val am = context.getSystemService(AudioManager::class.java)
            val mic = MicRouting.speakerMic(am) ?: return false
            apply(mic)
            true
        } catch (_: Exception) {
            false
        }
    }
}
