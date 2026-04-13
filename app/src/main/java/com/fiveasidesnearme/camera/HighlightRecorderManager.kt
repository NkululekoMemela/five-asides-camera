package com.fiveasidesnearme.camera

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.io.File
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

        /**
         * Called after a highlight clip has been exported.
         * For now this gives MainActivity a hook for future Firebase upload logic.
         */
        fun onHighlightExported(
            exportedFile: File,
            tag: String,
            isOfficialMatch: Boolean
        )
    }

    private var isOfficialMatchContext: Boolean = false

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

            override fun onHighlightExported(
                exportedFile: File,
                tag: String,
                isOfficialMatch: Boolean
            ) {
                listener.onHighlightExported(
                    exportedFile = exportedFile,
                    tag = tag,
                    isOfficialMatch = isOfficialMatch
                )
            }
        }
    )

    fun setOfficialMatchContext(isOfficialMatch: Boolean) {
        isOfficialMatchContext = isOfficialMatch
    }

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
                    isOfficialMatch = isOfficialMatchContext,
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
                isOfficialMatch = isOfficialMatchContext,
                onComplete = {}
            )
        }
    }

    fun release() {
        rollingBufferEngine.release()
        cameraSessionController.release()
    }
}