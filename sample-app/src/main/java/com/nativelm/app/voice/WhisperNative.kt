/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.voice

/**
 * JNI bindings to `libwhisper.so` (whisper.cpp). The function + class names here must match
 * the symbol mangling in `whisper_jni.c`. Do not rename without updating the native side.
 */
internal class WhisperNative {
    companion object {
        init {
            System.loadLibrary("whisper")
        }

        /** Load a Whisper model file; returns a native context pointer (0 on failure). */
        external fun initContext(modelPath: String): Long

        /** Release the native context. */
        external fun freeContext(ctxPtr: Long)

        /** Run transcription over 16 kHz mono float PCM. [language] is a code or "auto". */
        external fun fullTranscribe(ctxPtr: Long, numThreads: Int, audio: FloatArray, language: String?)

        external fun segmentCount(ctxPtr: Long): Int
        external fun segmentText(ctxPtr: Long, index: Int): String
        external fun systemInfo(): String
    }
}
