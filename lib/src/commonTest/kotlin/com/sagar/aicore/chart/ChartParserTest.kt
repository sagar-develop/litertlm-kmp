/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartParserTest {

    @Test fun parsesDonut() {
        val spec = ChartParser.parse("""{"type":"donut","title":"Spend","data":[{"label":"Rent","value":40},{"label":"Food","value":25}]}""")
        val donut = assertIs<ChartSpec.Donut>(spec)
        assertEquals("Spend", donut.title)
        assertEquals(2, donut.slices.size)
        assertEquals("Rent", donut.slices[0].label)
        assertEquals(40.0, donut.slices[0].value)
    }

    @Test fun parsesBarWithUnit() {
        val spec = ChartParser.parse("""{"type":"bar","unit":"k","data":[{"label":"Q1","value":10},{"label":"Q2","value":14}]}""")
        val bar = assertIs<ChartSpec.Bar>(spec)
        assertEquals("k", bar.unit)
        assertEquals(2, bar.bars.size)
    }

    @Test fun parsesProgressWithDefaultMax() {
        val spec = ChartParser.parse("""{"type":"progress","title":"Goal","value":72}""")
        val p = assertIs<ChartSpec.Progress>(spec)
        assertEquals(72.0, p.value)
        assertEquals(100.0, p.max) // default
    }

    @Test fun parsesLineFromFlatData() {
        val spec = ChartParser.parse("""{"type":"line","data":[{"label":"Jan","value":10},{"label":"Feb","value":14}]}""")
        val line = assertIs<ChartSpec.Line>(spec)
        assertEquals(1, line.series.size)
        assertEquals(2, line.series[0].points.size)
        assertEquals("Jan", line.series[0].points[0].x)
    }

    @Test fun parsesLineFromSeriesWithXY() {
        val spec = ChartParser.parse("""{"type":"growth","series":[{"name":"Rev","points":[{"x":"Jan","y":10},{"x":"Feb","y":14}]}]}""")
        val line = assertIs<ChartSpec.Line>(spec)
        assertEquals("Rev", line.series[0].name)
        assertEquals(14.0, line.series[0].points[1].y)
    }

    @Test fun acceptsTypeSynonyms() {
        assertIs<ChartSpec.Donut>(ChartParser.parse("""{"type":"pie","data":[{"label":"A","value":1},{"label":"B","value":2}]}"""))
        assertIs<ChartSpec.Bar>(ChartParser.parse("""{"type":"column","data":[{"label":"A","value":1}]}"""))
    }

    @Test fun acceptsNumericStringsAndPercent() {
        val bar = assertIs<ChartSpec.Bar>(ChartParser.parse("""{"type":"bar","data":[{"label":"A","value":"40"}]}"""))
        assertEquals(40.0, bar.bars[0].value)
        val p = assertIs<ChartSpec.Progress>(ChartParser.parse("""{"type":"progress","value":"72%"}"""))
        assertEquals(72.0, p.value)
    }

    @Test fun invalidOrUnknownReturnsNull() {
        assertNull(ChartParser.parse("not json"))
        assertNull(ChartParser.parse("""{"type":"scatter","data":[]}""")) // unknown type
        assertNull(ChartParser.parse("""{"type":"donut","data":[]}""")) // empty data
        assertNull(ChartParser.parse("""{"type":"line","data":[{"label":"only","value":1}]}""")) // needs >= 2 points
    }

    @Test fun skipsMalformedRowsButKeepsValidOnes() {
        val bar = assertIs<ChartSpec.Bar>(
            ChartParser.parse("""{"type":"bar","data":[{"label":"A","value":1},{"nope":true},{"label":"B","value":2}]}"""),
        )
        assertEquals(2, bar.bars.size)
        assertTrue(bar.bars.map { it.label } == listOf("A", "B"))
    }

    // kotlin.test has no assertIs in older versions on all targets; provide a tiny helper.
    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T, "expected ${T::class.simpleName} but was ${value?.let { it::class.simpleName }}")
        return value as T
    }
}
