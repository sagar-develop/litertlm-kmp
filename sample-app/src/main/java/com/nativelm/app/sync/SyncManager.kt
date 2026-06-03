/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.nativelm.app.data.AppPreferences
import com.nativelm.app.data.backup.BackupManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

/** UI-facing state of a peer-to-peer sync session. */
sealed interface SyncState {
    data object Idle : SyncState

    // --- Send side ---
    /** Advertising on the network; show [code] for the user to read out / type on the other device. */
    data class Advertising(val code: String, val deviceName: String) : SyncState
    data class Sending(val percent: Int) : SyncState
    data object SendComplete : SyncState

    // --- Receive side ---
    data object Discovering : SyncState
    data class PeersFound(val peers: List<SyncPeer>) : SyncState
    data class Receiving(val percent: Int) : SyncState
    /** File received; prompt for the code shown on the sending device to decrypt + import. */
    data object AwaitingCode : SyncState
    data class ReceiveComplete(val projects: Int, val documents: Int) : SyncState

    data class Failed(val message: String) : SyncState
}

/**
 * Orchestrates local peer-to-peer sync between a user's own devices over Wi-Fi, with no
 * server and no Google Play Services. The transfer **reuses the backup pipeline as its
 * format**: the sender exports an encrypted `.nlmbak` (sealed with a one-time 6-digit
 * code), advertises it via [NsdHelper], and streams it over a [SocketTransfer]; the
 * receiver discovers + pulls it, then prompts for the code to decrypt and import.
 *
 * Because the bundle is end-to-end encrypted before it hits the socket, the transport
 * only ever carries ciphertext.
 */
class SyncManager(
    private val context: Context,
    private val backupManager: BackupManager,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
) {
    private val nsd = NsdHelper(context)

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var receivedFile: File? = null
    private var job: Job? = null

    private val deviceName: String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().replaceFirstChar { it.uppercase() }

    // --- Send ---

    /** Export the knowledge base, advertise it, and serve it to the first device that connects. */
    fun startSend(appVersion: String) {
        cancel()
        job = scope.launch {
            try {
                val code = "%06d".format(SecureRandom().nextInt(1_000_000))
                val outFile = File(context.cacheDir, OUT_NAME)
                outFile.outputStream().use { out ->
                    backupManager.export(out, code.toCharArray(), appVersion, System.currentTimeMillis())
                }

                val ss = SocketTransfer.openServerSocket().also { serverSocket = it }
                nsd.advertise(
                    port = ss.localPort,
                    serviceName = "NativeLM · $deviceName",
                    onRegistered = { Log.i(TAG, "advertising as $it on ${ss.localPort}") },
                    onError = { _state.value = SyncState.Failed(it) },
                )
                _state.value = SyncState.Advertising(code, deviceName)

                SocketTransfer.serveFile(ss, outFile) { sent, total ->
                    _state.value = SyncState.Sending(percent(sent, total))
                }
                _state.value = SyncState.SendComplete
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "send failed", e)
                _state.value = SyncState.Failed(e.message ?: "Couldn't send. Make sure both devices are on the same Wi-Fi.")
            } finally {
                cleanupSend()
            }
        }
    }

    // --- Receive ---

    /** Start discovering nearby senders. Picks accumulate into [SyncState.PeersFound]. */
    fun startReceive() {
        cancel()
        _state.value = SyncState.Discovering
        val peers = LinkedHashMap<String, SyncPeer>()
        nsd.discover(
            onPeer = { peer ->
                peers["${peer.host}:${peer.port}"] = peer
                _state.value = SyncState.PeersFound(peers.values.toList())
            },
            onError = { _state.value = SyncState.Failed(it) },
        )
    }

    /** Connect to a chosen [peer] and pull the encrypted bundle; then await the code. */
    fun connectTo(peer: SyncPeer) {
        cancelJobOnly()
        job = scope.launch {
            try {
                _state.value = SyncState.Receiving(0)
                val inFile = File(context.cacheDir, IN_NAME).also { receivedFile = it }
                // Connect on the agreed fixed port (peer.port from NSD is unreliable on some
                // OEM mDNS daemons); discovery is used only to find peer.host.
                SocketTransfer.receiveFile(peer.host, SocketTransfer.SYNC_PORT, inFile) { received, total ->
                    _state.value = SyncState.Receiving(percent(received, total))
                }
                nsd.stopDiscovery()
                _state.value = SyncState.AwaitingCode
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "receive failed", e)
                _state.value = SyncState.Failed(e.message ?: "Couldn't receive from that device.")
            }
        }
    }

    /** Decrypt + import the received bundle using the [code] shown on the sending device. */
    fun importReceived(code: CharArray) {
        val file = receivedFile
        if (file == null || !file.exists()) {
            _state.value = SyncState.Failed("No received file to import.")
            return
        }
        job = scope.launch {
            try {
                val result = file.inputStream().use { input -> backupManager.import(input, code, prefs) }
                _state.value = SyncState.ReceiveComplete(result.projects, result.documents)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "import failed", e)
                _state.value = SyncState.Failed(e.message ?: "Wrong code, or the transfer was corrupted.")
            } finally {
                code.fill(' ')
                file.delete()
                receivedFile = null
            }
        }
    }

    /** Stop everything and reset to [SyncState.Idle]. */
    fun cancel() {
        cancelJobOnly()
        cleanupSend()
        nsd.stopDiscovery()
        receivedFile?.delete()
        receivedFile = null
        _state.value = SyncState.Idle
    }

    private fun cancelJobOnly() {
        job?.cancel()
        job = null
    }

    private fun cleanupSend() {
        nsd.stopAdvertising()
        runCatching { serverSocket?.close() }
        serverSocket = null
        File(context.cacheDir, OUT_NAME).delete()
    }

    private fun percent(done: Long, total: Long): Int =
        ((done * 100) / total.coerceAtLeast(1)).toInt().coerceIn(0, 100)

    private companion object {
        const val OUT_NAME = "sync-out.nlmbak"
        const val IN_NAME = "sync-in.nlmbak"
        const val TAG = "SyncManager"
    }
}
