/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Converts the engine-agnostic [ToolSchema.Definition] into an OpenAPI 3.0
 * function-spec JSON string consumable by LiteRT-LM's `OpenApiTool`.
 *
 * Output shape (matches the OpenAI / Google function-calling convention):
 * ```json
 * {
 *   "name": "extract_event_details",
 *   "description": "...",
 *   "parameters": {
 *     "type": "object",
 *     "properties": {
 *       "title": {"type": "string", "description": "..."},
 *       "attendees": {"type": "array", "items": {"type": "string"}, "description": "..."},
 *       "duration_minutes": {"type": "integer", "description": "..."}
 *     },
 *     "required": ["title", "attendees", "duration_minutes"]
 *   }
 * }
 * ```
 *
 * Parameter names pass through verbatim. Prefer snake_case in your
 * [ToolSchema.Definition] params â€” LiteRT-LM also accepts camelCase but
 * snake_case keeps round-trip with [EngineState.ToolCallEmitted.arguments]
 * cleaner.
 */
internal val ToolSchemaJson: Json = Json { prettyPrint = false }

fun ToolSchema.Definition.toOpenApiJson(): String {
    val obj = buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                for (param in parameters) {
                    put(param.name, buildJsonObject {
                        writeType(param.type)
                        put("description", param.description)
                    })
                }
            })
            put("required", buildJsonArray {
                for (param in parameters) {
                    if (param.required) add(param.name)
                }
            })
        })
    }
    return ToolSchemaJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
}

private fun JsonObjectBuilder.writeType(type: ToolParameterType) {
    when (type) {
        ToolParameterType.StringT -> put("type", "string")
        ToolParameterType.IntegerT -> put("type", "integer")
        ToolParameterType.NumberT -> put("type", "number")
        ToolParameterType.BooleanT -> put("type", "boolean")
        is ToolParameterType.ArrayT -> {
            put("type", "array")
            put("items", buildJsonObject { writeType(type.itemType) })
        }
    }
}
