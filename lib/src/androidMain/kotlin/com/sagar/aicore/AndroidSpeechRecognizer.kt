/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSttClient
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import com.sagar.aicore.di.AppScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject

/**
 * Android implementation of [SpeechRecognizer]. Uses the system
 * `android.speech.SpeechRecognizer`; on most devices that's the Google
 * recognizer backed by on-device models for the configured language.
 *
 * Caller must hold `RECORD_AUDIO`; emit [SpeechRecognizerState.NotAvailable]
 * if not granted so the UI can route to a permission request.
 *
 * The Android STT API is callback-based on the main looper â€” we marshal
 * lifecycle calls to the main thread, then surface state via a
 * [MutableStateFlow] for the ViewModel to collect.
 */
@AppScope
@Inject
class AndroidSpeechRecognizer(
    private val context: Context,
) : SpeechRecognizer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<SpeechRecognizerState>(SpeechRecognizerState.Idle)
    override val state: Flow<SpeechRecognizerState> = _state.asStateFlow()

    private var client: AndroidSttClient? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = SpeechRecognizerState.Listening
        }
        override fun onBeginningOfSpeech() { /* covered by Listening */ }
        override fun onRmsChanged(rmsdB: Float) { /* surface this if you want a level meter */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* unused */ }
        override fun onEndOfSpeech() { /* covered by FinalResult */ }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = extractFirstTranscript(partialResults)
            if (text != null) {
                _state.value = SpeechRecognizerState.PartialResult(text)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = extractFirstTranscript(results).orEmpty()
            _state.value = SpeechRecognizerState.FinalResult(text)
            _state.value = SpeechRecognizerState.Idle
        }

        override fun onError(error: Int) {
            val msg = errorMessage(error)
            Napier.w(tag = TAG) { "STT error $error: $msg" }
            _state.value = SpeechRecognizerState.Error(error, msg)
            _state.value = SpeechRecognizerState.Idle
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* unused */ }
    }

    override fun start(languageTag: String) {
        if (!hasRecordAudioPermission()) {
            _state.value = SpeechRecognizerState.NotAvailable
            return
        }
        // Skip isRecognitionAvailable() â€” under Android 11+ package
        // visibility rules it can return false even when a recognizer is
        // present and `setRecognitionListener` works fine. If creation
        // fails, the listener surfaces ERROR_CLIENT and we report that.

        mainHandler.post {
            val recognizer = client ?: AndroidSttClient.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
                client = it
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Long-form voice capture: allow up to 5s of silence before
                // auto-stop (default ~1s is too aggressive when the speaker
                // pauses to think).
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5_000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5_000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000)
            }
            recognizer.startListening(intent)
        }
    }

    override fun stop() {
        mainHandler.post {
            client?.stopListening()
        }
    }

    override fun release() {
        mainHandler.post {
            client?.destroy()
            client = null
            _state.value = SpeechRecognizerState.Idle
        }
    }

    private fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun extractFirstTranscript(bundle: Bundle?): String? {
        val list = bundle?.getStringArrayList(AndroidSttClient.RESULTS_RECOGNITION)
        return list?.firstOrNull()
    }

    private fun errorMessage(code: Int): String = when (code) {
        AndroidSttClient.ERROR_AUDIO -> "Audio recording error"
        AndroidSttClient.ERROR_CLIENT -> "Client side error"
        AndroidSttClient.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing microphone permission"
        AndroidSttClient.ERROR_NETWORK -> "Network error"
        AndroidSttClient.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        AndroidSttClient.ERROR_NO_MATCH -> "No speech recognized"
        AndroidSttClient.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        AndroidSttClient.ERROR_SERVER -> "Server error"
        AndroidSttClient.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Speech recognition error ($code)"
    }

    private companion object { const val TAG = "AndroidSTT" }
}
