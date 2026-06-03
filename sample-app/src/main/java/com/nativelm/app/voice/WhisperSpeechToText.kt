/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.voice

import com.sagar.aicore.SpeechToText
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * [SpeechToText] backed by on-device Whisper (whisper.cpp). Loads a Whisper GGML model and
 * transcribes 16 kHz mono float PCM entirely on-device — no network, no Google component.
 *
 * whisper.cpp requires single-threaded access to a context, so all native calls run on a
 * dedicated single-thread dispatcher.
 */
class WhisperSpeechToText private constructor(private var ctxPtr: Long) : SpeechToText {

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    override suspend fun transcribe(pcm16k: FloatArray, languageCode: String?): String =
        withContext(dispatcher) {
            check(ctxPtr != 0L) { "Whisper context already released." }
            if (pcm16k.isEmpty()) return@withContext ""
            val language = languageCode?.takeIf { it.isNotBlank() } ?: "auto"
            val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)
            WhisperNative.fullTranscribe(ctxPtr, threads, pcm16k, language)
            val count = WhisperNative.segmentCount(ctxPtr)
            buildString {
                for (i in 0 until count) append(WhisperNative.segmentText(ctxPtr, i))
            }.trim()
        }

    override fun close() {
        if (ctxPtr != 0L) {
            WhisperNative.freeContext(ctxPtr)
            ctxPtr = 0L
        }
        dispatcher.close()
    }

    companion object {
        /** Create from a Whisper GGML model file on disk. Throws if the model can't be loaded. */
        fun fromModelFile(path: String): WhisperSpeechToText {
            val ptr = WhisperNative.initContext(path)
            require(ptr != 0L) { "Couldn't load the Whisper model at $path" }
            return WhisperSpeechToText(ptr)
        }
    }
}
