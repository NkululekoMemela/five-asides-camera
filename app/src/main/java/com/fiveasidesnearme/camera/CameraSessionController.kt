package com.fiveasidesnearme.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner

class CameraSessionController(
    private val context: Context,
    private val cameraProvider: ProcessCameraProvider,
    private val previewView: PreviewView,
    private val listener: Listener
) {

    interface Listener {
        fun onCameraReady()
        fun onError(message: String)
    }

    fun bindCamera(videoCapture: VideoCapture<Recorder>) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                videoCapture
            )
            listener.onCameraReady()
        } catch (e: Exception) {
            listener.onError("Failed to bind camera: ${e.message}")
        }
    }

    fun release() {
        cameraProvider.unbindAll()
    }
}