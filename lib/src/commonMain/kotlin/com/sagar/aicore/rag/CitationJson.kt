/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Serializes a message's [Citation]s to/from a single JSON string for persistence,
 * so the source chips under a grounded answer survive reopening a saved chat.
 *
 * Uses the kotlinx-serialization JSON *runtime* (hand-built [buildJsonObject] /
 * [jsonArray]) rather than `@Serializable` codegen, so no serialization compiler
 * plugin is needed. Short keys keep the stored string compact; decoding tolerates
 * malformed/legacy data by returning an empty list rather than throwing.
 */
object CitationJson {

    fun encode(citations: List<Citation>): String {
        if (citations.isEmpty()) return ""
        val array = JsonArray(
            citations.map { c ->
                buildJsonObject {
                    put("d", c.documentId)
                    put("t", c.documentTitle)
                    put("p", c.pageNumber)
                    put("s", c.snippet)
                }
            },
        )
        return Json.encodeToString(JsonArray.serializer(), array)
    }

    fun decode(json: String?): List<Citation> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(json).jsonArray.map { element ->
                val obj = element.jsonObject
                Citation(
                    documentId = obj["d"]?.jsonPrimitive?.long ?: 0L,
                    documentTitle = obj["t"]?.jsonPrimitive?.content.orEmpty(),
                    pageNumber = obj["p"]?.jsonPrimitive?.int ?: 0,
                    snippet = obj["s"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }
}
