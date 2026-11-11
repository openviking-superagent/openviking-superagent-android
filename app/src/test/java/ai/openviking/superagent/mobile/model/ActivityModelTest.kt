// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.model

import ai.openviking.superagent.mobile.data.ActivityRow
import ai.openviking.superagent.mobile.data.ApiJson
import ai.openviking.superagent.mobile.data.ServiceState
import ai.openviking.superagent.mobile.data.inputPreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun row(
        id: Long,
        kind: String,
        summary: String = "",
        status: String? = null,
        inboundSeq: Long? = null,
        metadata: String = "{}",
    ): ActivityRow = ActivityRow(
        id = id,
        kind = kind,
        summary = summary,
        status = status,
        inboundSeq = inboundSeq,
        metadata = json.parseToJsonElement(metadata) as JsonObject,
        createdAtMs = id * 1_000,
    )

    @Test
    fun `groups a normal turn into input process and delivery`() {
        val rows = listOf(
            row(1, "message.received", "看看这个报错", inboundSeq = 10),
            row(
                2,
                "agent.turn_started",
                inboundSeq = 10,
                metadata = """{"runtime":"pi","input":"看看这个报错","historyChars":12,"inboundSeqs":[10]}""",
            ),
            row(3, "agent.thinking", metadata = """{"thinking":"先读日志"}"""),
            row(
                4,
                "agent.tool_called",
                "bash",
                status = "RUNNING",
                metadata = """{"toolId":"t1","toolName":"bash","input":"{\"command\":\"tail -n 20 app.log\"}"}""",
            ),
            row(
                5,
                "agent.tool_completed",
                "bash",
                status = "COMPLETED",
                metadata = """{"toolId":"t1","toolName":"bash","durationMs":120,"output":"boom"}""",
            ),
            row(6, "delivery.sent", "已经定位到问题", metadata = """{"action":"message.reply"}"""),
            row(
                7,
                "agent.turn_completed",
                status = "completed",
                metadata = """{"durationMs":500,"inputTokens":1200,"cachedInputTokens":1000,"outputTokens":30}""",
            ),
        )

        val feed = buildFeed(rows, ServiceState(ready = true))
        assertEquals(1, feed.turns.size)
        val turn = feed.turns.single()
        assertEquals(1, turn.messages.size)
        assertTrue(turn.messages.single().claimed)
        assertEquals(TurnStatus.OK, turn.statusInfo(ServiceState(), true).status)
        assertEquals(500L, turn.durationMs())

        val tools = turn.steps.filterIsInstance<ToolStep>().filter { !it.isDelivery }
        assertEquals(1, tools.size)
        assertFalse(tools.single().running)
        assertEquals("boom", tools.single().output)
        assertEquals(120L, tools.single().durationMs)
        assertEquals(1, turn.steps.filterIsInstance<InputStep>().size)
        assertEquals(1, turn.steps.filterIsInstance<ThinkStep>().size)
        assertEquals(1, turn.steps.filterIsInstance<ToolStep>().count { it.isDelivery })
        assertEquals(1200L, turn.meta.inputTokens)
        assertEquals(30L, turn.meta.outputTokens)
    }

    @Test
    fun `creates an implicit turn for orphaned agent rows`() {
        val feed = buildFeed(
            listOf(
                row(1, "agent.tool_completed", "bash", status = "COMPLETED", metadata = """{"toolId":"t","toolName":"bash"}"""),
            ),
        )
        assertEquals(1, feed.turns.size)
        val turn = feed.turns.single()
        assertTrue(turn.implicit)
        assertEquals("记录不全", turn.statusInfo(ServiceState(activeTurns = 0), true).label)
    }

    @Test
    fun `folds old tool rows without arguments into one blank step`() {
        val feed = buildFeed(
            listOf(
                row(1, "agent.turn_started", metadata = """{"input":"go"}"""),
                row(2, "agent.tool_completed", "bash", status = "COMPLETED", metadata = """{"toolId":"a","toolName":"bash","durationMs":10}"""),
                row(3, "agent.tool_completed", "bash", status = "COMPLETED", metadata = """{"toolId":"b","toolName":"bash","durationMs":20}"""),
                row(4, "agent.turn_completed", status = "completed"),
            ),
        )
        val turn = feed.turns.single()
        val blank = turn.steps.filterIsInstance<BlankStep>().single()
        assertEquals("bash", blank.name)
        assertEquals(2, blank.count)
        assertEquals(30L, blank.durationMs)
    }

    @Test
    fun `decodes console payload with unknown fields and nested metadata`() {
        val payload = """
            {
              "generatedAt": 1,
              "service": {"ready": true, "subscriber": "running", "uptimeSeconds": 90, "activeTurns": 1},
              "overview": {"receivedToday": 3, "sentToday": 2, "attention": 1, "unknown": true},
              "sessions": [{"epoch": 2, "current": true, "startMs": 100, "activityCount": 9, "runtime": "pi"}],
              "epoch": 2,
              "activities": [{
                "id": 8, "kind": "agent.tool_called", "title": "调用工具", "summary": "bash",
                "status": "RUNNING", "createdAtMs": 1234,
                "metadata": {"toolId": "t", "toolName": "bash", "input": {"command": "ls"}}
              }],
              "nextCursor": 7,
              "hasNewer": false
            }
        """.trimIndent()

        val response = ApiJson.decodeFromString(ai.openviking.superagent.mobile.data.ActivityResponse.serializer(), payload)
        assertEquals(1, response.sessions.size)
        assertEquals(2, response.epoch)
        assertEquals(1, response.activities.size)
        assertNotNull(response.activities.single().metadata["toolId"])
        assertTrue(response.service.ready)
        assertEquals(7L, response.nextCursor)
    }

    @Test
    fun `summarizes json tool input`() {
        assertEquals("tail -n 20 app.log", inputPreview("""{"command":"tail -n 20 app.log","timeout":10}"""))
        assertEquals("raw text", inputPreview("raw   text"))
    }
}
