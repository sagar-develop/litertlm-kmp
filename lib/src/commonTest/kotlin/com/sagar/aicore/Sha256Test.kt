/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for the streaming SHA-256 helper used by the model download flow.
 * Direct file I/O is not exercised here (commonTest is platform-agnostic);
 * the source-level test proves the streaming path is correct, and the
 * `sha256OfFile` wrapper is a one-line `fileSystem.source(path)` adapter.
 */
class Sha256Test {

    @Test
    fun matches_known_value_for_empty_input() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256OfSource(Buffer()),
        )
    }

    @Test
    fun matches_known_value_for_abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256OfSource(Buffer().writeUtf8("abc")),
        )
    }

    @Test
    fun same_input_yields_same_hash() {
        val a = sha256OfSource(Buffer().writeUtf8("hello world"))
        val b = sha256OfSource(Buffer().writeUtf8("hello world"))
        assertEquals(a, b)
    }

    @Test
    fun different_content_yields_different_hash() {
        val a = sha256OfSource(Buffer().writeUtf8("hello"))
        val b = sha256OfSource(Buffer().writeUtf8("world"))
        assertNotEquals(a, b)
    }

    @Test
    fun produces_lowercase_hex() {
        val hex = sha256OfSource(Buffer().writeUtf8("anything"))
        assertEquals(hex.lowercase(), hex)
        // 64 hex chars = 32 bytes = 256 bits.
        assertEquals(64, hex.length)
    }
}
