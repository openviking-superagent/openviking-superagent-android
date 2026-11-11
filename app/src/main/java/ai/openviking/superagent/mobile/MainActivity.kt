// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.openviking.superagent.mobile.ui.ActivityScreen
import ai.openviking.superagent.mobile.ui.AppViewModel
import ai.openviking.superagent.mobile.ui.SetupScreen
import ai.openviking.superagent.mobile.ui.SuperAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SuperAgentTheme {
                val viewModel: AppViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                AppRoot(state, viewModel)
            }
        }
    }
}

@Composable
private fun AppRoot(state: ai.openviking.superagent.mobile.ui.AppUiState, viewModel: AppViewModel) {
    if (state.config == null || state.showSettings) {
        SetupScreen(
            state = state,
            onTest = viewModel::testConnection,
            onSave = viewModel::saveAndConnect,
            onBack = viewModel::closeSettings,
            onDisconnect = viewModel::disconnect,
            onInputChanged = viewModel::clearTest,
        )
    } else {
        ActivityScreen(
            state = state,
            onSelectInstance = viewModel::selectInstance,
            onSelectEpoch = viewModel::selectEpoch,
            onRefresh = viewModel::refresh,
            onTogglePause = viewModel::togglePause,
            onLoadOlder = viewModel::loadOlder,
            onOpenSettings = viewModel::openSettings,
        )
    }
}
