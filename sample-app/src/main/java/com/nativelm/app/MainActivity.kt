/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.nativelm.app.data.ThemeMode
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.ui.NativeLmApp
import com.nativelm.app.ui.theme.NativeLmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        val vm = ViewModelProvider(this, NativeLmViewModel.factory(application))[NativeLmViewModel::class.java]
        // Hold the system splash until the boot decision resolves the start route.
        splash.setKeepOnScreenCondition { vm.startRoute.value == null }

        enableEdgeToEdge()
        setContent {
            val startRoute by vm.startRoute.collectAsState()
            val themeMode by vm.themeMode.collectAsState()
            val dark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            NativeLmTheme(darkTheme = dark) {
                startRoute?.let { route -> NativeLmApp(vm = vm, startRoute = route) }
            }
        }
    }
}
