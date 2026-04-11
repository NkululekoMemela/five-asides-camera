package com.fiveasidesnearme.camera

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fiveasidesnearme.camera.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var recorderManager: HighlightRecorderManager? = null
    private var currentTag: String = "highlight"

    private var initialBufferTimer: CountDownTimer? = null
    private var saveCooldownTimer: CountDownTimer? = null
    private var secondsBuffered: Int = 0
    private var isCoolingDownAfterSave: Boolean = false
    private var isRecordingActive: Boolean = false

    private val readyRed = Color.parseColor("#B71C1C")
    private val disabledGrey = Color.parseColor("#455A64")
    private val bufferingBlueGrey = Color.parseColor("#546E7A")
    private val startBlue = Color.parseColor("#1565C0")
    private val recordingBlue = Color.parseColor("#0D47A1")
    private val stopDark = Color.parseColor("#263238")
    private val stopActive = Color.parseColor("#37474F")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = REQUIRED_PERMISSIONS.all { grants[it] == true }
        if (granted) {
            setupCamera()
        } else {
            Toast.makeText(
                this,
                "Camera and audio permissions are required.",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        applyWindowInsets()
        setupUi()

        if (hasAllPermissions()) {
            setupCamera()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomPanel.setPadding(
                binding.bottomPanel.paddingLeft,
                binding.bottomPanel.paddingTop,
                binding.bottomPanel.paddingRight,
                systemBars.bottom + 20
            )
            insets
        }
    }

    private fun setupUi() {
        binding.startBufferButton.setOnClickListener {
            recorderManager?.startRollingBuffer()
            showRecordingBanner()
            startInitialBufferProgressUi()
            setRecordingUi(true)
            binding.statusText.text = "5 Asides Near Me • Video recording started"
            Toast.makeText(this, "Video recording started", Toast.LENGTH_SHORT).show()
        }

        binding.stopBufferButton.setOnClickListener {
            recorderManager?.stopRollingBuffer()
            stopInitialBufferProgressUi()
            stopSaveCooldownUi()
            setRecordingUi(false)
            binding.statusText.text = "Recording stopped"
        }

        binding.saveLastButton.setOnClickListener {
            if (!isCoolingDownAfterSave && isRecordingActive) {
                recorderManager?.saveHighlight(currentTag)
            }
        }

        binding.goalButton.setOnClickListener {
            currentTag = "goal"
            binding.statusText.text = "Clip type selected: goal"
        }

        binding.saveTagButton.setOnClickListener {
            currentTag = "save"
            binding.statusText.text = "Clip type selected: save"
        }

        binding.skillButton.setOnClickListener {
            currentTag = "skill"
            binding.statusText.text = "Clip type selected: skill"
        }

        binding.statusText.text = "Camera ready"
        setRecordingUi(false)
        resetSaveButtonToDefault()
    }

    private fun showRecordingBanner() {
        binding.recordingBanner.visibility = View.VISIBLE
        binding.recordingBanner.alpha = 0f
        binding.recordingBanner.animate()
            .alpha(1f)
            .setDuration(250)
            .withEndAction {
                binding.recordingBanner.postDelayed({
                    binding.recordingBanner.animate()
                        .alpha(0f)
                        .setDuration(600)
                        .withEndAction {
                            binding.recordingBanner.visibility = View.GONE
                            binding.recordingBanner.alpha = 1f
                        }
                        .start()
                }, 3000)
            }
            .start()
    }

    private fun startInitialBufferProgressUi() {
        initialBufferTimer?.cancel()
        secondsBuffered = 0
        binding.saveBufferProgress.max = 15
        binding.saveBufferProgress.progress = 0
        binding.saveLastButton.isEnabled = true
        binding.saveLastButton.text = "BUFFERING 15S"
        setSaveButtonStateBuffering()

        initialBufferTimer = object : CountDownTimer(15_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                if (isCoolingDownAfterSave || !isRecordingActive) return

                secondsBuffered = 15 - (millisUntilFinished / 1000L).toInt()
                binding.saveBufferProgress.progress = secondsBuffered
                val remaining = 15 - secondsBuffered

                if (secondsBuffered >= 15) {
                    binding.saveLastButton.text = "SAVE LAST 15S"
                    setSaveButtonStateReady()
                } else {
                    binding.saveLastButton.text = "BUFFERING ${remaining}S"
                    setSaveButtonStateBuffering()
                }
            }

            override fun onFinish() {
                if (isCoolingDownAfterSave || !isRecordingActive) return

                secondsBuffered = 15
                binding.saveBufferProgress.progress = 15
                binding.saveLastButton.text = "SAVE LAST 15S"
                setSaveButtonStateReady()
            }
        }.start()
    }

    private fun stopInitialBufferProgressUi() {
        initialBufferTimer?.cancel()
        initialBufferTimer = null
        if (!isCoolingDownAfterSave) {
            resetSaveButtonToDefault()
        }
    }

    private fun startSaveCooldownUi() {
        saveCooldownTimer?.cancel()
        isCoolingDownAfterSave = true

        binding.saveLastButton.isEnabled = false
        binding.saveBufferProgress.max = 15
        binding.saveBufferProgress.progress = 0
        binding.saveLastButton.text = "HIGHLIGHT SAVED"
        setSaveButtonStateCoolingDown()

        saveCooldownTimer = object : CountDownTimer(15_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = (millisUntilFinished / 1000L).toInt()
                val elapsed = 15 - remaining
                binding.saveBufferProgress.progress = elapsed
                binding.saveLastButton.text =
                    if (remaining > 0) "NEXT SAVE IN ${remaining}S" else "SAVE LAST 15S"
                setSaveButtonStateCoolingDown()
            }

            override fun onFinish() {
                isCoolingDownAfterSave = false
                binding.saveLastButton.isEnabled = isRecordingActive
                binding.saveBufferProgress.max = 15
                binding.saveBufferProgress.progress = 15
                binding.saveLastButton.text = "SAVE LAST 15S"
                setSaveButtonStateReady()
            }
        }.start()
    }

    private fun stopSaveCooldownUi() {
        saveCooldownTimer?.cancel()
        saveCooldownTimer = null
        isCoolingDownAfterSave = false
        resetSaveButtonToDefault()
    }

    private fun resetSaveButtonToDefault() {
        binding.saveBufferProgress.max = 15
        binding.saveBufferProgress.progress = 0
        binding.saveLastButton.text = "SAVE LAST 15S"

        if (isRecordingActive) {
            setSaveButtonStateReady()
            binding.saveBufferProgress.progress = 15
        } else {
            setSaveButtonStateDisabled()
            binding.saveLastButton.isEnabled = false
        }
    }

    private fun setRecordingUi(isRecording: Boolean) {
        isRecordingActive = isRecording

        binding.startBufferButton.isEnabled = !isRecording
        binding.stopBufferButton.isEnabled = isRecording

        if (isRecording) {
            binding.startBufferButton.text = "RECORDING"
            binding.startBufferButton.backgroundTintList =
                ColorStateList.valueOf(recordingBlue)

            binding.stopBufferButton.backgroundTintList =
                ColorStateList.valueOf(stopActive)
        } else {
            binding.startBufferButton.text = "START VIDEO"
            binding.startBufferButton.backgroundTintList =
                ColorStateList.valueOf(startBlue)

            binding.stopBufferButton.backgroundTintList =
                ColorStateList.valueOf(stopDark)
        }
    }

    private fun setSaveButtonStateReady() {
        binding.saveLastButton.backgroundTintList =
            ColorStateList.valueOf(readyRed)
        binding.saveLastButton.setTextColor(Color.WHITE)
    }

    private fun setSaveButtonStateCoolingDown() {
        binding.saveLastButton.backgroundTintList =
            ColorStateList.valueOf(disabledGrey)
        binding.saveLastButton.setTextColor(Color.parseColor("#CFD8DC"))
    }

    private fun setSaveButtonStateBuffering() {
        binding.saveLastButton.backgroundTintList =
            ColorStateList.valueOf(bufferingBlueGrey)
        binding.saveLastButton.setTextColor(Color.WHITE)
    }

    private fun setSaveButtonStateDisabled() {
        binding.saveLastButton.backgroundTintList =
            ColorStateList.valueOf(disabledGrey)
        binding.saveLastButton.setTextColor(Color.parseColor("#B0BEC5"))
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            recorderManager = HighlightRecorderManager(
                context = this,
                cameraProvider = cameraProvider,
                previewView = binding.previewView,
                mainExecutor = ContextCompat.getMainExecutor(this),
                backgroundExecutor = cameraExecutor,
                listener = object : HighlightRecorderManager.Listener {
                    override fun onStatusChanged(message: String) {
                        binding.statusText.text = message
                    }

                    override fun onRollingStateChanged(isRunning: Boolean) {
                        setRecordingUi(isRunning)

                        if (!isRollingAndCoolingDownBlocked()) {
                            binding.saveLastButton.isEnabled = isRunning
                        }

                        if (!isRunning) {
                            stopInitialBufferProgressUi()
                            stopSaveCooldownUi()
                        }
                    }

                    override fun onHighlightSaved(message: String) {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        binding.statusText.text = "Highlight saved successfully"
                        startSaveCooldownUi()
                    }

                    override fun onError(message: String) {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        binding.statusText.text = message
                    }
                }
            )

            recorderManager?.bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun isRollingAndCoolingDownBlocked(): Boolean {
        return isRecordingActive && isCoolingDownAfterSave
    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        initialBufferTimer?.cancel()
        saveCooldownTimer?.cancel()
        recorderManager?.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}