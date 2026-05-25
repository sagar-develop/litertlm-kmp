/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolSchemaConverterTest {

    private val json = Json

    @Test
    fun emits_OpenAPI_function_shape_with_name_description_parameters() {
        val def = ToolSchema.Definition(
            name = "extract_event_details",
            description = "Extract structured event details from a sentence.",
            parameters = listOf(
                ToolParameter("title", ToolParameterType.StringT, "Event title.", required = true),
                ToolParameter("duration_minutes", ToolParameterType.IntegerT, "Length in minutes.", required = true),
            ),
        )

        val parsed = json.parseToJsonElement(def.toOpenApiJson()).jsonObject

        assertEquals("extract_event_details", parsed["name"]?.jsonPrimitive?.content)
        assertEquals("Extract structured event details from a sentence.", parsed["description"]?.jsonPrimitive?.content)
        val params = parsed["parameters"]!!.jsonObject
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        val props = params["properties"]!!.jsonObject
        assertTrue("title" in props)
        assertTrue("duration_minutes" in props)
    }

    @Test
    fun maps_primitive_types_to_JSON_Schema_strings() {
        val def = ToolSchema.Definition(
            name = "t",
            description = "",
            parameters = listOf(
                ToolParameter("s", ToolParameterType.StringT, "", true),
                ToolParameter("i", ToolParameterType.IntegerT, "", true),
                ToolParameter("n", ToolParameterType.NumberT, "", true),
                ToolParameter("b", ToolParameterType.BooleanT, "", true),
            ),
        )

        val props = json.parseToJsonElement(def.toOpenApiJson()).jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("string", props["s"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["i"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("number", props["n"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["b"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun array_carries_items_subtype() {
        val def = ToolSchema.Definition(
            name = "t",
            description = "",
            parameters = listOf(
                ToolParameter("options", ToolParameterType.ArrayT(ToolParameterType.StringT), "Four strings.", true),
            ),
        )

        val opt = json.parseToJsonElement(def.toOpenApiJson())
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject["options"]!!.jsonObject

        assertEquals("array", opt["type"]!!.jsonPrimitive.content)
        assertEquals("string", opt["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun nested_array_of_arrays() {
        val def = ToolSchema.Definition(
            name = "t",
            description = "",
            parameters = listOf(
                ToolParameter(
                    "grid",
                    ToolParameterType.ArrayT(ToolParameterType.ArrayT(ToolParameterType.IntegerT)),
                    "",
                    true,
                ),
            ),
        )

        val grid = json.parseToJsonElement(def.toOpenApiJson())
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject["grid"]!!.jsonObject

        assertEquals("array", grid["type"]!!.jsonPrimitive.content)
        val inner = grid["items"]!!.jsonObject
        assertEquals("array", inner["type"]!!.jsonPrimitive.content)
        assertEquals("integer", inner["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun required_list_includes_only_required_params() {
        val def = ToolSchema.Definition(
            name = "t",
            description = "",
            parameters = listOf(
                ToolParameter("a", ToolParameterType.StringT, "", required = true),
                ToolParameter("b", ToolParameterType.StringT, "", required = false),
                ToolParameter("c", ToolParameterType.StringT, "", required = true),
            ),
        )

        val required: JsonArray = json.parseToJsonElement(def.toOpenApiJson())
            .jsonObject["parameters"]!!.jsonObject["required"]!!.jsonArray

        val names = required.map { (it as JsonPrimitive).content }
        assertEquals(listOf("a", "c"), names)
        assertFalse("b" in names)
    }
}
