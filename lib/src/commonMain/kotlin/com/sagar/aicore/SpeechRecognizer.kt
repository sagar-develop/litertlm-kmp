/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import kotlinx.coroutines.flow.Flow

/**
 * Platform speech-to-text. Android wraps `android.speech.SpeechRecognizer`
 * (uses the system recognizer that ships with Google services). iOS / other
 * platforms provide their own `actual`.
 *
 * Useful when feeding voice input into a text-only LLM: capture transcript
 * here, then pass the text into [LocalAiEngine.generateStream]. Gemma-family
 * models on LiteRT-LM are text-only at this writing, so audio prompts must
 * route through platform STT.
 */
interface SpeechRecognizer {
    /** Hot flow of recognizer lifecycle + transcript events. Replays the latest state to new collectors. */
    val state: Flow<SpeechRecognizerState>

    /**
     * Begin listening. [languageTag] is BCP-47 (e.g. "en-IN", "hi-IN",
     * "ta-IN"). Partial results stream via [SpeechRecognizerState.PartialResult];
     * a final transcript arrives via [SpeechRecognizerState.FinalResult] when
     * the user stops speaking or [stop] is called.
     */
    fun start(languageTag: String = "en-IN")

    /** Stop listening; the recognizer flushes its buffer and emits a `FinalResult`. */
    fun stop()

    /** Release native resources; safe to call repeatedly. */
    fun release()
}

sealed class SpeechRecognizerState {
    object Idle : SpeechRecognizerState()
    object Listening : SpeechRecognizerState()
    /** Streamed during recognition; the recognizer may revise it before final. */
    data class PartialResult(val text: String) : SpeechRecognizerState()
    /** End-of-utterance transcript. After this, the recognizer returns to Idle. */
    data class FinalResult(val text: String) : SpeechRecognizerState()
    data class Error(val code: Int, val message: String) : SpeechRecognizerState()
    /** No on-device recognizer available, or RECORD_AUDIO permission missing. */
    object NotAvailable : SpeechRecognizerState()
}
