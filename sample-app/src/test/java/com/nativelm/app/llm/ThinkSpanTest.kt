/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

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
        // Mid-reasoning: block opened, not yet closed → empty (bubble shows typing dots).
        assertEquals("", renderAssistantText("<think> still reasoning about the question"))
    }

    @Test fun partialOpeningTagShowsNothing() {
        // The opening tag itself streams in token-by-token.
        assertEquals("", renderAssistantText("<thi"))
        assertEquals("", renderAssistantText("<think"))
    }

    @Test fun nonThinkAngleBracketAnswerIsKept() {
        // An answer that legitimately starts with '<' must not be suppressed.
        assertEquals("<html> tag example", renderAssistantText("<html> tag example"))
    }
}
