/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.studio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Whether the podcast is actively speaking or paused. */
enum class PodcastStatus { SPEAKING, PAUSED }

/** Podcast playback session: the artifact, play state, and the turn currently spoken. */
data class PodcastPlayState(val artifactId: Long, val status: PodcastStatus, val turnIndex: Int)

/**
 * Two-voice playback of a parsed podcast over Android's on-device [TextToSpeech] —
 * Studio Step 7b. Each [PodcastTurn] is spoken in turn order; the two speakers get two
 * **distinct** voices when the engine offers them, plus a small pitch offset so they
 * always sound different even on a single-voice engine. Turns are spoken one at a time
 * (the next is queued from the previous turn's `onDone`) so each gets the right voice —
 * mid-queue voice switches aren't reliable across engines. Everything stays on-device.
 *
 * Own one instance with an Application context and call [shutdown] from onCleared.
 */
class PodcastController(
    context: Context,
    private val onUnavailable: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<PodcastPlayState?>(null)
    val state: StateFlow<PodcastPlayState?> = _state.asStateFlow()

    private var tts: TextToSpeech? = null

    @Volatile private var ready = false

    @Volatile private var failed = false

    private var turns: List<PodcastTurn> = emptyList()
    private var cursor = 0
    private var artifactId = 0L
    private var voiceA: Voice? = null
    private var voiceB: Voice? = null
    private var pending: Pair<Long, List<PodcastTurn>>? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    engine.setLanguage(Locale.US)
                    pickVoices(engine)
                    engine.setOnUtteranceProgressListener(listener)
                    ready = true
                    pending?.let { (id, t) ->
                        pending = null
                        start(id, t)
                    }
                }
            } else {
                failed = true
                onUnavailable()
            }
        }
    }

    /** Choose two distinct on-device English voices (best effort; may be the same one). */
    private fun pickVoices(engine: TextToSpeech) {
        val usable = runCatching {
            engine.voices
                ?.filter { v ->
                    v.locale.language == Locale.US.language &&
                        !v.isNetworkConnectionRequired &&
                        v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                }
                ?.sortedBy { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())
        voiceA = usable.firstOrNull { it.locale == Locale.US } ?: usable.firstOrNull()
        voiceB = usable.firstOrNull { it.name != voiceA?.name } ?: voiceA
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.toIntOrNull()?.let { cursor = it }
        }

        override fun onDone(utteranceId: String?) {
            val idx = utteranceId?.toIntOrNull() ?: return
            // Only advance from the turn we think is current (ignore stale callbacks
            // after a stop/flush). Speak the next turn, or end at the last one.
            if (_state.value?.status != PodcastStatus.SPEAKING || idx != cursor) return
            if (idx >= turns.lastIndex) {
                cursor = 0
                _state.value = null
            } else {
                speakTurn(idx + 1)
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
        }
    }

    /** Play/pause [turns] for artifact [id]. Same artifact toggles; a new one restarts. */
    fun toggle(id: Long, turns: List<PodcastTurn>) {
        if (failed) {
            onUnavailable()
            return
        }
        if (turns.isEmpty()) return
        if (!ready) {
            pending = id to turns
            return
        }
        val cur = _state.value
        when {
            cur?.artifactId == id && cur.status == PodcastStatus.SPEAKING -> pause()
            cur?.artifactId == id && cur.status == PodcastStatus.PAUSED -> resume()
            else -> start(id, turns)
        }
    }

    private fun start(id: Long, t: List<PodcastTurn>) {
        turns = t
        if (turns.isEmpty()) return
        artifactId = id
        cursor = 0
        _state.value = PodcastPlayState(id, PodcastStatus.SPEAKING, 0)
        speakTurn(0)
    }

    private fun speakTurn(i: Int) {
        val engine = tts ?: return
        if (i !in turns.indices) return
        val turn = turns[i]
        val voice = if (turn.speaker == 0) voiceA else voiceB
        if (voice != null) engine.voice = voice
        // Guaranteed differentiator even when only one voice exists: a small pitch offset.
        engine.setPitch(if (turn.speaker == 0) 1.08f else 0.92f)
        engine.setSpeechRate(1.0f)
        engine.speak(turn.text.take(3900), TextToSpeech.QUEUE_FLUSH, null, i.toString())
        _state.value = _state.value?.copy(turnIndex = i, status = PodcastStatus.SPEAKING)
    }

    private fun pause() {
        tts?.stop() // cursor already points at the interrupted turn
        _state.value = _state.value?.copy(status = PodcastStatus.PAUSED)
    }

    private fun resume() {
        speakTurn(cursor)
    }

    /** Stop playback and clear the session (e.g. leaving the artifact viewer). */
    fun stop() {
        tts?.stop()
        turns = emptyList()
        cursor = 0
        _state.value = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
