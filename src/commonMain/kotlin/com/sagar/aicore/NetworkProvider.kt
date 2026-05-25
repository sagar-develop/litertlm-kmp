package com.sagar.aicore

enum class NetworkType {
    WIFI, CELLULAR, NONE
}

interface NetworkProvider {
    fun getNetworkType(): NetworkType
}
