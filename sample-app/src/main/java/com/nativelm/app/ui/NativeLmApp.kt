/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.llm.ROUTE_CHAT
import com.nativelm.app.llm.ROUTE_MODELS
import com.nativelm.app.llm.ROUTE_ONBOARDING
import com.nativelm.app.llm.ROUTE_SETTINGS
import com.nativelm.app.llm.ROUTE_SPLASH
import com.nativelm.app.ui.chat.ChatScreen
import com.nativelm.app.ui.models.ModelManagementScreen
import com.nativelm.app.ui.onboarding.OnboardingScreen
import com.nativelm.app.ui.settings.SettingsScreen
import com.nativelm.app.ui.splash.SplashScreen

/**
 * NativeLM navigation graph. The start destination is decided by the ViewModel's
 * boot logic (onboarding done? a model on disk?) and passed in as [startRoute].
 */
@Composable
fun NativeLmApp(vm: NativeLmViewModel, startRoute: String) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = startRoute) {
        composable(ROUTE_SPLASH) {
            SplashScreen(
                vm = vm,
                onReady = {
                    nav.navigate(ROUTE_CHAT) { popUpTo(ROUTE_SPLASH) { inclusive = true } }
                },
                onFailed = {
                    nav.navigate(ROUTE_MODELS) { popUpTo(ROUTE_SPLASH) { inclusive = true } }
                },
            )
        }
        composable(ROUTE_ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    vm.completeOnboarding()
                    nav.navigate(ROUTE_MODELS) { popUpTo(ROUTE_ONBOARDING) { inclusive = true } }
                },
            )
        }
        composable(ROUTE_MODELS) {
            ModelManagementScreen(
                vm = vm,
                canGoBack = nav.previousBackStackEntry != null,
                onBack = { nav.popBackStack() },
                onContinue = {
                    nav.navigate(ROUTE_CHAT) { popUpTo(ROUTE_MODELS) { inclusive = true } }
                },
            )
        }
        composable(ROUTE_CHAT) {
            ChatScreen(
                vm = vm,
                onOpenModels = { nav.navigate(ROUTE_MODELS) },
                onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenModels = { nav.navigate(ROUTE_MODELS) },
            )
        }
    }
}
