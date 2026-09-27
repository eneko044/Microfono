package com.eneko.microfono

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import rikka.shizuku.Shizuku
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
    private lateinit var systemStatus: TextView
    private lateinit var setupSteps: LinearLayout
    private lateinit var systemSwitch: Switch
    private lateinit var otherAppsText: TextView

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

    private val systemListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        setSystemOverride(checked)
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) {
                SystemMicOverride.reapplyIfWanted(this)
            }
            refreshSystemUi()
        }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { refreshSystemUi() }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener { refreshSystemUi() }

    /** Muestra qué micrófono usan otras apps cuando graban (para comprobar que funciona). */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            if (recorder != null) return
            val device = configs.firstNotNullOfOrNull { it.audioDevice } ?: return
            val time = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())
            otherAppsText.text = getString(
                R.string.other_apps_seen, time, MicRouting.describe(device)
            )
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
        systemStatus = findViewById(R.id.systemStatus)
        setupSteps = findViewById(R.id.setupSteps)
        systemSwitch = findViewById(R.id.systemSwitch)
        otherAppsText = findViewById(R.id.otherAppsText)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        audioManager.registerAudioRecordingCallback(recordingCallback, handler)

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

        SystemMicOverride.reapplyIfWanted(this)
        refreshSystemUi()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        super.onDestroy()
    }

    private fun refreshSystemUi() {
        val status = SystemMicOverride.status(this)
        val active = if (status == SystemMicOverride.Status.READY) {
            try {
                SystemMicOverride.currentAddress()
            } catch (_: Exception) {
                null
            }
        } else null

        systemStatus.text = when {
            active != null -> getString(
                R.string.status_active,
                MicRouting.builtinMics(audioManager)
                    .firstOrNull { it.address == active }
                    ?.let { MicRouting.describe(it) } ?: active
            )
            status == SystemMicOverride.Status.NOT_INSTALLED ->
                getString(R.string.status_not_installed)
            status == SystemMicOverride.Status.NOT_RUNNING ->
                getString(R.string.status_not_running)
            status == SystemMicOverride.Status.NO_PERMISSION ->
                getString(R.string.status_no_permission)
            else -> getString(R.string.status_ready)
        }

        renderSetupSteps(status)

        systemSwitch.setOnCheckedChangeListener(null)
        systemSwitch.isEnabled = status == SystemMicOverride.Status.READY
        systemSwitch.isChecked = active != null
        systemSwitch.setOnCheckedChangeListener(systemListener)
    }

    private class Step(
        val title: String,
        val detail: String,
        val done: Boolean,
        val buttons: List<Pair<String, () -> Unit>>,
    )

    /** Asistente paso a paso para dejar Shizuku funcionando. */
    private fun renderSetupSteps(status: SystemMicOverride.Status) {
        setupSteps.removeAllViews()
        if (status == SystemMicOverride.Status.READY) return

        val installed = status != SystemMicOverride.Status.NOT_INSTALLED
        val running = status == SystemMicOverride.Status.NO_PERMISSION
        val devOptions = try {
            Settings.Global.getInt(
                contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
        } catch (_: Exception) {
            false
        }

        val steps = listOf(
            Step(
                getString(R.string.step1_title), getString(R.string.step1_detail), installed,
                listOf(getString(R.string.step1_button) to ::openShizukuStore)
            ),
            Step(
                getString(R.string.step2_title), getString(R.string.step2_detail),
                devOptions || running,
                listOf(getString(R.string.step2_button) to {
                    openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
                })
            ),
            Step(
                getString(R.string.step3_title), getString(R.string.step3_detail), running,
                listOf(
                    getString(R.string.step3_button_shizuku) to ::openShizuku,
                    getString(R.string.step3_button_settings) to {
                        openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    },
                )
            ),
            Step(
                getString(R.string.step4_title), getString(R.string.step4_detail), running,
                listOf(getString(R.string.step4_button) to ::openShizuku)
            ),
            Step(
                getString(R.string.step5_title), getString(R.string.step5_detail), false,
                if (running) {
                    listOf(getString(R.string.step5_button) to {
                        Shizuku.requestPermission(SystemMicOverride.PERMISSION_REQUEST)
                    })
                } else emptyList()
            ),
        )

        // Solo se muestran los botones del primer paso pendiente, para no liar.
        val current = steps.indexOfFirst { !it.done }
        val pad = (8 * resources.displayMetrics.density).toInt()
        steps.forEachIndexed { i, step ->
            val mark = when {
                step.done -> "✓"
                i == current -> "➜"
                else -> "○"
            }
            setupSteps.addView(TextView(this).apply {
                text = "$mark ${i + 1}. ${step.title}"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                alpha = if (i == current) 1f else 0.6f
                setPadding(0, pad * 2, 0, 0)
            })
            if (i != current) return@forEachIndexed
            setupSteps.addView(TextView(this).apply {
                text = step.detail
                setPadding(0, pad / 2, 0, pad / 2)
            })
            for ((label, action) in step.buttons) {
                setupSteps.addView(Button(this).apply {
                    text = label
                    setOnClickListener { action() }
                })
            }
        }
    }

    private fun openShizukuStore() {
        openUrl(
            "market://details?id=${SystemMicOverride.SHIZUKU_PACKAGE}",
            "https://play.google.com/store/apps/details?id=${SystemMicOverride.SHIZUKU_PACKAGE}"
        )
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(SystemMicOverride.SHIZUKU_PACKAGE)
        if (intent != null) startActivity(intent) else openShizukuStore()
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openUrl(vararg urls: String) {
        for (url in urls) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun setSystemOverride(enable: Boolean) {
        try {
            if (enable) {
                val mic = MicRouting.speakerMic(audioManager)
                if (mic == null) {
                    Toast.makeText(this, R.string.no_speaker_mic, Toast.LENGTH_LONG).show()
                } else {
                    SystemMicOverride.apply(mic)
                    SystemMicOverride.setWanted(this, true)
                }
            } else {
                SystemMicOverride.clear()
                SystemMicOverride.setWanted(this, false)
            }
        } catch (e: Exception) {
            val msg = generateSequence<Throwable>(e) { it.cause }.last().message ?: e.toString()
            Toast.makeText(this, "No se pudo cambiar: $msg", Toast.LENGTH_LONG).show()
        }
        refreshSystemUi()
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
