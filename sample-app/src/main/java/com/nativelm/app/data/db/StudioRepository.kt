/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CRUD over [StudioArtifactEntity] — the Studio artifacts (Briefings, etc.)
 * generated from a project's sources. Same manual-DI, direct-Box style as
 * [ProjectRepository] / [ConversationRepository]; heavy ops are `suspend` on
 * [Dispatchers.IO].
 */
class StudioRepository {

    private val artifacts = ObjectBox.store.boxFor(StudioArtifactEntity::class.java)

    /** Artifacts in [projectId], newest first. */
    suspend fun list(projectId: Long): List<StudioArtifactEntity> = withContext(Dispatchers.IO) {
        artifacts.query()
            .equal(StudioArtifactEntity_.projectId, projectId)
            .orderDesc(StudioArtifactEntity_.createdAt)
            .build()
            .use { it.find() }
    }

    suspend fun get(id: Long): StudioArtifactEntity? = withContext(Dispatchers.IO) { artifacts.get(id) }

    /** Insert or update; returns the row id. */
    suspend fun put(entity: StudioArtifactEntity): Long =
        withContext(Dispatchers.IO) { artifacts.put(entity) }

    suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) { artifacts.remove(id) }

    /** Remove every artifact of [projectId] — called when a project is deleted. */
    suspend fun deleteForProject(projectId: Long): Unit = withContext(Dispatchers.IO) {
        artifacts.query().equal(StudioArtifactEntity_.projectId, projectId).build().use { it.remove() }
    }
}
