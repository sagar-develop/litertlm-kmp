package com.sagar.aicore

import okio.Path

/**
 * Provides access to platform-specific directories.
 */
interface PlatformFolders {
    /**
     * The directory where AI models should be stored.
     */
    val modelDir: Path
}
