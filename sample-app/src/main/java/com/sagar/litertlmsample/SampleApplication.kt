/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample

import android.app.Application
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Napier.base(DebugAntilog())
    }
}
