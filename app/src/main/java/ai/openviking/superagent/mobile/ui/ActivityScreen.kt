// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openviking.superagent.mobile.data.EpochSelection
import ai.openviking.superagent.mobile.model.FeedItem
import ai.openviking.superagent.mobile.model.LooseItem
import ai.openviking.superagent.mobile.model.MessageItem
import ai.openviking.superagent.mobile.model.ToolStep
import ai.openviking.superagent.mobile.model.Turn
import ai.openviking.superagent.mobile.model.TurnStatus
import ai.openviking.superagent.mobile.model.statusInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    state: AppUiState,
    onSelectInstance: (String) -> Unit,
    onSelectEpoch: (EpochSelection) -> Unit,
    onRefresh: () -> Unit,
    onTogglePause: () -> Unit,
    onLoadOlder: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val lastTurn = state.feed.turns.lastOrNull()
    val lastStatus = lastTurn?.statusInfo(state.service, true)

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling && canForward) follow = false
                if (!scrolling) follow = !canForward
            }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .collect { count ->
                if (follow && count > 0 && !showSystem) {
                    listState.scrollToItem(count - 1)
                }
            }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("SuperAgent", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(statusColor(state, lastStatus?.status))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    statusText(state, lastTurn, lastStatus?.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        TextButton(onClick = onTogglePause) {
                            Text(if (state.paused) "继续" else "暂停")
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (showSystem) "执行流" else "系统事件") },
                                    onClick = {
                                        showSystem = !showSystem
                                        menuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("连接设置") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenSettings()
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                InstanceBar(
                    state = state,
                    onSelectInstance = onSelectInstance,
                )
                SessionBar(
                    state = state,
                    onSelectEpoch = onSelectEpoch,
                )
                StatusStrip(state)
            }
        },
        floatingActionButton = {
            if (!showSystem && state.feed.items.isNotEmpty() && (!follow || state.viewingHistorical)) {
                ExtendedFloatingActionButton(
                    text = { Text(if (state.viewingHistorical) "回到当前" else "最新") },
                    icon = { Text("↓", fontSize = 18.sp) },
                    onClick = {
                        if (state.viewingHistorical) onSelectEpoch(EpochSelection.Current)
                        follow = true
                        scope.launch {
                            delay(60)
                            val count = listState.layoutInfo.totalItemsCount
                            if (count > 0) listState.animateScrollToItem(count - 1)
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRefresh) { Text("重试") }
                    }
                }
            }

            when {
                showSystem -> SystemFeed(state)
                state.loading && state.feed.items.isEmpty() -> LoadingState()
                state.feed.items.isEmpty() -> EmptyState(onRefresh)
                else -> {
                    val turnIndex = remember(state.feed.turns) {
                        state.feed.turns.withIndex().associate { (index, turn) -> turn.key to index }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.nextCursor != null) {
                            item(key = "older") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    OutlinedButton(
                                        onClick = onLoadOlder,
                                        enabled = !state.loadingOlder,
                                    ) {
                                        if (state.loadingOlder) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text("加载更早")
                                    }
                                }
                            }
                        }
                        items(state.feed.items, key = { it.key }) { item ->
                            when (item) {
                                is Turn -> TurnCard(
                                    turn = item,
                                    index = turnIndex[item.key] ?: 0,
                                    service = state.service,
                                    isLast = item === state.feed.turns.lastOrNull(),
                                )

                                is MessageItem -> MessageCard(item)
                                is LooseItem -> LooseCard(item)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusColor(state: AppUiState, status: TurnStatus?): Color = when {
    state.error != null -> Color(0xFFC62828)
    state.paused -> Color(0xFF8A8F98)
    state.viewingHistorical -> Color(0xFF7B6AA8)
    state.loading && !state.connected -> Color(0xFF8A8F98)
    !state.service.ready -> Color(0xFFC62828)
    status == TurnStatus.RUNNING -> Color(0xFF2F6FDB)
    status == TurnStatus.FAILED -> Color(0xFFC62828)
    else -> Color(0xFF2E7D32)
}

private fun statusText(state: AppUiState, lastTurn: Turn?, status: TurnStatus?): String {
    if (state.error != null) return "连接失败"
    if (state.paused) return "已暂停"
    if (state.viewingHistorical) return "历史会话"
    if (state.loading && !state.connected) return "正在连接"
    if (!state.service.ready) return "服务未就绪"
    return when {
        lastTurn != null && lastTurn.endRow == null && state.service.activeTurns != 0 -> {
            val lastStep = lastTurn.steps.lastOrNull()
            if (lastStep is ToolStep && lastStep.running) "工具 ${lastStep.label}" else "思考中"
        }
        status == TurnStatus.RUNNING -> "进行中"
        else -> "空闲 · 运行 ${formatDuration(state.service.uptimeSeconds * 1000)}"
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun InstanceBar(state: AppUiState, onSelectInstance: (String) -> Unit) {
    if (state.instances.size <= 1) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.instanceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.instances.forEach { instance ->
            FilterChip(
                selected = instance.id == state.instanceId,
                onClick = { onSelectInstance(instance.id) },
                label = { Text(instance.label) },
            )
        }
    }
}

@Composable
private fun SessionBar(state: AppUiState, onSelectEpoch: (EpochSelection) -> Unit) {
    val historical = state.sessions.filter { !it.current }.sortedByDescending { it.startMs }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "current") {
            FilterChip(
                selected = state.selectedEpoch is EpochSelection.Current,
                onClick = { onSelectEpoch(EpochSelection.Current) },
                label = { Text("当前") },
            )
        }
        items(historical, key = { "s:${it.epoch}" }) { session ->
            val selected = (state.selectedEpoch as? EpochSelection.Session)?.epoch == session.epoch
            FilterChip(
                selected = selected,
                onClick = { onSelectEpoch(EpochSelection.Session(session.epoch)) },
                label = { Text(formatSessionLabel(session)) },
            )
        }
        item(key = "all") {
            FilterChip(
                selected = state.selectedEpoch is EpochSelection.All,
                onClick = { onSelectEpoch(EpochSelection.All) },
                label = { Text("全部") },
            )
        }
    }
}

@Composable
private fun StatusStrip(state: AppUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "收 ${state.overview.receivedToday} · 回 ${state.overview.sentToday}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (state.overview.attention > 0) {
                Text(
                    "需关注 ${state.overview.attention}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                state.instanceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "正在读取执行记录…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(onRefresh: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "这个会话还没有活动",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRefresh) { Text("刷新") }
        }
    }
}

@Composable
private fun SystemFeed(state: AppUiState) {
    val rows = remember(state.feed.system) { state.feed.system.takeLast(100).reversed() }
    if (rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无系统事件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { "sys:${it.id}" }) { row ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatDayTime(row.createdAtMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            row.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (row.severity == "error") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (row.summary.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            row.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
