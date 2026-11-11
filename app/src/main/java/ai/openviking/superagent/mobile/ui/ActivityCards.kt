// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openviking.superagent.mobile.data.metaLong
import ai.openviking.superagent.mobile.data.metaText
import ai.openviking.superagent.mobile.data.sourceLabel
import ai.openviking.superagent.mobile.data.inputPreview
import ai.openviking.superagent.mobile.model.BlankStep
import ai.openviking.superagent.mobile.model.EventStep
import ai.openviking.superagent.mobile.model.InputStep
import ai.openviking.superagent.mobile.model.LooseItem
import ai.openviking.superagent.mobile.model.MessageItem
import ai.openviking.superagent.mobile.model.NoteStep
import ai.openviking.superagent.mobile.model.Step
import ai.openviking.superagent.mobile.model.ThinkStep
import ai.openviking.superagent.mobile.model.ToolStep
import ai.openviking.superagent.mobile.model.Turn
import ai.openviking.superagent.mobile.model.TurnStatus
import ai.openviking.superagent.mobile.model.durationMs
import ai.openviking.superagent.mobile.model.statusInfo
import kotlinx.coroutines.delay

private const val MAX_DETAIL_CHARS = 30_000

@Composable
fun TurnCard(
    turn: Turn,
    index: Int,
    service: ai.openviking.superagent.mobile.data.ServiceState,
    isLast: Boolean,
) {
    val status = turn.statusInfo(service, isLast)
    val running = status.status == TurnStatus.RUNNING
    var now by remember(turn.key) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val duration = if (running) now - turn.startMs else turn.durationMs()
    val border = when (status.status) {
        TurnStatus.RUNNING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        TurnStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        TurnStatus.OK -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, border),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    formatTime(turn.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(status.label, status.status)
                Spacer(Modifier.weight(1f))
                if (duration != null) {
                    Text(
                        formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            val tokenLine = buildString {
                turn.meta.inputTokens?.let {
                    append("上下文 ${formatTokens(it)}")
                    turn.meta.contextWindow?.let { window -> append(" / ${formatTokens(window)}") }
                    turn.meta.cachedInputTokens?.let { cached -> append("（缓存 ${formatTokens(cached)}）") }
                }
                turn.meta.outputTokens?.let {
                    if (isNotEmpty()) append(" · ")
                    append("输出 ${formatTokens(it)}")
                }
            }
            if (tokenLine.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    tokenLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val runtimeLine = listOfNotNull(turn.meta.runtime, turn.meta.model, turn.meta.effort)
                .joinToString(" · ")
            if (runtimeLine.isNotBlank()) {
                Text(
                    runtimeLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (turn.implicit) {
                Text(
                    "未记录开始（旧日志或 daemon 重启）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val firstInput = turn.steps.filterIsInstance<InputStep>().firstOrNull { !it.steered }
            if (turn.messages.isNotEmpty() || firstInput != null) {
                SectionLabel("输入")
                turn.messages.forEach { message -> MessageLine(message) }
                if (firstInput != null) {
                    InputBlock(
                        step = firstInput,
                        expandedDefault = turn.messages.isEmpty(),
                    )
                }
            }

            val deliveries = turn.steps.filterIsInstance<ToolStep>().filter { it.isDelivery }
            val processSteps = turn.steps.filter { step ->
                step !== firstInput && !(step is ToolStep && step.isDelivery)
            }
            if (processSteps.isNotEmpty()) {
                SectionLabel("过程")
                processSteps.forEach { step -> StepRow(step, now) }
            }

            SectionLabel("输出")
            if (deliveries.isEmpty()) {
                val text = when {
                    status.status == TurnStatus.RUNNING -> "进行中…"
                    turn.endRow != null -> "没有发出消息（静默或仅模型文本）"
                    else -> "没有记录到输出"
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                deliveries.forEach { step -> DeliveryBubble(step) }
            }

            if (turn.endRow?.status?.equals("failed", ignoreCase = true) == true
                && turn.endRow?.summary?.isNotBlank() == true
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    turn.endRow?.summary.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, status: TurnStatus) {
    val (container, content) = when (status) {
        TurnStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        TurnStatus.OK -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        TurnStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun Tag(
    text: String,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(color = container, shape = RoundedCornerShape(5.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
fun MessageCard(item: MessageItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            MessageLine(item)
        }
    }
}

@Composable
private fun MessageLine(item: MessageItem) {
    val row = item.row
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(
                text = if (item.ignored) row.title else "收",
                container = if (item.ignored) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.primaryContainer,
                content = if (item.ignored) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                row.sourceName ?: row.sourceLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTime(row.createdAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        val messageType = row.metaText("messageType")
        if (!messageType.isNullOrBlank() && messageType != "text") {
            Text(
                messageType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        val latency = row.metaLong("latencyMs")
        if (latency != null && latency > 3_000) {
            Text(
                "延迟 ${formatDuration(latency)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (row.summary.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                row.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.ignored) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
fun LooseCard(item: LooseItem) {
    val row = item.row
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.padding(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                formatTime(row.createdAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(56.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(
                        row.title,
                        container = if (row.severity == "error") MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        content = if (row.severity == "error") MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (row.sourceName != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            row.sourceName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

@Composable
private fun InputBlock(step: InputStep, expandedDefault: Boolean) {
    var expanded by rememberSaveable(step.key) { mutableStateOf(expandedDefault) }
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(step.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(56.dp),
            )
            Tag(
                if (step.steered) "追加" else "输入",
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(6.dp))
            if (step.historyChars != null && step.historyChars > 0) {
                Text(
                    "历史前缀 ${formatTokens(step.historyChars)} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    "模型实际收到",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(4.dp).clickable { expanded = !expanded },
            )
        }
        Text(
            step.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 56.dp, top = 2.dp)
                .clickable { expanded = !expanded },
        )
    }
}

@Composable
private fun StepRow(step: Step, now: Long) {
    when (step) {
        is ToolStep -> ToolStepRow(step, now)
        is ThinkStep -> NarrativeStep(step.key, step.startMs, "思考", step.text, "think")
        is NoteStep -> NarrativeStep(step.key, step.startMs, "模型文本", step.text, "note")
        is EventStep -> EventStepRow(step)
        is BlankStep -> BlankStepRow(step)
        is InputStep -> InputBlock(step, expandedDefault = false)
    }
}

@Composable
private fun NarrativeStep(
    key: String,
    startMs: Long,
    label: String,
    text: String,
    tone: String,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            formatTime(startMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(56.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(
                    label,
                    container = if (tone == "think") MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    content = if (tone == "think") MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(4.dp).clickable { expanded = !expanded },
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun EventStepRow(step: EventStep) {
    val row = step.row
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            formatTime(row.createdAtMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(56.dp),
        )
        Tag(
            row.title,
            container = if (row.severity == "error") MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            content = if (row.severity == "error") MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            row.summary,
            style = MaterialTheme.typography.bodySmall,
            color = if (row.severity == "error") MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BlankStepRow(step: BlankStep) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTime(step.startMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(56.dp),
        )
        Tag("工具", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(6.dp))
        Text(
            if (step.count > 1) "${step.name} ×${step.count}" else step.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (step.failed > 0) {
            Text(
                "${step.failed} 次失败",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "共 ${formatDuration(step.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolStepRow(tool: ToolStep, now: Long) {
    var expanded by rememberSaveable(tool.key) { mutableStateOf(false) }
    val preview = inputPreview(tool.input).ifBlank {
        if (tool.summary.isNotBlank() && tool.summary != tool.label) tool.summary else ""
    }
    val errorText = tool.error
    val hasDetails = !tool.input.isNullOrBlank() || !tool.output.isNullOrBlank() || !errorText.isNullOrBlank()
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                formatTime(tool.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(56.dp).padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(
                        "工具",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tool.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (tool.running) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            formatDuration(now - tool.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        Text(
                            when {
                                tool.failed -> "失败"
                                tool.exitCode != null && tool.exitCode != 0 -> "exit ${tool.exitCode}"
                                else -> formatDuration(tool.durationMs)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tool.failed || (tool.exitCode ?: 0) != 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (preview.isNotBlank()) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!expanded && !errorText.isNullOrBlank()) {
                    Text(
                        errorText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (hasDetails) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(
                            if (expanded) "收起详情" else "输入 / 输出",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!tool.input.isNullOrBlank()) {
                            DetailBlock("输入", tool.input.orEmpty(), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!tool.error.isNullOrBlank()) {
                            DetailBlock("错误", tool.error.orEmpty(), MaterialTheme.colorScheme.error)
                        }
                        if (!tool.output.isNullOrBlank()) {
                            DetailBlock("输出", tool.output.orEmpty(), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(label: String, text: String, contentColor: Color) {
    val clipboard = LocalClipboardManager.current
    val shown = remember(text) { text.take(MAX_DETAIL_CHARS) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) {
                    Text("复制", style = MaterialTheme.typography.labelSmall)
                }
            }
            SelectionContainer {
                Text(
                    shown,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = contentColor,
                )
            }
            if (text.length > MAX_DETAIL_CHARS) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "内容较长，仅显示前 $MAX_DETAIL_CHARS 字，可用“复制”取全文。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeliveryBubble(tool: ToolStep) {
    val container = if (tool.silent) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.primaryContainer
    val content = if (tool.silent) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        color = container,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tool.deliveryMode ?: "投递",
                    style = MaterialTheme.typography.labelSmall,
                    color = content,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(tool.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                )
            }
            val body = tool.deliveryText ?: tool.summary
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = content)
            } else if (tool.silent) {
                Spacer(Modifier.height(3.dp))
                Text("这一轮选择不回复", style = MaterialTheme.typography.bodySmall, color = content)
            }
        }
    }
}
