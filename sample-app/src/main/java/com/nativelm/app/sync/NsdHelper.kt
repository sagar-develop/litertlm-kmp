/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/** A discovered sync peer: its advertised name + resolved address. */
data class SyncPeer(val name: String, val host: String, val port: Int)

/**
 * Thin wrapper over Android's Network Service Discovery (`NsdManager` = mDNS/Bonjour)
 * for local peer-to-peer sync. The sender **advertises** a `_nativelm-sync._tcp` service
 * on an ephemeral port; the receiver **discovers** + resolves it to a host/port and opens
 * a socket. No internet, no Google Play Services — the same Bonjour protocol iOS speaks,
 * so this path is portable to a future cross-platform sync.
 *
 * A `WifiManager.MulticastLock` is held during discovery so mDNS multicast packets aren't
 * filtered out while the screen is on.
 */
class NsdHelper(context: Context) {

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // --- Advertise (sender) ---

    /**
     * Register a service named [serviceName] on [port]. [onRegistered] returns the actual
     * name NSD assigned (it may append a suffix on a name collision).
     */
    fun advertise(
        port: Int,
        serviceName: String,
        onRegistered: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                onRegistered(info.serviceName ?: serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                onError("Couldn't advertise (NSD error $errorCode).")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { onError(it.message ?: "Couldn't advertise.") }
    }

    fun stopAdvertising() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
    }

    // --- Discover (receiver) ---

    /** Discover sync peers; [onPeer] fires once per resolved peer. */
    fun discover(onPeer: (SyncPeer) -> Unit, onError: (String) -> Unit) {
        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.trimEnd('.').endsWith(SERVICE_TYPE.trimEnd('.'))) {
                    resolve(service, onPeer)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onError("Couldn't search for devices (NSD error $errorCode).")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { onError(it.message ?: "Couldn't search for devices.") }
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        releaseMulticastLock()
    }

    @Suppress("DEPRECATION") // resolveService is deprecated in API 34 but works on minSdk 24
    private fun resolve(service: NsdServiceInfo, onPeer: (SyncPeer) -> Unit) {
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                onPeer(SyncPeer(info.serviceName ?: "device", host, info.port))
            }
        })
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("nativelm-sync").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
    }

    private companion object {
        const val SERVICE_TYPE = "_nativelm-sync._tcp."
        const val TAG = "NsdHelper"
    }
}
