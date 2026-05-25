/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.aakira.napier.Napier
import me.tatarka.inject.annotations.Inject

@Inject
class AndroidNetworkProvider(
    private val context: Context
) : NetworkProvider {

    override fun getNetworkType(): NetworkType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE

        val result = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.WIFI
            else -> NetworkType.NONE
        }
        Napier.d { "Detected Network Type: $result" }
        return result
    }
}
