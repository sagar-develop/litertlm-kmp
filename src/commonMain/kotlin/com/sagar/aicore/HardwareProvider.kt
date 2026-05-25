package com.sagar.aicore

/**
 * Capabilities snapshot of the current device, used for adaptive configuration.
 */
data class DeviceCapabilities(
    val totalRamMb: Long,
    val availableRamMb: Long,
)

interface HardwareProvider {
    /**
     * Returns a snapshot of the device's hardware capabilities.
     */
    fun getDeviceCapabilities(): DeviceCapabilities

    /**
     * Returns the recommended max token count for the current device.
     * Lower-RAM devices get fewer tokens to prevent OOM kills.
     */
    fun getAdaptiveMaxTokens(): Int

    /**
     * Returns the available disk space in bytes for the internal storage.
     */
    fun getAvailableDiskSpace(): Long

    /**
     * Returns true if the device has enough space for the model (approx 2.5GB).
     */
    fun hasEnoughSpaceForModel(): Boolean {
        return getAvailableDiskSpace() >= 2500L * 1024 * 1024
    }
}
