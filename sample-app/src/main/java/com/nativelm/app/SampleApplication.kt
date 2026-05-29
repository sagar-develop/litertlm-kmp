/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app

import android.app.Application
import com.nativelm.app.data.db.ObjectBox
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Napier.base(DebugAntilog())
        ObjectBox.init(this)
    }
}
