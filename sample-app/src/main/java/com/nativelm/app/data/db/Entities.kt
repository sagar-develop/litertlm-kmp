/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * A saved conversation (one chat thread in the drawer). Messages are linked by
 * [MessageEntity.conversationId] rather than an ObjectBox relation — a plain
 * indexed foreign key keeps the model simple and cascade-delete explicit.
 */
@Entity
class ConversationEntity {
    @Id var id: Long = 0
    var title: String = ""
    var createdAt: Long = 0
    var updatedAt: Long = 0
}

/** One message in a conversation. [role] is "user" or "assistant". */
@Entity
class MessageEntity {
    @Id var id: Long = 0

    @Index
    var conversationId: Long = 0
    var role: String = ROLE_USER
    var text: String = ""
    var createdAt: Long = 0

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
