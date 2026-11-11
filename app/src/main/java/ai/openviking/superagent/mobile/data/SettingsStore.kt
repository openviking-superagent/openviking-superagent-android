// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ServerConfig(
    val baseUrl: String,
    val token: String = "",
    val instanceId: String? = null,
)

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("superagent_mobile", Context.MODE_PRIVATE)

    fun load(): ServerConfig? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null)?.trim().orEmpty()
        if (baseUrl.isEmpty()) return null
        return ServerConfig(
            baseUrl = baseUrl,
            token = preferences.getString(KEY_TOKEN, "").orEmpty(),
            instanceId = preferences.getString(KEY_INSTANCE, null),
        )
    }

    fun save(config: ServerConfig) {
        preferences.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_INSTANCE, config.instanceId)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_INSTANCE = "instance_id"

        /** Accepts an http(s) URL, optionally with a path prefix, and removes trailing slashes. */
        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            if (parsed.query != null || parsed.fragment != null) return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            return trimmed
        }
    }
}
