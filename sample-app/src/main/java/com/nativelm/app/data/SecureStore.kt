/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for the user's Hugging Face access token. The token is the
 * only secret NativeLM holds; it's used solely as an `Authorization: Bearer`
 * header when downloading license-gated models. Kept out of plain DataStore.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "nativelm_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The stored token, or null/blank if none. */
    fun getHfToken(): String? = prefs.getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun hasHfToken(): Boolean = !getHfToken().isNullOrBlank()

    fun setHfToken(token: String) {
        prefs.edit().putString(KEY_HF_TOKEN, token.trim()).apply()
    }

    fun clearHfToken() {
        prefs.edit().remove(KEY_HF_TOKEN).apply()
    }

    private companion object {
        const val KEY_HF_TOKEN = "hf_token"
    }
}
