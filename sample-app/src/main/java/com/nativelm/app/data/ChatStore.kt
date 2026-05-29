/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data

import android.content.Context
import com.nativelm.app.llm.ChatMessage
import io.github.aakira.napier.Napier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the current conversation to a small JSON file in app storage so it
 * survives process death / app restart. Single conversation for now; a
 * multi-conversation history list can come later. Uses org.json (built-in) — no
 * extra dependency.
 */
class ChatStore(context: Context) {

    private val file = File(context.filesDir, "chat_history.json")

    fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val role = if (o.getString("role") == "user") ChatMessage.Role.User else ChatMessage.Role.Assistant
                    val text = o.getString("text")
                    if (text.isNotEmpty()) add(ChatMessage(role = role, text = text, id = i.toLong()))
                }
            }
        }.getOrElse {
            Napier.w(it) { "Failed to load chat history" }
            emptyList()
        }
    }

    fun save(messages: List<ChatMessage>) {
        runCatching {
            val arr = JSONArray()
            messages.filter { it.text.isNotEmpty() }.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("role", if (m.role == ChatMessage.Role.User) "user" else "assistant")
                        .put("text", m.text),
                )
            }
            file.writeText(arr.toString())
        }.onFailure { Napier.w(it) { "Failed to save chat history" } }
    }

    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }
}
