# Micrófono altavoz

App Android para grabar notas de voz con el micrófono que usa el móvil en las
**llamadas en altavoz** (el superior/trasero), en lugar del micrófono inferior.
Pensada para un Samsung Galaxy S24 FE con el micrófono de abajo estropeado.

## Qué hace

1. **Elegir micrófono**
   - *Micrófono del altavoz (como en llamadas)* — recomendado. Pone el móvil en
     el mismo modo que una llamada con altavoz y graba con la fuente de voz de
     llamada, así que usa el mismo micrófono que ya sabes que funciona.
   - *Micrófono superior/trasero (modo cámara)* — alternativa, el que usa la
     cámara al grabar vídeo.
   - *Micrófono inferior* — solo para comparar.
2. **Grabar, escuchar y enviar** — la nota se graba en OGG/Opus (el formato de
   WhatsApp) y con el botón *Enviar* se comparte a WhatsApp, Telegram, etc.
   Mientras grabas se ve el nivel de audio y qué micrófono se está usando.
3. **Modo global (experimental)** — deja el móvil en "modo llamada con
   altavoz" en segundo plano (con una notificación para desactivarlo) para que
   otras apps, como las notas de voz de WhatsApp, intenten usar ese micrófono.
   Android no deja a una app elegir el micrófono de otra, así que no está
   garantizado que funcione en todas.

Abajo del todo aparece la lista de micrófonos que detecta el móvil.

## Instalar

- Descarga `MicrofonoAltavoz.apk` (desde *Releases* o desde la pestaña
  *Actions* → última ejecución → *Artifacts*).
- Ábrelo en el móvil y permite "instalar apps desconocidas" cuando lo pida.
- Concede el permiso de micrófono (y notificaciones para el modo global).

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
