/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import android.content.Context
import me.tatarka.inject.annotations.Inject
import okio.Path
import okio.Path.Companion.toPath

@Inject
class AndroidPlatformFolders(
    private val context: Context
) : PlatformFolders {
    override val modelDir: Path
        get() = context.filesDir.absolutePath.toPath() / "models"
}
