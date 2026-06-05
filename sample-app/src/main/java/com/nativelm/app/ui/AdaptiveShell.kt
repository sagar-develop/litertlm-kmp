/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.window.core.layout.WindowWidthSizeClass
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.ui.chat.ChatScreen
import com.nativelm.app.ui.documents.DocumentsScreen
import com.nativelm.app.ui.models.ModelManagementScreen
import com.nativelm.app.ui.settings.SettingsScreen
import com.nativelm.app.ui.studio.StudioScreen

/** The five top-level destinations of the adaptive shell (DESIGN.md §2). */
enum class Destination(val label: String, val icon: ImageVector) {
    Chat("Chat", Icons.Outlined.Forum),
    Models("Models", Icons.Filled.Memory),
    Sources("Sources", Icons.Outlined.Description),
    Studio("Studio", Icons.Filled.AutoAwesome),
    Settings("Settings", Icons.Filled.Settings),
}

/**
 * Adaptive navigation shell hosting the five primary destinations. The window
 * width class drives the presentation (DESIGN.md §2):
 *  - **Compact**  → no rail; each screen keeps its own top bar + Chat's modal
 *                   drawer (the hub model, unchanged for phones).
 *  - **Medium**   → a left navigation rail.
 *  - **Expanded** → a left navigation rail (+ two-pane inside Chat/Sources/Studio,
 *                   handled per-screen).
 *
 * Flow routes (splash / onboarding / lock / pdf) live in [NativeLmApp]'s NavHost
 * outside this shell; [onOpenPdf] bridges back out to the PDF viewer route.
 */
@Composable
fun AdaptiveShell(
    vm: NativeLmViewModel,
    initial: Destination,
    onOpenPdf: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(initial) }

    val widthClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
    val isCompact = widthClass == WindowWidthSizeClass.COMPACT
    val isExpanded = widthClass == WindowWidthSizeClass.EXPANDED

    // On compact there's no persistent rail, so a secondary destination needs a
    // way back to Chat — honor the system back button instead of trapping the user.
    if (isCompact && selected != Destination.Chat) {
        BackHandler { selected = Destination.Chat }
    }

    val layoutType = if (isCompact) NavigationSuiteType.None else NavigationSuiteType.NavigationRail

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { dest ->
                item(
                    selected = dest == selected,
                    onClick = { selected = dest },
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label) },
                )
            }
        },
        layoutType = layoutType,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        when (selected) {
            Destination.Chat -> ChatScreen(
                vm = vm,
                showNavRail = !isCompact,
                expanded = isExpanded,
                onOpenModels = { selected = Destination.Models },
                onOpenSettings = { selected = Destination.Settings },
                onOpenDocuments = { selected = Destination.Sources },
                onOpenPdf = onOpenPdf,
                onOpenStudio = { selected = Destination.Studio },
            )

            Destination.Models -> ModelManagementScreen(
                vm = vm,
                // On the rail the destination is always reachable — no back arrow.
                canGoBack = isCompact,
                onBack = { selected = Destination.Chat },
                onContinue = { selected = Destination.Chat },
            )

            Destination.Sources -> DocumentsScreen(
                vm = vm,
                showBack = isCompact,
                onBack = { selected = Destination.Chat },
            )

            Destination.Studio -> StudioScreen(
                vm = vm,
                showBack = isCompact,
                onBack = { selected = Destination.Chat },
                onAskInChat = { selected = Destination.Chat },
            )

            Destination.Settings -> SettingsScreen(
                vm = vm,
                showBack = isCompact,
                onBack = { selected = Destination.Chat },
                onOpenModels = { selected = Destination.Models },
            )
        }
    }
}
