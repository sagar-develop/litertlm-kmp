/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.llm.ROUTE_BENCHMARK
import com.nativelm.app.llm.ROUTE_ONBOARDING
import com.nativelm.app.llm.ROUTE_PDF_VIEWER
import com.nativelm.app.llm.ROUTE_SPLASH
import com.nativelm.app.ui.benchmark.BenchmarkScreen
import com.nativelm.app.ui.onboarding.OnboardingScreen
import com.nativelm.app.ui.pdf.PdfViewerScreen
import com.nativelm.app.ui.splash.SplashScreen

/** Shell route, carrying the initial destination as a path arg (`shell/chat`). */
private const val ROUTE_SHELL = "shell"
private fun shellRoute(dest: Destination) =
    "$ROUTE_SHELL/${if (dest == Destination.Models) "models" else "chat"}"

/**
 * NativeLM navigation graph. The full-bleed *flows* (splash / onboarding / PDF
 * viewer) are NavHost routes; the five primary destinations live inside the
 * adaptive shell (see [AdaptiveShell]), which the rail/drawer switches between
 * without leaving the route. The ViewModel's boot logic picks [startRoute].
 */
@Composable
fun NativeLmApp(vm: NativeLmViewModel, startRoute: String) {
    val nav = rememberNavController()

    // Map the boot decision onto a real start destination. "models" (no model on
    // disk) enters the shell directly on the Models tab; everything else flows.
    val firstRoute = when (startRoute) {
        ROUTE_ONBOARDING -> ROUTE_ONBOARDING
        ROUTE_SPLASH -> ROUTE_SPLASH
        else -> shellRoute(Destination.Models)
    }

    NavHost(navController = nav, startDestination = firstRoute) {
        composable(ROUTE_SPLASH) {
            SplashScreen(
                vm = vm,
                onReady = {
                    nav.navigate(shellRoute(Destination.Chat)) {
                        popUpTo(ROUTE_SPLASH) { inclusive = true }
                    }
                },
                onFailed = {
                    nav.navigate(shellRoute(Destination.Models)) {
                        popUpTo(ROUTE_SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(ROUTE_ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    vm.completeOnboarding()
                    nav.navigate(shellRoute(Destination.Models)) {
                        popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = "$ROUTE_SHELL/{start}",
            arguments = listOf(navArgument("start") { type = NavType.StringType; defaultValue = "chat" }),
        ) { entry ->
            val start = entry.arguments?.getString("start")
            AdaptiveShell(
                vm = vm,
                initial = if (start == "models") Destination.Models else Destination.Chat,
                onOpenPdf = { nav.navigate(ROUTE_PDF_VIEWER) },
                onOpenBenchmark = { nav.navigate(ROUTE_BENCHMARK) },
            )
        }
        composable(ROUTE_PDF_VIEWER) {
            PdfViewerScreen(
                vm = vm,
                onBack = {
                    vm.clearPdfViewTarget()
                    nav.popBackStack()
                },
            )
        }
        composable(ROUTE_BENCHMARK) {
            BenchmarkScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
