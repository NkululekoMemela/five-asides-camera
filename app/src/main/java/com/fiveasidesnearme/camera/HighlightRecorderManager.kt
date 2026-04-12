package com.fiveasidesnearme.camera

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

class HighlightRecorderManager(
    context: Context,
    cameraProvider: ProcessCameraProvider,
    previewView: PreviewView,
    private val mainExecutor: Executor,
    backgroundExecutor: ExecutorService,
    private val listener: Listener
) {

    interface Listener {
        fun onStatusChanged(message: String)
        fun onRollingStateChanged(isRunning: Boolean)
        fun onHighlightSaved(message: String)
        fun onError(message: String)
    }

    private val cameraSessionController = CameraSessionController(
        context = context,
        cameraProvider = cameraProvider,
        previewView = previewView,
        listener = object : CameraSessionController.Listener {
            override fun onCameraReady() {
                listener.onStatusChanged("Camera ready")
                listener.onRollingStateChanged(false)
            }

            override fun onError(message: String) {
                listener.onError(message)
            }
        }
    )

    private val rollingBufferEngine = RollingBufferEngine(
        context = context,
        mainExecutor = mainExecutor,
        backgroundExecutor = backgroundExecutor,
        listener = object : RollingBufferEngine.Listener {
            override fun onStatusChanged(message: String) {
                listener.onStatusChanged(message)
            }

            override fun onRollingStateChanged(isRunning: Boolean) {
                listener.onRollingStateChanged(isRunning)
            }

            override fun onError(message: String) {
                listener.onError(message)
            }
        }
    )

    private val highlightExportManager = HighlightExportManager(
        context = context,
        listener = object : HighlightExportManager.Listener {
            override fun onStatusChanged(message: String) {
                listener.onStatusChanged(message)
            }

            override fun onHighlightSaved(message: String) {
                listener.onHighlightSaved(message)
            }

            override fun onError(message: String) {
                listener.onError(message)
            }
        }
    )

    fun bindCamera() {
        cameraSessionController.bindCamera(rollingBufferEngine.videoCapture)
    }

    fun startRollingBuffer() {
        rollingBufferEngine.startRollingBuffer()
    }

    fun stopRollingBuffer() {
        rollingBufferEngine.stopRollingBuffer()
    }

    fun saveHighlight(tag: String) {

        val wasRunning = rollingBufferEngine.isRolling()

        if (wasRunning) {
            listener.onStatusChanged("Finalizing recording...")

            rollingBufferEngine.stopRollingBuffer { finalizedFile ->
                if (finalizedFile == null) {
                    listener.onError("Could not finalize recording")
                    return@stopRollingBuffer
                }

                highlightExportManager.exportLast15Seconds(
                    sourceFile = finalizedFile,
                    tag = tag,
                    onComplete = {
                        rollingBufferEngine.startRollingBuffer()
                    }
                )
            }

        } else {
            val finalizedFile = rollingBufferEngine.getLastFinalizedFile()
            if (finalizedFile == null) {
                listener.onError("No recording available")
                return
            }

            highlightExportManager.exportLast15Seconds(
                sourceFile = finalizedFile,
                tag = tag,
                onComplete = {}
            )
        }
    }

    fun release() {
        rollingBufferEngine.release()
        cameraSessionController.release()
    }
}