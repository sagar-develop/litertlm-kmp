/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag.extract

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Recognizes text in a [Bitmap]. Lets the extractor turn scanned/photographed
 * pages — which carry no selectable text layer — into something the RAG pipeline
 * can chunk, embed, and retrieve.
 */
interface OcrEngine {
    /** Returns the recognized text (reading order), or "" when none is found. */
    suspend fun recognize(bitmap: Bitmap): String
}

/**
 * [OcrEngine] backed by ML Kit's on-device Latin text recognizer. The model is
 * bundled in the APK, so recognition runs fully locally with no Play Services
 * download and no image ever leaving the device — consistent with the
 * no-upload promise. (ML Kit is a closed-source Google library; that trade-off
 * was made deliberately for accuracy.)
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}
