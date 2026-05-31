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

    @Test fun sixGbDeviceIsOfferedOnlyTheSmallInt4Llm() {
        val ram = 6000L
        val supported = catalog.byRole(ModelRole.LLM_PRIMARY)
            .filter { ram >= it.minDeviceRamMb }
            .map { it.id }
        assertEquals(listOf("gemma3-1b-it-int4-litertlm"), supported)
    }

    @Test fun ramTiersAreGraduatedNotJustSixAndTen() {
        val tiers = catalog.byRole(ModelRole.LLM_PRIMARY).map { it.minDeviceRamMb }.toSet()
        // More than the old two thresholds (6000 / 10000).
        assertTrue("expected graduated tiers, got $tiers", tiers.size >= 3)
        assertTrue(tiers.contains(4000L))
        assertTrue(tiers.contains(10000L))
    }
}
