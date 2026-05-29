/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * NativeLM brand palette. Sage-green accent used sparingly over a warm
 * off-white (light) / warm-dark (dark) canvas. See CLAUDE.md locked decisions.
 */

// Brand accent — the one sage-green, used for primary actions / active state.
val Sage = Color(0xFF7FA980)

// ---- Light ----
val LightBackground = Color(0xFFFAF9F6) // off-white canvas
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEFEDE8) // warm light grey card/fill
val LightOnBackground = Color(0xFF1C1B1A) // warm near-black
val LightOnSurfaceVariant = Color(0xFF6B6862) // muted warm grey (secondary text)
val LightOutline = Color(0xFFDAD8D2) // hairline divider/border
val LightPrimaryContainer = Color(0xFFE9F0E9) // soft sage tint (user bubble, selected)
val LightOnPrimaryContainer = Color(0xFF2E3B2E)
val LightError = Color(0xFFBA4A42) // restrained red

// ---- Dark (warm) ----
val DarkBackground = Color(0xFF1C1B1A)
val DarkSurface = Color(0xFF232220)
val DarkSurfaceVariant = Color(0xFF2C2A27)
val DarkOnBackground = Color(0xFFECEAE4)
val DarkOnSurfaceVariant = Color(0xFFA8A49C)
val DarkOutline = Color(0xFF3A3833)
val DarkPrimaryContainer = Color(0xFF2E3B2E)
val DarkOnPrimaryContainer = Color(0xFFC8E0C8)
val DarkError = Color(0xFFE5938B)
