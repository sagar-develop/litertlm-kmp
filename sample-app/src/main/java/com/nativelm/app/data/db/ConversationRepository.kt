/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

/**
 * CRUD over conversations + their messages, scoped by [ConversationEntity.projectId]
 * (0 = default general chats; a project id = that project's single grounded chat).
 * Messages link by [MessageEntity.conversationId]; deletes are explicit (no cascade).
 */
class ConversationRepository {

    private val conversations = ObjectBox.store.boxFor(ConversationEntity::class.java)
    private val messages = ObjectBox.store.boxFor(MessageEntity::class.java)

    /** Conversations in [projectId], newest-activity-first. */
    fun list(projectId: Long): List<ConversationEntity> =
        conversations.query()
            .equal(ConversationEntity_.projectId, projectId)
            .orderDesc(ConversationEntity_.updatedAt)
            .build()
            .use { it.find() }

    /** The most recent conversation of [projectId], or null — a project has at most one. */
    fun firstForProject(projectId: Long): ConversationEntity? =
        conversations.query()
            .equal(ConversationEntity_.projectId, projectId)
            .orderDesc(ConversationEntity_.updatedAt)
            .build()
            .use { it.findFirst() }

    fun get(id: Long): ConversationEntity? = conversations.get(id)

    fun create(projectId: Long, title: String, now: Long): Long {
        val c = ConversationEntity().apply {
            this.projectId = projectId
            this.title = title
            createdAt = now
            updatedAt = now
        }
        return conversations.put(c)
    }

    fun rename(id: Long, title: String) {
        conversations.get(id)?.let {
            it.title = title
            conversations.put(it)
        }
    }

    fun touch(id: Long, now: Long) {
        conversations.get(id)?.let {
            it.updatedAt = now
            conversations.put(it)
        }
    }

    fun delete(id: Long) {
        messages.query().equal(MessageEntity_.conversationId, id).build().use { it.remove() }
        conversations.remove(id)
    }

    /** Delete every conversation (and its messages) belonging to [projectId]. */
    fun deleteForProject(projectId: Long) {
        list(projectId).forEach { delete(it.id) }
    }

    fun messages(conversationId: Long): List<MessageEntity> =
        messages.query()
            .equal(MessageEntity_.conversationId, conversationId)
            .order(MessageEntity_.createdAt)
            .build()
            .use { it.find() }

    /** Replace all stored messages for a conversation (called when a turn completes). */
    fun saveMessages(conversationId: Long, msgs: List<MessageEntity>) {
        messages.query().equal(MessageEntity_.conversationId, conversationId).build().use { it.remove() }
        msgs.forEach {
            it.id = 0
            it.conversationId = conversationId
        }
        messages.put(msgs)
    }
}
