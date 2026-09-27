# Micrófono altavoz

App Android para grabar notas de voz con el micrófono que usa el móvil en las
**llamadas en altavoz** (el superior/trasero), en lugar del micrófono inferior.
Pensada para un Samsung Galaxy S24 FE con el micrófono de abajo estropeado.

## Qué hace

### 1. Todas las apps (WhatsApp, Instagram, Telegram…) — con Shizuku

Un interruptor que cambia el micrófono **de todo el móvil** al del altavoz, así
que grabas los audios normalmente desde WhatsApp, Instagram o cualquier app.
Funciona en segundo plano y no gasta batería.

Android no deja a una app normal cambiar el micrófono de otras apps. Por eso se
usa [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
(gratis, sin root), que da permisos de ADB. Con ellos la app usa la opción
interna de Android de "micrófono preferido para cada fuente de grabación"
(`setPreferredDevicesForCapturePreset`) para las fuentes `MIC`, `CAMCORDER`,
`VOICE_RECOGNITION`, `VOICE_COMMUNICATION`, `UNPROCESSED` y `VOICE_PERFORMANCE`.

Configuración (una vez):

1. Instala Shizuku desde Google Play.
2. Activa las opciones de desarrollador: *Ajustes › Acerca del teléfono ›
   Información de software* › toca 7 veces *Número de compilación*.
3. Con Wi‑Fi, en Shizuku pulsa *Emparejar* y sigue los pasos (*Opciones de
   desarrollador › Depuración inalámbrica › Vincular dispositivo con código*).
4. En Shizuku pulsa *Iniciar*.
5. En esta app pulsa *Dar permiso* y activa el interruptor.

Al reiniciar el móvil Android olvida el ajuste: abre Shizuku y pulsa *Iniciar*
otra vez y la app lo vuelve a activar sola (avisa con una notificación).

Para comprobar que funciona, la app muestra qué micrófono usó la última
grabación de otra app.

### 2. Grabar aquí y enviar

Graba con el micrófono del altavoz desde la propia app y comparte la nota
(OGG/Opus) a WhatsApp, Telegram, etc. Útil si no quieres usar Shizuku.

### 3. Método alternativo sin Shizuku (experimental)

Deja el móvil en "modo llamada con altavoz" en segundo plano. Puede que no
funcione en todas las apps.

Abajo del todo aparece la lista de micrófonos que detecta el móvil.

## Instalar

- Descarga `MicrofonoAltavoz.apk` (desde *Releases* o desde la pestaña
  *Actions* → última ejecución → *Artifacts*).
- Ábrelo en el móvil y permite "instalar apps desconocidas" cuando lo pida.
- Concede el permiso de micrófono y notificaciones.

## Compilar

```sh
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

## Consejos si el micrófono inferior falla

- Limpia con cuidado el agujero del micrófono inferior (junto al USB‑C) con un
  cepillo suave; a menudo es suciedad.
- Prueba el micrófono en *Samsung Members → Soporte → Diagnóstico del
  teléfono → Micrófono*.
- Si está roto, es una reparación sencilla en el servicio técnico (y puede
  entrar en garantía).
