/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Plain TCP file transfer over the local network for peer-to-peer sync. The bytes on the
 * wire are an **already-encrypted** `.nlmbak` (AES-GCM with a one-time transfer code), so
 * the socket only ever carries ciphertext — the receiving device prompts for the code to
 * decrypt. The wire framing is trivial: an 8-byte big-endian length, then that many bytes.
 *
 * The transfer is the last mile; the encrypted bundle is the same format the backup
 * feature produces, so confidentiality is handled before a byte hits the socket.
 */
object SocketTransfer {

    private const val BUFFER = 64 * 1024
    /** A guard so a malformed/hostile length prefix can't trigger an unbounded allocation. */
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB

    /** Bind a server socket on an ephemeral port. The caller advertises [ServerSocket.getLocalPort]. */
    fun openServerSocket(): ServerSocket = ServerSocket(0).apply { soTimeout = ACCEPT_TIMEOUT_MS }

    /**
     * Accept one inbound connection on [serverSocket] and stream [file] to it. Closes the
     * server socket when done. [onProgress] reports sent/total bytes.
     */
    suspend fun serveFile(
        serverSocket: ServerSocket,
        file: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        serverSocket.use { ss ->
            ss.accept().use { socket ->
                DataOutputStream(BufferedOutputStream(socket.getOutputStream())).use { out ->
                    val total = file.length()
                    out.writeLong(total)
                    file.inputStream().use { input ->
                        val buf = ByteArray(BUFFER)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            onProgress(sent, total)
                        }
                    }
                    out.flush()
                }
            }
        }
    }

    /**
     * Connect to [host]:[port] and write the received bytes to [dest]. [onProgress] reports
     * received/total bytes. Throws on connection failure, a truncated stream, or an
     * implausible length prefix.
     */
    suspend fun receiveFile(
        host: String,
        port: Int,
        dest: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            DataInputStream(BufferedInputStream(socket.getInputStream())).use { input ->
                val total = input.readLong()
                require(total in 0..MAX_BYTES) { "Unexpected transfer size." }
                dest.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    var received = 0L
                    while (received < total) {
                        val want = minOf(buf.size.toLong(), total - received).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) throw java.io.EOFException("Transfer ended early.")
                        out.write(buf, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                    out.flush()
                }
            }
        }
    }

    private const val ACCEPT_TIMEOUT_MS = 120_000 // sender waits up to 2 min for the receiver
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000
}
