/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

/**
 * On-device speech-to-text (voice input) — transcribes dictated audio to text **fully
 * offline**, no cloud, no Google component. Backed by Whisper (whisper.cpp) on Android;
 * iOS can wrap the same library later.
 *
 * Shared in the engine so every consumer (NativeLM, the kids app, …) reuses one
 * implementation. The Android factory lives in the platform source set
 * (`createWhisperSpeechToText`), since constructing it loads a native library + a Whisper
 * model file from the [ModelRole.SPEECH_TO_TEXT] catalog entry.
 */
interface SpeechToText {

    /**
     * Transcribe [pcm16k] — 16 kHz, mono, normalized float samples in [-1, 1] — to text.
     * [languageCode] is a BCP-47 hint (e.g. "hi", "es"); null lets the model auto-detect.
     * Runs on a background dispatcher; cancellation propagates.
     */
    suspend fun transcribe(pcm16k: FloatArray, languageCode: String? = null): String

    /** Release the native context. Call when voice input is no longer needed. */
    fun close()
}
