// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.ui

import ai.openviking.superagent.mobile.data.SessionInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dayTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private val zone: ZoneId get() = ZoneId.systemDefault()

fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(timeFormatter)

fun formatDayTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(dayTimeFormatter)

fun formatDay(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(dayFormatter)

fun formatDuration(ms: Long?): String {
    if (ms == null || ms < 0) return ""
    if (ms < 1_000) return "${ms}ms"
    if (ms < 60_000) return String.format(Locale.US, "%.1fs", ms / 1_000.0)
    if (ms < 3_600_000) {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1_000
        return "${minutes}m${seconds.toString().padStart(2, '0')}s"
    }
    if (ms < 86_400_000) return String.format(Locale.US, "%.1fh", ms / 3_600_000.0)
    return String.format(Locale.US, "%.1fd", ms / 86_400_000.0)
}

fun formatTokens(value: Long?): String {
    if (value == null) return ""
    return if (value >= 1_000) {
        val divisor = if (value >= 100_000) 1_000.0 else 1_000.0
        val decimals = if (value >= 100_000) 0 else 1
        String.format(Locale.US, "%.${decimals}fk", value / divisor)
    } else {
        value.toString()
    }
}

fun formatSessionLabel(session: SessionInfo): String {
    if (session.current) return "当前"
    return formatDay(session.startMs)
}

fun formatSessionDetail(session: SessionInfo): String {
    val range = if (session.endMs != null) {
        "${formatDayTime(session.startMs)} – ${formatDayTime(session.endMs)}"
    } else {
        "${formatDayTime(session.startMs)} 至今"
    }
    val runtime = session.runtime?.let { " · $it" } ?: ""
    return "$range$runtime · ${session.activityCount} 条"
}
