/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkSpanTest {

    @Test fun plainTextIsUnchanged() {
        assertEquals("Red, Blue, and Yellow.", renderAssistantText("Red, Blue, and Yellow."))
    }

    @Test fun closedThinkBlockKeepsOnlyTheAnswer() {
        val raw = "<think> The user wants primary colors. red, blue, yellow. </think>\nRed, Blue, and Yellow."
        assertEquals("Red, Blue, and Yellow.", renderAssistantText(raw))
    }

    @Test fun multilineThinkIsStripped() {
        val raw = "<think>\nstep 1\nstep 2\n</think>   The answer is 42."
        assertEquals("The answer is 42.", renderAssistantText(raw))
    }

    @Test fun openThinkStillStreamingShowsNothing() {
        assertEquals("", renderAssistantText("<think> still reasoning about the question"))
    }

    @Test fun partialOpeningTagShowsNothing() {
        assertEquals("", renderAssistantText("<thi"))
        assertEquals("", renderAssistantText("<think"))
    }

    @Test fun nonThinkAngleBracketAnswerIsKept() {
        assertEquals("<html> tag example", renderAssistantText("<html> tag example"))
    }

    @Test fun customTagsAreHonored() {
        assertEquals("answer", renderAssistantText("<reason>x</reason>answer", openTag = "<reason>", closeTag = "</reason>"))
    }
}
