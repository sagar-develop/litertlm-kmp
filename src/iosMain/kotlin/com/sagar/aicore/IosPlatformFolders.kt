package com.sagar.aicore

import me.tatarka.inject.annotations.Inject
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@Inject
class IosPlatformFolders : PlatformFolders {
    override val modelDir: Path
        get() {
            val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null
            )
            return documentDirectory?.path?.toPath()!! / "models"
        }
}
