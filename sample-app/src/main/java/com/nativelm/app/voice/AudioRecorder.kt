/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures microphone audio as **16 kHz mono PCM**, normalized to float `[-1, 1]` — exactly
 * what Whisper expects. Records into a growing buffer while active; [stop] returns the
 * collected samples for transcription. The audio never leaves the device.
 *
 * The caller must hold `RECORD_AUDIO` before [start].
 */
class AudioRecorder {

    private var record: AudioRecord? = null
    @Volatile
    private var recording = false
    private var readJob: Job? = null
    private val pcm = ArrayList<Float>()

    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission") // caller guarantees RECORD_AUDIO is granted
    fun start(scope: CoroutineScope) {
        if (recording) return
        synchronized(pcm) { pcm.clear() }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(SAMPLE_RATE) // ≥ ~0.5s of 16-bit samples
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, minBuf)
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { ar.release() }
            error("Microphone unavailable.")
        }
        record = ar
        ar.startRecording()
        recording = true
        readJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(minBuf / 2)
            while (isActive && recording) {
                val n = ar.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(pcm) {
                        for (i in 0 until n) pcm.add(buf[i] / 32768f)
                    }
                }
            }
        }
    }

    /** Stop recording and return the captured 16 kHz mono float samples. */
    suspend fun stop(): FloatArray {
        recording = false
        readJob?.cancelAndJoin()
        readJob = null
        releaseRecord()
        return synchronized(pcm) { pcm.toFloatArray() }
    }

    /** Abort recording and discard the buffer (e.g. user cancels). */
    fun cancel() {
        recording = false
        readJob?.cancel()
        readJob = null
        releaseRecord()
        synchronized(pcm) { pcm.clear() }
    }

    private fun releaseRecord() {
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
