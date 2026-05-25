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
