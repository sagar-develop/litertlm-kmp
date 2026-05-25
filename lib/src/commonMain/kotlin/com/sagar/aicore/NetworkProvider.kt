/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

enum class NetworkType {
    WIFI, CELLULAR, NONE
}

interface NetworkProvider {
    fun getNetworkType(): NetworkType
}
