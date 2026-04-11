package com.fiveasidesnearme.camera

import android.content.Context
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

class RollingBufferEngine(
    private val context: Context,
    private val mainExecutor: Executor,
    private val backgroundExecutor: ExecutorService,
    private val listener: Listener
) {

    interface Listener {
        fun onStatusChanged(message: String)
        fun onRollingStateChanged(isRunning: Boolean)
        fun onError(message: String)
    }

    private val recorder: Recorder = Recorder.Builder()
        .setExecutor(backgroundExecutor)
        .setQualitySelector(QualitySelector.from(Quality.HD))
        .build()

    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

    private var activeRecording: Recording? = null
    private var pendingRecording: PendingRecording? = null
    private var currentFile: File? = null
    private var lastFinalizedFile: File? = null
    private var pendingStopCallback: ((File?) -> Unit)? = null

    @Volatile
    private var rolling = false

    fun isRolling(): Boolean = rolling

    fun getLastFinalizedFile(): File? = lastFinalizedFile

    fun startRollingBuffer() {
        if (rolling) return

        val outputFile = File(context.cacheDir, "rolling_${System.currentTimeMillis()}.mp4")
        currentFile = outputFile

        val fileOptions = FileOutputOptions.Builder(outputFile).build()

        pendingRecording = recorder.prepareRecording(context, fileOptions).apply {
            withAudioEnabled()
        }

        activeRecording = pendingRecording?.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    rolling = true
                    listener.onRollingStateChanged(true)
                    listener.onStatusChanged("Recording started")
                }

                is VideoRecordEvent.Finalize -> {
                    rolling = false
                    listener.onRollingStateChanged(false)

                    val callback = pendingStopCallback
                    pendingStopCallback = null

                    if (event.hasError()) {
                        outputFile.delete()
                        listener.onError("Recording failed: ${event.error}")
                        callback?.invoke(null)
                    } else {
                        lastFinalizedFile = outputFile
                        listener.onStatusChanged("Recording finalized")
                        callback?.invoke(outputFile)
                    }

                    activeRecording = null
                }
            }
        }
    }

    fun stopRollingBuffer(onStopped: ((File?) -> Unit)? = null) {
        if (!rolling) {
            onStopped?.invoke(lastFinalizedFile)
            return
        }

        pendingStopCallback = onStopped
        activeRecording?.stop()
    }

    fun release() {
        pendingStopCallback = null
        activeRecording?.stop()
        activeRecording = null
        rolling = false
    }
}