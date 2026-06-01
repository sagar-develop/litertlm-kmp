/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import com.sagar.aicore.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLmModelCatalogTest {

    private val catalog = NativeLmModelCatalog()

    @Test fun lowRamModelIsTextOnlyInt4() {
        val m = catalog.byId("gemma3-1b-it-int4-litertlm")!!
        assertFalse("1B is text-only — no vision backend", m.supportsVision)
        assertEquals(4000L, m.minDeviceRamMb)
        assertTrue(m.fileName.endsWith(".litertlm"))
    }

    @Test fun e2bIsMultimodalAndGatedAboveSixGb() {
        val m = catalog.byId("gemma-4-e2b-it-litertlm")!!
        assertTrue("E2B bundle is multimodal", m.supportsVision)
        // 6 GB phones report ~5.9 GB; the gate must sit above that and below a
        // real 8 GB device's ~7.6 GB so E2B stays available there.
        assertTrue("E2B must NOT be offered to 6GB devices", m.minDeviceRamMb in 6001L..7600L)
    }

    @Test fun sixGbDeviceGetsSmallModelsNotE2bOrLarger() {
        val ram = 6000L
        val supported = catalog.byRole(ModelRole.LLM_PRIMARY)
            .filter { ram >= it.minDeviceRamMb }
            .map { it.id }
        // 6 GB is offered the small tiers, never the multimodal Gemma 4 or flagship.
        assertTrue("entry Qwen3 offered", "qwen3-0_6b-litertlm" in supported)
        assertTrue("Gemma 3 1B offered", "gemma3-1b-it-int4-litertlm" in supported)
        assertTrue("E2B must NOT be offered to 6GB", "gemma-4-e2b-it-litertlm" !in supported)
        assertTrue("E4B must NOT be offered to 6GB", "gemma-4-e4b-it-litertlm" !in supported)
        assertTrue("flagship must NOT be offered to 6GB", "qwen3-4b-litertlm" !in supported)
    }

    @Test fun entryModelIsNonGemmaUngatedText() {
        val m = catalog.byId("qwen3-0_6b-litertlm")!!
        assertFalse("entry model must be ungated (no token/license friction)", m.requiresAuth)
        assertFalse("entry model is text-only", m.supportsVision)
        assertTrue("entry is the smallest LLM tier", m.minDeviceRamMb <= 4000L)
    }

    @Test fun catalogueSpansEntryToFlagship() {
        val llms = catalog.byRole(ModelRole.LLM_PRIMARY)
        // A real cross-device range: many tiers, all .litertlm, a mix of gated +
        // ungated and Gemma + non-Gemma.
        assertTrue("expected a broad lineup, got ${llms.size}", llms.size >= 6)
        assertTrue(llms.all { it.fileName.endsWith(".litertlm") })
        assertTrue("has ungated (non-Gemma) models", llms.any { !it.requiresAuth })
        assertTrue("keeps gated Gemma models", llms.any { it.requiresAuth })
        assertTrue("has a multimodal model", llms.any { it.supportsVision })
        val tiers = llms.map { it.minDeviceRamMb }.toSet()
        assertTrue("graduated tiers, got $tiers", tiers.size >= 5)
    }
}
