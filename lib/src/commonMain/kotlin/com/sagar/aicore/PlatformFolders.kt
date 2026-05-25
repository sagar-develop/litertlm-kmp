/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
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
