/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

/** CRUD over projects (notebooks). Sources + the project's chat are scoped by id elsewhere. */
class ProjectRepository {

    private val projects = ObjectBox.store.boxFor(ProjectEntity::class.java)

    fun list(): List<ProjectEntity> =
        projects.query().orderDesc(ProjectEntity_.updatedAt).build().use { it.find() }

    fun get(id: Long): ProjectEntity? = projects.get(id)

    fun create(name: String, now: Long): Long =
        projects.put(
            ProjectEntity().apply {
                this.name = name
                createdAt = now
                updatedAt = now
            },
        )

    fun rename(id: Long, name: String) {
        projects.get(id)?.let {
            it.name = name
            it.updatedAt = System.currentTimeMillis()
            projects.put(it)
        }
    }

    fun touch(id: Long, now: Long) {
        projects.get(id)?.let {
            it.updatedAt = now
            projects.put(it)
        }
    }

    fun delete(id: Long) = projects.remove(id)
}
