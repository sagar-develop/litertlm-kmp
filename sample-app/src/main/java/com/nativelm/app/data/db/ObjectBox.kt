/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

import android.content.Context
import io.objectbox.BoxStore

/** Process-wide ObjectBox store. Initialized once from [com.nativelm.app.SampleApplication]. */
object ObjectBox {
    @Volatile
    private var boxStore: BoxStore? = null

    val store: BoxStore
        get() = boxStore ?: error("ObjectBox.init() not called")

    fun init(context: Context) {
        if (boxStore != null) return
        boxStore = MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .build()
    }
}
