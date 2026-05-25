/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sagar.litertlmsample.llm.SampleViewModel
import com.sagar.litertlmsample.llm.SetupState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleApp(vm: SampleViewModel) {
    val setup by vm.setup.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "litertlm-kmp sample",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            vm.descriptorDisplayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (setup !is SetupState.Ready) {
                SetupScreen(setup = setup, onStart = vm::startDownloadAndInit)
            } else {
                MainTabs(vm)
            }
        }
    }
}

@Composable
private fun MainTabs(vm: SampleViewModel) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val titles = listOf("Chat", "Function calling")

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex, containerColor = MaterialTheme.colorScheme.surface) {
            titles.forEachIndexed { idx, title ->
                Tab(
                    selected = tabIndex == idx,
                    onClick = { tabIndex = idx },
                    text = { Text(title) },
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when (tabIndex) {
                0 -> ChatScreen(vm)
                else -> FunctionCallScreen(vm)
            }
        }
        MetricsOverlay(vm.metrics)
    }
}
