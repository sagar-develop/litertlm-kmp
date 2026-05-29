/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nativelm_prefs")

/** Theme preference. [SYSTEM] follows the OS setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Non-secret app state persisted across launches: whether onboarding is done,
 * which model the user last activated, and the theme. The HF token is NOT here
 * — it lives in [SecureStore] (encrypted).
 */
class AppPreferences(private val context: Context) {

    val onboardingCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ONBOARDING] ?: false }

    val selectedModelId: Flow<String?> =
        context.dataStore.data.map { it[KEY_SELECTED_MODEL] }

    val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map { prefs ->
            when (prefs[KEY_THEME]) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
        }

    suspend fun setOnboardingCompleted(done: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING] = done }
    }

    suspend fun setSelectedModelId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_SELECTED_MODEL) else prefs[KEY_SELECTED_MODEL] = id
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name.lowercase() }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model_id")
        val KEY_THEME = stringPreferencesKey("theme_mode")
    }
}
