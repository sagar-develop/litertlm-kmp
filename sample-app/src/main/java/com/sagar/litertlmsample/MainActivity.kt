/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sagar.litertlmsample.llm.SampleViewModel
import com.sagar.litertlmsample.ui.SampleApp
import com.sagar.litertlmsample.ui.theme.SampleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SampleTheme {
                val vm: SampleViewModel = viewModel(factory = SampleViewModel.factory(application))
                SampleApp(vm = vm)
            }
        }
    }
}
