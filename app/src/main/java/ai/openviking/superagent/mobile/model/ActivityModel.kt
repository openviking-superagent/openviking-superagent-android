// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.model

import ai.openviking.superagent.mobile.data.ActivityRow
import ai.openviking.superagent.mobile.data.ServiceState
import ai.openviking.superagent.mobile.data.actionLabel
import ai.openviking.superagent.mobile.data.isFailed
import ai.openviking.superagent.mobile.data.metaLong
import ai.openviking.superagent.mobile.data.metaLongList
import ai.openviking.superagent.mobile.data.metaText

sealed interface FeedItem {
    val key: String
    var lastId: Long
}

class MessageItem(
    val row: ActivityRow,
    val ignored: Boolean,
) : FeedItem {
    override val key: String = "m:${row.id}"
    override var lastId: Long = row.id
    var claimed: Boolean = false
}

class Turn(
    override val key: String,
    val startRow: ActivityRow?,
    val startMs: Long,
    override var lastId: Long,
    val implicit: Boolean = false,
) : FeedItem {
    var endRow: ActivityRow? = null
    var endMs: Long? = null
    val messages: MutableList<MessageItem> = mutableListOf()
    val steps: MutableList<Step> = mutableListOf()
    val meta: TurnMeta = TurnMeta()

    val isRunning: Boolean get() = endRow == null
}

class LooseItem(val row: ActivityRow) : FeedItem {
    override val key: String = "r:${row.id}"
    override var lastId: Long = row.id
}

sealed interface Step {
    val key: String
    val startMs: Long
}

class InputStep(
    override val key: String,
    override val startMs: Long,
    val text: String,
    val steered: Boolean = false,
    val historyChars: Long? = null,
) : Step

class ThinkStep(
    override val key: String,
    override val startMs: Long,
    val text: String,
) : Step

class NoteStep(
    override val key: String,
    override val startMs: Long,
    val text: String,
) : Step

class EventStep(
    override val key: String,
    override val startMs: Long,
    val row: ActivityRow,
) : Step

class BlankStep(
    override val key: String,
    override val startMs: Long,
    val name: String,
    var count: Int,
    var durationMs: Long,
    var failed: Int,
) : Step

class ToolStep(
    override val key: String,
    override val startMs: Long,
    val name: String,
    val label: String,
    var summary: String,
    var input: String?,
    var output: String?,
    var error: String?,
    var running: Boolean,
    var failed: Boolean,
    var durationMs: Long?,
    var exitCode: Int?,
    val isDelivery: Boolean = false,
    var deliveryMode: String? = null,
    var deliveryText: String? = null,
    var silent: Boolean = false,
) : Step

class TurnMeta {
    var runtime: String? = null
    var model: String? = null
    var effort: String? = null
    var status: String? = null
    var durationMs: Long? = null
    var inputTokens: Long? = null
    var cachedInputTokens: Long? = null
    var outputTokens: Long? = null
    var contextWindow: Long? = null
    var costUsd: Double? = null

    fun merge(metadata: kotlinx.serialization.json.JsonObject) {
        val row = ActivityRow(id = 0, metadata = metadata)
        runtime = row.metaText("runtime") ?: runtime
        model = row.metaText("model") ?: model
        effort = row.metaText("effort") ?: effort
        status = row.metaText("status") ?: status
        durationMs = row.metaLong("durationMs") ?: durationMs
        inputTokens = row.metaLong("inputTokens") ?: inputTokens
        cachedInputTokens = row.metaLong("cachedInputTokens") ?: cachedInputTokens
        outputTokens = row.metaLong("outputTokens") ?: outputTokens
        contextWindow = row.metaLong("contextWindow") ?: contextWindow
        costUsd = row.metaDouble("costUsd") ?: costUsd
    }

    private fun ActivityRow.metaDouble(key: String): Double? {
        val value = metadata[key]?.toString()?.trim('"') ?: return null
        return value.toDoubleOrNull()
    }
}

data class FeedModel(
    val items: List<FeedItem> = emptyList(),
    val turns: List<Turn> = emptyList(),
    val system: List<ActivityRow> = emptyList(),
)

enum class TurnStatus { RUNNING, OK, FAILED }

data class TurnStatusInfo(val label: String, val status: TurnStatus)

fun Turn.statusInfo(service: ServiceState, isLast: Boolean): TurnStatusInfo {
    if (endRow == null) {
        if (implicit && !isLast) return TurnStatusInfo("记录不全", TurnStatus.OK)
        if (implicit && service.activeTurns == 0) return TurnStatusInfo("记录不全", TurnStatus.OK)
        return TurnStatusInfo("进行中", TurnStatus.RUNNING)
    }
    return when (endRow?.status?.lowercase() ?: meta.status?.lowercase()) {
        "failed" -> TurnStatusInfo("失败", TurnStatus.FAILED)
        "interrupted" -> TurnStatusInfo("中断", TurnStatus.FAILED)
        else -> TurnStatusInfo("完成", TurnStatus.OK)
    }
}

fun Turn.durationMs(): Long? {
    val end = endMs ?: endRow?.createdAtMs ?: return null
    return meta.durationMs ?: (end - startMs)
}

private val HIDDEN_KINDS = setOf("message.duplicate", "agent.tool_waiting")
private val TURN_STEP_KINDS = setOf(
    "agent.tool",
    "agent.tool_called",
    "agent.tool_completed",
    "agent.note",
    "agent.thinking",
    "agent.turn_completed",
)
private val INTERNAL_MARKERS = setOf("DELIVERED", "NO_DELIVERY")

/**
 * Rebuilds the console's turn-oriented view from the flat activity feed.
 *
 * The daemon records rows, not turns. This mirrors packages/web/public/activity.js
 * so the phone shows the same input / process / output grouping as the desktop
 * console, including implicit turns left behind by an older log or a restart.
 */
fun buildFeed(rows: List<ActivityRow>, service: ServiceState = ServiceState()): FeedModel {
    val items = mutableListOf<FeedItem>()
    val system = mutableListOf<ActivityRow>()
    val turns = mutableListOf<Turn>()
    val bySeq = mutableMapOf<Long, MessageItem>()
    val turnBySeq = mutableMapOf<Long, Turn>()
    var open: Turn? = null
    var tools = mutableMapOf<String, ToolStep>()

    fun claim(turn: Turn, sequences: List<Long>) {
        for (sequence in sequences) {
            val message = bySeq[sequence] ?: continue
            if (message.claimed) continue
            message.claimed = true
            turn.messages.add(message)
            turnBySeq[sequence] = turn
        }
    }

    fun newTool(row: ActivityRow): ToolStep {
        val toolId = row.metaText("toolId")
        val name = row.metaText("toolName") ?: row.metaText("toolLabel") ?: row.summary.ifBlank { "工具" }
        return ToolStep(
            key = "tool:${toolId ?: row.id}",
            startMs = row.createdAtMs,
            name = name,
            label = row.metaText("toolLabel") ?: name,
            summary = row.summary,
            input = row.metaText("input"),
            output = row.metaText("output"),
            error = row.metaText("error"),
            running = row.kind == "agent.tool_called",
            failed = row.isFailed(),
            durationMs = row.metaLong("durationMs"),
            exitCode = row.metaLong("exitCode")?.toInt(),
        )
    }

    fun completeTool(tool: ToolStep, row: ActivityRow) {
        tool.running = false
        tool.failed = row.isFailed()
        row.metaText("output")?.let { tool.output = it }
        row.metaText("error")?.let { tool.error = it }
        row.metaText("input")?.let { tool.input = it }
        tool.durationMs = row.metaLong("durationMs") ?: (row.createdAtMs - tool.startMs)
        row.metaLong("exitCode")?.let { tool.exitCode = it.toInt() }
        if (row.kind == "agent.tool" && row.summary.isNotBlank() && row.summary != tool.label) {
            tool.summary = row.summary
        }
    }

    for (row in rows.sortedBy { it.id }) {
        val kind = row.kind
        if (kind in HIDDEN_KINDS) continue

        if (kind.startsWith("system.")) {
            system.add(row)
            continue
        }

        if (kind.startsWith("message.")) {
            val message = MessageItem(row, ignored = kind != "message.received")
            row.inboundSeq?.let { sequence ->
                if (!message.ignored) bySeq[sequence] = message
            }
            items.add(message)
            continue
        }

        if (kind == "agent.turn_started") {
            val turn = Turn(
                key = "t:${row.id}",
                startRow = row,
                startMs = row.createdAtMs,
                lastId = row.id,
            )
            open = turn
            tools = mutableMapOf()
            turn.meta.merge(row.metadata)
            val sequences = row.metaLongList("inboundSeqs").toMutableList()
            row.inboundSeq?.let { if (it !in sequences) sequences.add(it) }
            claim(turn, sequences)
            row.metaText("input")?.takeIf { it.isNotBlank() }?.let { input ->
                turn.steps.add(
                    InputStep(
                        key = "in:${row.id}",
                        startMs = row.createdAtMs,
                        text = input,
                        historyChars = row.metaLong("historyChars"),
                    ),
                )
            }
            turns.add(turn)
            items.add(turn)
            continue
        }

        if (kind == "agent.decision_missing" && open == null) {
            val turn = row.inboundSeq?.let { turnBySeq[it] }
            if (turn != null) {
                turn.steps.add(EventStep("e:${row.id}", row.createdAtMs, row))
                turn.lastId = maxOf(turn.lastId, row.id)
                continue
            }
        }

        val agentRow = kind.startsWith("agent.") || kind.startsWith("delivery.")
        if (open == null && agentRow && kind in TURN_STEP_KINDS) {
            val turn = Turn(
                key = "t:${row.id}",
                startRow = null,
                startMs = row.createdAtMs,
                lastId = row.id,
                implicit = true,
            )
            open = turn
            tools = mutableMapOf()
            turns.add(turn)
            items.add(turn)
        }

        val turn = open
        if (turn == null || !agentRow) {
            if (kind == "agent.thinking" && row.metaText("thinking").isNullOrBlank()) continue
            items.add(LooseItem(row))
            continue
        }

        turn.lastId = row.id
        when (kind) {
            "agent.turn_completed" -> {
                for (step in turn.steps) {
                    if (step is ToolStep && step.running) {
                        step.running = false
                        step.failed = true
                        step.error = row.summary.ifBlank { "回合已结束，未收到工具结果" }
                        step.durationMs = row.createdAtMs - step.startMs
                    }
                }
                turn.endRow = row
                turn.endMs = row.createdAtMs
                turn.meta.merge(row.metadata)
                open = null
            }

            "agent.steered" -> {
                val sequences = row.metaLongList("inboundSeqs").toMutableList()
                row.inboundSeq?.let { if (it !in sequences) sequences.add(it) }
                claim(turn, sequences)
                turn.steps.add(
                    InputStep(
                        key = "in:${row.id}",
                        startMs = row.createdAtMs,
                        text = row.metaText("input") ?: row.summary,
                        steered = true,
                    ),
                )
            }

            "delivery.sent" -> {
                turn.steps.add(
                    ToolStep(
                        key = "delivery:${row.id}",
                        startMs = row.createdAtMs,
                        name = row.actionLabel(),
                        label = row.actionLabel(),
                        summary = row.summary,
                        input = null,
                        output = null,
                        error = null,
                        running = false,
                        failed = row.isFailed(),
                        durationMs = row.metaLong("durationMs"),
                        exitCode = null,
                        isDelivery = true,
                        deliveryMode = row.actionLabel(),
                        deliveryText = row.summary,
                    ),
                )
            }

            "delivery.silent" -> {
                turn.steps.add(
                    ToolStep(
                        key = "delivery:${row.id}",
                        startMs = row.createdAtMs,
                        name = "不回复",
                        label = "不回复",
                        summary = row.summary,
                        input = null,
                        output = null,
                        error = null,
                        running = false,
                        failed = false,
                        durationMs = null,
                        exitCode = null,
                        isDelivery = true,
                        deliveryMode = "不回复",
                        deliveryText = row.summary,
                        silent = true,
                    ),
                )
            }

            "agent.tool_called", "agent.tool_completed", "agent.tool" -> {
                val toolId = row.metaText("toolId")
                val existing = toolId?.let { tools[it] }
                if (existing != null && kind != "agent.tool_called") {
                    completeTool(existing, row)
                } else {
                    val tool = newTool(row)
                    if (kind != "agent.tool_called") completeTool(tool, row)
                    if (toolId != null) tools[toolId] = tool
                    turn.steps.add(tool)
                }
            }

            "agent.thinking" -> {
                row.metaText("thinking")?.takeIf { it.isNotBlank() }?.let {
                    turn.steps.add(ThinkStep("th:${row.id}", row.createdAtMs, it))
                }
            }

            "agent.note" -> {
                if (row.summary.trim() !in INTERNAL_MARKERS) {
                    turn.steps.add(NoteStep("n:${row.id}", row.createdAtMs, row.summary))
                }
            }

            else -> turn.steps.add(EventStep("e:${row.id}", row.createdAtMs, row))
        }
    }

    turns.forEach { it.steps.replaceWith(foldBlankTools(it.steps)) }
    turns.forEach { turn ->
        turn.messages.sortBy { it.row.id }
        turn.lastId = maxOf(turn.lastId, turn.messages.maxOfOrNull { it.lastId } ?: 0L)
    }

    val visible = items.filter { !(it is MessageItem && it.claimed) }
    return FeedModel(items = visible, turns = turns, system = system)
}

private fun <T> MutableList<T>.replaceWith(values: List<T>) {
    clear()
    addAll(values)
}

private fun foldBlankTools(steps: List<Step>): List<Step> {
    val out = mutableListOf<Step>()
    for (step in steps) {
        if (step is ToolStep
            && !step.isDelivery
            && !step.running
            && step.input.isNullOrBlank()
            && step.output.isNullOrBlank()
            && step.error.isNullOrBlank()
        ) {
            val previous = out.lastOrNull()
            if (previous is BlankStep && previous.name == step.label) {
                previous.count += 1
                previous.durationMs += step.durationMs ?: 0
                previous.failed += if (step.failed) 1 else 0
                continue
            }
            out.add(
                BlankStep(
                    key = step.key,
                    startMs = step.startMs,
                    name = step.label,
                    count = 1,
                    durationMs = step.durationMs ?: 0,
                    failed = if (step.failed) 1 else 0,
                ),
            )
            continue
        }
        out.add(step)
    }
    return out
}
