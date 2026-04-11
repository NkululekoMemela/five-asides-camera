package com.fiveasidesnearme.camera

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HighlightExportManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatusChanged(message: String)
        fun onHighlightSaved(message: String)
        fun onError(message: String)
    }

    private val bufferDurationMs = 15_000L

    fun exportLast15Seconds(
        sourceFile: File,
        tag: String,
        onComplete: () -> Unit
    ) {
        if (!sourceFile.exists()) {
            listener.onError("Source recording not found")
            return
        }

        val durationMs = getVideoDurationMs(sourceFile)
        if (durationMs <= 0L) {
            listener.onError("Could not read recording duration")
            return
        }

        if (durationMs < bufferDurationMs) {
            listener.onError("Wait a few more seconds before saving")
            return
        }

        val startMs = durationMs - bufferDurationMs
        val outputFile = createOutputFile(tag)

        listener.onStatusChanged("Exporting last 15 seconds...")

        val clippedMediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(sourceFile))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(durationMs)
                    .build()
            )
            .build()

        val editedItem = EditedMediaItem.Builder(clippedMediaItem).build()
        val sequence = EditedMediaItemSequence.Builder(listOf(editedItem)).build()
        val composition = Composition.Builder(sequence).build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(outputFile.absolutePath),
                        arrayOf("video/mp4"),
                        null
                    )
                    listener.onStatusChanged("Highlight saved")
                    listener.onHighlightSaved("Saved ${tag} clip to gallery")
                    onComplete()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    listener.onError("Export failed: ${exportException.message}")
                }
            })
            .build()

        transformer.start(composition, outputFile.absolutePath)
    }

    private fun getVideoDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationString =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationString?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun createOutputFile(tag: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "FiveAsidesNearMeCamera"
        )
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return File(baseDir, "FANM_${tag}_${timestamp}.mp4")
    }
}