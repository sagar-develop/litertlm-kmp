/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.studio
import com.sagar.aicore.studio.stripForSpeech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Whether read-aloud is actively speaking [artifactId] or paused on it. */
enum class ReadStatus { SPEAKING, PAUSED }

/** Read-aloud session: which artifact is loaded and its play state. null = nothing playing. */
data class ReadAloudState(val artifactId: Long, val status: ReadStatus)

/**
 * Single-voice read-aloud over Android's on-device [TextToSpeech] — Studio Step 7a.
 *
 * The artifact text is split into sentence-sized utterances and queued; a progress
 * listener tracks the sentence currently speaking so pause/resume works (resume
 * restarts the interrupted sentence, which the platform can't continue mid-utterance).
 * All speech stays on-device; nothing is uploaded. Own one instance with an
 * Application context and call [shutdown] from the ViewModel's onCleared.
 */
class TtsController(
    context: Context,
    /** Invoked once if no usable TTS engine is present, and on a toggle thereafter. */
    private val onUnavailable: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<ReadAloudState?>(null)
    val state: StateFlow<ReadAloudState?> = _state.asStateFlow()

    private var tts: TextToSpeech? = null

    @Volatile private var ready = false

    @Volatile private var failed = false

    // The active read session.
    private var sentences: List<String> = emptyList()
    private var cursor = 0 // index of the sentence currently/next spoken
    private var pending: Pair<Long, String>? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val locale = if (engine.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE) {
                        Locale.US
                    } else {
                        Locale.getDefault()
                    }
                    engine.setLanguage(locale)
                    engine.setOnUtteranceProgressListener(listener)
                    ready = true
                    pending?.let { (id, text) ->
                        pending = null
                        start(id, text)
                    }
                }
            } else {
                failed = true
                onUnavailable()
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            // Track the sentence in progress so a pause can resume from it.
            utteranceId?.toIntOrNull()?.let { cursor = it }
        }

        override fun onDone(utteranceId: String?) {
            val idx = utteranceId?.toIntOrNull() ?: return
            if (idx >= sentences.lastIndex) {
                cursor = 0
                _state.value = null
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
        }
    }

    /**
     * Play/pause toggle for artifact [id] reading [text]. Tapping the same artifact
     * pauses or resumes it; a different artifact (or none playing) starts fresh.
     */
    fun toggle(id: Long, text: String) {
        if (failed) {
            onUnavailable()
            return
        }
        if (!ready) {
            pending = id to text // flushed when init completes
            return
        }
        val cur = _state.value
        when {
            cur?.artifactId == id && cur.status == ReadStatus.SPEAKING -> pause()
            cur?.artifactId == id && cur.status == ReadStatus.PAUSED -> resume()
            else -> start(id, text)
        }
    }

    private fun start(id: Long, text: String) {
        sentences = splitForSpeech(text)
        if (sentences.isEmpty()) return
        cursor = 0
        enqueueFrom(0)
        _state.value = ReadAloudState(id, ReadStatus.SPEAKING)
    }

    private fun enqueueFrom(from: Int) {
        val engine = tts ?: return
        for (i in from until sentences.size) {
            val mode = if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(sentences[i], mode, null, i.toString())
        }
    }

    private fun pause() {
        tts?.stop() // cursor already points at the interrupted sentence (set in onStart)
        _state.value = _state.value?.copy(status = ReadStatus.PAUSED)
    }

    private fun resume() {
        enqueueFrom(cursor)
        _state.value = _state.value?.copy(status = ReadStatus.SPEAKING)
    }

    /** Stop any playback and clear the session (e.g. leaving the artifact viewer). */
    fun stop() {
        tts?.stop()
        sentences = emptyList()
        cursor = 0
        _state.value = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

/**
 * Break already-plain text into sentence-sized utterances: split on blank lines,
 * then on sentence-ending punctuation, capping each chunk well under the platform's
 * per-utterance input limit. Keeps natural pauses between sentences.
 */
private fun splitForSpeech(text: String): List<String> {
    val out = ArrayList<String>()
    for (para in text.split(Regex("\\n+"))) {
        val p = para.trim()
        if (p.isEmpty()) continue
        for (s in p.split(Regex("(?<=[.!?])\\s+"))) {
            val sentence = s.trim()
            if (sentence.isNotEmpty()) out.add(sentence.take(3900))
        }
    }
    return out
}
