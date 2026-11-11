// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    state: AppUiState,
    onTest: (String, String) -> Unit,
    onSave: (String, String) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onInputChanged: () -> Unit,
) {
    val config = state.config
    var baseUrl by remember(config?.baseUrl) { mutableStateOf(config?.baseUrl.orEmpty()) }
    var token by remember(config?.token) { mutableStateOf(config?.token.orEmpty()) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (config == null) "连接 SuperAgent" else "连接设置") },
                navigationIcon = {
                    if (config != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "在手机上看 SuperAgent 正在做什么：收到的消息、思考、工具调用和最终回复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    onInputChanged()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务地址") },
                placeholder = { Text("http://192.168.1.10:8787") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                supportingText = { Text("控制台或访问网关的地址，可带路径前缀") },
            )

            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    onInputChanged()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("访问令牌（可留空）") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) {
                        Text(if (showToken) "隐藏" else "显示")
                    }
                },
                supportingText = { Text("服务端启用 token 时填写；只保存在本机") },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onTest(baseUrl, token) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.testState.running,
                ) {
                    Text("测试连接")
                }
                Button(
                    onClick = { onSave(baseUrl, token) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.testState.running,
                ) {
                    Text(if (config == null) "连接" else "保存并连接")
                }
            }

            if (state.testState.running || state.testState.ok != null) {
                Surface(
                    color = when (state.testState.ok) {
                        true -> MaterialTheme.colorScheme.primaryContainer
                        false -> MaterialTheme.colorScheme.errorContainer
                        null -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.testState.running) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            if (state.testState.running) "正在连接…" else state.testState.message,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("远程访问怎么填", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "控制台默认只监听 127.0.0.1，并且会校验 Host，手机直接用局域网 IP 会被 403。两种可用方式：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "1. 在运行 SuperAgent 的电脑上启动仓库里的访问网关：\n" +
                            "node bridge/server.mjs --target http://127.0.0.1:3737 --host 0.0.0.0 --port 8787 --token <令牌>\n" +
                            "手机填 http://<电脑局域网或 Tailscale IP>:8787，令牌同上。",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        "2. 用 SSH 或 Tailscale 隧道把控制台端口映射到手机/内网，再填隧道地址。隧道能保留原端口时，直接填 http://127.0.0.1:3737。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "App 只读 /api/instances 和 /api/activity，不会替你发消息或改文件。HTTP 明文只适合可信内网或隧道。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (config != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { confirmDisconnect = true }) {
                    Text("清除本机连接配置", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("清除连接配置？") },
            text = { Text("会删除本机保存的地址和令牌，不影响 SuperAgent 服务端。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        onDisconnect()
                    },
                ) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text("取消")
                }
            },
        )
    }
}
