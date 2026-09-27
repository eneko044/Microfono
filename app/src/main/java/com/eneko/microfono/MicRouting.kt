package com.eneko.microfono

import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.Locale

/**
 * Utilidades para elegir el micrófono.
 *
 * En los Samsung Galaxy los micrófonos internos aparecen como dispositivos
 * TYPE_BUILTIN_MIC con direcciones como "bottom" (inferior), "back"/"top"
 * (superior/trasero). En llamadas con altavoz el sistema usa el modo
 * MODE_IN_COMMUNICATION con el altavoz como dispositivo de comunicación, que
 * selecciona el micrófono superior/trasero.
 */
object MicRouting {

    fun builtinMics(am: AudioManager): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun AudioDeviceInfo.addr() = address.orEmpty().lowercase(Locale.ROOT)

    fun isBottom(device: AudioDeviceInfo) = device.addr().contains("bottom")

    /** Micrófono que no es el inferior (el que se usa en altavoz), si se puede identificar. */
    fun speakerMic(am: AudioManager): AudioDeviceInfo? {
        val mics = builtinMics(am)
        return mics.firstOrNull { it.addr().contains("back") }
            ?: mics.firstOrNull { it.addr().contains("top") }
            ?: mics.firstOrNull { it.addr().isNotEmpty() && !isBottom(it) }
    }

    fun bottomMic(am: AudioManager): AudioDeviceInfo? =
        builtinMics(am).firstOrNull { isBottom(it) }

    /** Pone el móvil en el mismo estado que una llamada en altavoz. */
    fun enterSpeakerCallMode(am: AudioManager) {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { am.setCommunicationDevice(it) }
    }

    fun exitSpeakerCallMode(am: AudioManager) {
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
    }

    fun describe(device: AudioDeviceInfo?): String {
        if (device == null) return "desconocido"
        val addr = device.address.orEmpty()
        val name = when {
            addr.contains("bottom", true) -> "inferior"
            addr.contains("back", true) -> "trasero (altavoz)"
            addr.contains("top", true) -> "superior (altavoz)"
            else -> device.productName.toString()
        }
        return if (addr.isEmpty()) name else "$name [$addr]"
    }

    fun describeAll(am: AudioManager): String {
        val sb = StringBuilder()
        val mics = builtinMics(am)
        if (mics.isEmpty()) sb.append("No se han encontrado micrófonos internos.\n")
        for (d in mics) {
            sb.append("• ${describe(d)} (id ${d.id})\n")
        }
        try {
            for (m in am.microphones) {
                if (m.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) continue
                val p = m.position
                sb.append(
                    String.format(
                        Locale.ROOT, "  %s [%s] pos x=%.3f y=%.3f z=%.3f\n",
                        m.description, m.address, p.x, p.y, p.z
                    )
                )
            }
        } catch (_: Exception) {
            // Algunos fabricantes no exponen la información detallada.
        }
        return sb.toString().trimEnd()
    }
}
