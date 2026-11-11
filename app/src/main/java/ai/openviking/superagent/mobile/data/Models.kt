// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class InstancesResponse(
    val defaultInstance: String? = null,
    val instances: List<InstanceInfo> = emptyList(),
)

@Serializable
data class InstanceInfo(
    val id: String,
    val label: String = id,
)

@Serializable
data class ServiceState(
    val ready: Boolean = false,
    val subscriber: String? = null,
    val uptimeSeconds: Long = 0,
    val activeTurns: Int = 0,
)

@Serializable
data class Overview(
    val attention: Int = 0,
    val receivedToday: Int = 0,
    val sentToday: Int = 0,
)

@Serializable
data class SessionInfo(
    val epoch: Int,
    val current: Boolean = false,
    val startMs: Long = 0,
    val endMs: Long? = null,
    val activityCount: Int = 0,
    val runtime: String? = null,
)

@Serializable
data class ActivityResponse(
    val generatedAt: Long = 0,
    val service: ServiceState = ServiceState(),
    val overview: Overview = Overview(),
    val sessions: List<SessionInfo> = emptyList(),
    val epoch: Int? = null,
    val activities: List<ActivityRow> = emptyList(),
    val nextCursor: Long? = null,
    val hasNewer: Boolean = false,
)

@Serializable
data class ActivityRow(
    val id: Long,
    val kind: String = "",
    val category: String = "",
    val severity: String = "info",
    val title: String = "",
    val summary: String = "",
    val sourceRef: String? = null,
    val sourceType: String? = null,
    val sourceName: String? = null,
    @SerialName("inboundSeq") val inboundSeq: Long? = null,
    val outboxId: Long? = null,
    val status: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val createdAtMs: Long = 0,
)

sealed interface EpochSelection {
    data object Current : EpochSelection
    data object All : EpochSelection
    data class Session(val epoch: Int) : EpochSelection
}

fun EpochSelection.queryValue(): String? = when (this) {
    EpochSelection.Current -> "current"
    EpochSelection.All -> null
    is EpochSelection.Session -> epoch.toString()
}

fun JsonElement?.displayText(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull ?: toString()
    is JsonObject -> ApiJson.encodeToString(JsonObject.serializer(), this)
    is JsonArray -> ApiJson.encodeToString(JsonArray.serializer(), this)
}

fun ActivityRow.metaText(key: String): String? = metadata[key].displayText()?.takeIf { it.isNotBlank() }

fun ActivityRow.metaLong(key: String): Long? {
    val element = metadata[key]
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.toLongOrNull()
}

fun ActivityRow.metaDouble(key: String): Double? {
    val element = metadata[key]
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.toDoubleOrNull()
}

fun ActivityRow.metaLongList(key: String): List<Long> {
    val array = metadata[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
}

fun ActivityRow.actionLabel(): String = when (metaText("action")) {
    "message.send" -> "发送"
    "message.reply" -> "回复"
    "message.react" -> "表态"
    "message.retract" -> "撤回"
    "message.reaction.delete" -> "移除表态"
    else -> "投递"
}

fun ActivityRow.sourceLabel(): String = when (sourceType) {
    "p2p" -> "私聊"
    "group" -> "群聊"
    "system" -> "系统"
    else -> sourceType.orEmpty()
}

fun ActivityRow.isFailed(): Boolean = status?.equals("FAILED", ignoreCase = true) == true

/** A compact one-line preview for tool arguments. */
fun inputPreview(input: String?): String {
    val text = input?.trim().orEmpty()
    if (text.isEmpty()) return ""
    if (!text.startsWith("{")) return text.replace(Regex("\\s+"), " ").let { oneLine(it) }
    val objectValue = runCatching { ApiJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return oneLine(text)
    val primaryKeys = listOf("command", "cmd", "query", "uri", "path", "text", "markdown", "description", "prompt")
    for (key in primaryKeys) {
        val value = objectValue[key].displayText()
        if (!value.isNullOrBlank()) return oneLine(value)
    }
    val flat = objectValue.entries.joinToString(" ") { (key, value) ->
        val rendered = if (value is JsonPrimitive) value.contentOrNull.orEmpty() else ""
        if (rendered.isBlank()) "" else "$key=$rendered"
    }
    return oneLine(flat)
}

private fun oneLine(text: String, limit: Int = 160): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length > limit) flat.take(limit) + "…" else flat
}
