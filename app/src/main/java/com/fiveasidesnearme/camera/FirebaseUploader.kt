package com.fiveasidesnearme.camera

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import java.io.File

object FirebaseUploader {

    private val storage = FirebaseStorage.getInstance()

    fun uploadHighlight(
        file: File,
        matchId: String?,
        tag: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val uri = Uri.fromFile(file)

            val folder = matchId ?: "unknown_match"
            val fileName = file.name

            val ref = storage.reference
                .child("highlights/$folder/$fileName")

            ref.putFile(uri)
                .addOnSuccessListener {
                    onSuccess()
                }
                .addOnFailureListener { e ->
                    onError(e.message ?: "Upload failed")
                }

        } catch (e: Exception) {
            onError(e.message ?: "Upload exception")
        }
    }
}