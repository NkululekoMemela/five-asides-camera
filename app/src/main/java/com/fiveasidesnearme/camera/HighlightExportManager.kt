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

        /**
         * Called after the 15-second clip has been successfully exported.
         * The caller can decide what to do next:
         * - upload to Firebase if official
         * - keep local only if casual
         */
        fun onHighlightExported(
            exportedFile: File,
            tag: String,
            isOfficialMatch: Boolean
        )
    }

    private val bufferDurationMs = 15_000L

    fun exportLast15Seconds(
        sourceFile: File,
        tag: String,
        isOfficialMatch: Boolean,
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
        val outputFile = createOutputFile(tag, isOfficialMatch)

        listener.onStatusChanged(
            if (isOfficialMatch) {
                "Exporting official highlight..."
            } else {
                "Exporting local highlight..."
            }
        )

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

                    listener.onStatusChanged(
                        if (isOfficialMatch) {
                            "Official highlight exported"
                        } else {
                            "Local highlight exported"
                        }
                    )

                    listener.onHighlightSaved(
                        if (isOfficialMatch) {
                            "Official ${tag} clip saved to gallery"
                        } else {
                            "Saved locally — not an official match"
                        }
                    )

                    listener.onHighlightExported(
                        exportedFile = outputFile,
                        tag = tag,
                        isOfficialMatch = isOfficialMatch
                    )

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

    private fun createOutputFile(tag: String, isOfficialMatch: Boolean): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "FiveAsidesNearMeCamera"
        )

        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val prefix = if (isOfficialMatch) "OFFICIAL" else "LOCAL"

        return File(baseDir, "FANM_${prefix}_${tag}_${timestamp}.mp4")
    }
}