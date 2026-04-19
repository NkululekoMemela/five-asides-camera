package com.fiveasidesnearme.camera

import android.net.Uri
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.util.UUID

data class UploadedHighlightResult(
    val clipId: String,
    val storagePath: String,
    val downloadUrl: String,
    val matchId: String?
)

object FirebaseUploader {

    private const val TAG = "FANM_FIREBASE"

    private val storage = FirebaseStorage.getInstance()

    fun uploadHighlight(
        file: File,
        matchId: String?,
        tag: String,
        onSuccess: (UploadedHighlightResult) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            Log.d(TAG, "uploadHighlight() called")
            Log.d(TAG, "Local file path: ${file.absolutePath}")
            Log.d(TAG, "Local file exists: ${file.exists()}")
            Log.d(TAG, "Local file size: ${if (file.exists()) file.length() else -1L}")
            Log.d(TAG, "Incoming matchId: $matchId")
            Log.d(TAG, "Incoming tag: $tag")
            Log.d(TAG, "Firebase bucket: ${storage.reference.bucket}")

            if (!file.exists()) {
                Log.e(TAG, "Upload aborted: file does not exist")
                onError("File does not exist")
                return
            }

            val uri = Uri.fromFile(file)
            val folder = matchId ?: "unknown_match"
            val safeTag = tag.ifBlank { "highlight" }
            val uniqueName = "${safeTag}_${UUID.randomUUID()}.mp4"
            val storagePath = "video_highlights/$folder/$uniqueName"
            val ref = storage.reference.child(storagePath)

            Log.d(TAG, "Resolved storagePath: $storagePath")
            Log.d(TAG, "Resolved storage ref path: ${ref.path}")

            ref.putFile(uri)
                .addOnSuccessListener { taskSnapshot ->
                    Log.d(TAG, "Upload success")
                    Log.d(TAG, "Uploaded bytes: ${taskSnapshot.metadata?.sizeBytes}")
                    Log.d(TAG, "Uploaded object path: ${taskSnapshot.metadata?.path}")
                    Log.d(TAG, "Uploaded object name: ${taskSnapshot.metadata?.name}")

                    val clipId = uniqueName.substringBeforeLast(".")

                    ref.downloadUrl
                        .addOnSuccessListener { downloadUri ->
                            Log.d(TAG, "Download URL success: $downloadUri")

                            onSuccess(
                                UploadedHighlightResult(
                                    clipId = clipId,
                                    storagePath = storagePath,
                                    downloadUrl = downloadUri.toString(),
                                    matchId = matchId
                                )
                            )
                        }
                        .addOnFailureListener { e ->
                            Log.e(
                                TAG,
                                "Download URL lookup failed for path $storagePath",
                                e
                            )

                            onSuccess(
                                UploadedHighlightResult(
                                    clipId = clipId,
                                    storagePath = storagePath,
                                    downloadUrl = "",
                                    matchId = matchId
                                )
                            )
                        }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Upload failed for path $storagePath", e)
                    onError(e.message ?: "Upload failed")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Upload exception", e)
            onError(e.message ?: "Upload exception")
        }
    }
}