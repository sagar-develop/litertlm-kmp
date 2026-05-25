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
 *   "name": "submit_quiz_question",
 *   "description": "...",
 *   "parameters": {
 *     "type": "object",
 *     "properties": {
 *       "question_text": {"type": "string", "description": "..."},
 *       "options": {"type": "array", "items": {"type": "string"}, "description": "..."},
 *       "correct_answer_index": {"type": "integer", "description": "..."}
 *     },
 *     "required": ["question_text", "options", "correct_answer_index"]
 *   }
 * }
 * ```
 *
 * Parameter names pass through verbatim — orchestrator code uses snake_case
 * to match the schema Gemma 4 sees (LiteRT-LM also accepts camelCase but
 * snake_case keeps round-trip with [EngineState.ToolCallEmitted.arguments]
 * cleaner).
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
