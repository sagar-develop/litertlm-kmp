/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

/**
 * CRUD over conversations + their messages. Messages link to a conversation by
 * [MessageEntity.conversationId]; deleting a conversation explicitly removes its
 * messages (ObjectBox does not cascade).
 */
class ConversationRepository {

    private val conversations = ObjectBox.store.boxFor(ConversationEntity::class.java)
    private val messages = ObjectBox.store.boxFor(MessageEntity::class.java)

    /** Conversations newest-activity-first, for the drawer. */
    fun list(): List<ConversationEntity> =
        conversations.query().orderDesc(ConversationEntity_.updatedAt).build().use { it.find() }

    fun get(id: Long): ConversationEntity? = conversations.get(id)

    fun create(title: String, now: Long): Long {
        val c = ConversationEntity().apply {
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
