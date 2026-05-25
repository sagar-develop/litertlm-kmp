package com.sagar.aicore

import kotlinx.coroutines.flow.Flow

/**
 * Platform speech-to-text. Android wraps `android.speech.SpeechRecognizer`
 * (uses the system recognizer that ships with Google services). iOS/desktop
 * not wired yet — those phases provide their own `actual`.
 *
 * MedGemma is Gemma 3-based and does NOT have native audio, so dictation
 * uses platform STT and feeds the transcript text into the LLM as a prompt
 * (per the source plan revision noted in CLAUDE.md context).
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
