/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.backup

import org.signal.argon2.Argon2
import org.signal.argon2.Type
import org.signal.argon2.Version
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto for local encrypted backups. The user's passphrase derives a 256-bit key via
 * **Argon2id** (memory-hard), and payloads are sealed with **AES-256-GCM**. The key is
 * passphrase-derived and device-independent on purpose: it must restore on a *different*
 * phone, so the Android Keystore (device-bound, non-exportable) is deliberately not used.
 *
 * We hold no escrow and no recovery — losing the passphrase makes a backup unreadable.
 */
object BackupCrypto {

    const val SALT_LEN = 16
    const val KEY_LEN = 32 // 256-bit AES key

    // Argon2id cost parameters. Stored in the manifest so import derives the same key.
    const val ARGON2_MEM_KIB = 65_536 // 64 MiB — strong but mobile-friendly (~0.5s)
    const val ARGON2_ITERATIONS = 3
    const val ARGON2_PARALLELISM = 1

    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /**
     * Derive a 32-byte AES key from [passphrase] + [salt] using Argon2id with the given
     * cost params. The intermediate UTF-8 password bytes are zeroed after hashing.
     */
    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        memKiB: Int = ARGON2_MEM_KIB,
        iterations: Int = ARGON2_ITERATIONS,
        parallelism: Int = ARGON2_PARALLELISM,
    ): ByteArray {
        val pwdBytes = charsToUtf8(passphrase)
        try {
            val argon2 = Argon2.Builder(Version.V13)
                .type(Type.Argon2id)
                .memoryCostKiB(memKiB)
                .iterations(iterations)
                .parallelism(parallelism)
                .hashLength(KEY_LEN)
                .build()
            return argon2.hash(pwdBytes, salt).hash
        } finally {
            pwdBytes.fill(0)
        }
    }

    /** Seal [plaintext] under [key]; returns `IV(12) || ciphertext+tag`. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = randomBytes(IV_LEN)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plaintext)
    }

    /**
     * Open a `IV(12) || ciphertext+tag` [blob] with [key]. Throws (GCM tag failure) on a
     * wrong key or tampered data — callers surface that as "wrong passphrase / corrupt".
     */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "ciphertext too short" }
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(ct)
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        return ByteArray(bb.remaining()).also { bb.get(it) }
    }
}
