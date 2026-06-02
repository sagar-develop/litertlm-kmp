/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.studio

/**
 * The kinds of Studio artifact that can be generated from a project's sources.
 * Persisted by [name] in `StudioArtifactEntity.type`. The sequence grows
 * easiest→hardest (see `docs/STUDIO_PLAN.md`); v1 ships [BRIEFING] only.
 */
enum class StudioArtifactType(val label: String) {
    BRIEFING("Briefing"),
    FAQ("FAQ"),
    KEY_TOPICS("Key Topics"),
    STUDY_GUIDE("Study Guide"),
    TIMELINE("Timeline"),
    MIND_MAP("Mind Map"),
    AUDIO_OVERVIEW("Audio Overview");

    companion object {
        fun fromName(name: String): StudioArtifactType =
            entries.firstOrNull { it.name == name } ?: BRIEFING
    }
}
