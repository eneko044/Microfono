package com.eneko.microfono

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10

class MainActivity : Activity() {

    private enum class MicMode { SPEAKER, TOP, BOTTOM }

    private lateinit var audioManager: AudioManager
    private lateinit var modeGroup: RadioGroup
    private lateinit var recordButton: Button
    private lateinit var playButton: Button
    private lateinit var shareButton: Button
    private lateinit var levelBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var devicesText: TextView
    private lateinit var globalSwitch: Switch

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var lastRecording: File? = null
    private var enteredCallMode = false
    private var recordStart = 0L
    private var routedMic: AudioDeviceInfo? = null

    private val levelUpdater = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            if (routedMic == null) routedMic = r.routedDevice
            val amp = r.maxAmplitude.coerceAtLeast(1)
            val db = 20 * log10(amp / 32767.0)
            levelBar.progress = ((db + 60) / 60 * 100).toInt().coerceIn(0, 100)
            val secs = (SystemClock.elapsedRealtime() - recordStart) / 1000
            statusText.text = String.format(
                Locale.ROOT, "Grabando %d:%02d · micrófono: %s",
                secs / 60, secs % 60, MicRouting.describe(routedMic)
            )
            handler.postDelayed(this, 100)
        }
    }

    private val globalListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) {
            if (!hasMicPermission()) requestPermissions()
            GlobalSpeakerMicService.start(this)
        } else {
            GlobalSpeakerMicService.stop(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audioManager = getSystemService(AudioManager::class.java)

        modeGroup = findViewById(R.id.modeGroup)
        recordButton = findViewById(R.id.recordButton)
        playButton = findViewById(R.id.playButton)
        shareButton = findViewById(R.id.shareButton)
        levelBar = findViewById(R.id.levelBar)
        statusText = findViewById(R.id.statusText)
        devicesText = findViewById(R.id.devicesText)
        globalSwitch = findViewById(R.id.globalSwitch)

        recordButton.setOnClickListener {
            if (recorder != null) stopRecording() else startRecording()
        }
        playButton.setOnClickListener {
            if (player != null) stopPlayback() else startPlayback()
        }
        shareButton.setOnClickListener { share() }

        devicesText.text = MicRouting.describeAll(audioManager)

        if (!hasMicPermission()) requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // El modo global se puede desactivar desde la notificación.
        globalSwitch.setOnCheckedChangeListener(null)
        globalSwitch.isChecked = GlobalSpeakerMicService.isRunning
        globalSwitch.setOnCheckedChangeListener(globalListener)
    }

    override fun onStop() {
        super.onStop()
        if (recorder != null) stopRecording()
        stopPlayback()
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1
        )
    }

    private fun selectedMode() = when (modeGroup.checkedRadioButtonId) {
        R.id.modeTop -> MicMode.TOP
        R.id.modeBottom -> MicMode.BOTTOM
        else -> MicMode.SPEAKER
    }

    private fun startRecording() {
        if (!hasMicPermission()) {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }
        stopPlayback()

        val mode = selectedMode()
        val (source, preferred) = when (mode) {
            MicMode.SPEAKER -> MediaRecorder.AudioSource.VOICE_COMMUNICATION to
                MicRouting.speakerMic(audioManager)
            MicMode.TOP -> MediaRecorder.AudioSource.CAMCORDER to
                MicRouting.speakerMic(audioManager)
            MicMode.BOTTOM -> MediaRecorder.AudioSource.MIC to
                MicRouting.bottomMic(audioManager)
        }

        if (mode == MicMode.SPEAKER && !GlobalSpeakerMicService.isRunning) {
            MicRouting.enterSpeakerCallMode(audioManager)
            enteredCallMode = true
        }

        val dir = File(cacheDir, "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "nota_$stamp.ogg")

        val r = MediaRecorder(this)
        try {
            r.setAudioSource(source)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(file)
            preferred?.let { r.setPreferredDevice(it) }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            leaveCallMode()
            Toast.makeText(this, "No se pudo grabar: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        recorder = r
        currentFile = file
        routedMic = null
        recordStart = SystemClock.elapsedRealtime()
        recordButton.setText(R.string.stop)
        playButton.isEnabled = false
        shareButton.isEnabled = false
        modeGroup.isEnabled = false
        handler.post(levelUpdater)
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        handler.removeCallbacks(levelUpdater)
        val file = currentFile
        currentFile = null

        val ok = try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false // grabación demasiado corta
        } finally {
            r.release()
            leaveCallMode()
        }

        levelBar.progress = 0
        recordButton.setText(R.string.record)
        modeGroup.isEnabled = true

        if (ok && file != null) {
            lastRecording?.takeIf { it != file }?.delete()
            lastRecording = file
            statusText.text = "Grabación lista · micrófono: ${MicRouting.describe(routedMic)}"
        } else {
            file?.delete()
            statusText.text = "Grabación demasiado corta"
        }
        playButton.isEnabled = lastRecording != null
        shareButton.isEnabled = lastRecording != null
    }

    private fun leaveCallMode() {
        if (enteredCallMode) {
            MicRouting.exitSpeakerCallMode(audioManager)
            enteredCallMode = false
        }
    }

    private fun startPlayback() {
        val file = lastRecording ?: return
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stopPlayback() }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            p.release()
            Toast.makeText(this, "No se pudo reproducir: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        player = p
        playButton.setText(R.string.stop_play)
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playButton.setText(R.string.play)
    }

    private fun share() {
        val file = lastRecording ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/ogg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }
}
