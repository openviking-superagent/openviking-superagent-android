// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class ApiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

class ApiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun listInstances(config: ServerConfig): InstancesResponse =
        get(config, "${config.baseUrl}/api/instances", InstancesResponse.serializer())

    suspend fun activity(
        config: ServerConfig,
        instanceId: String?,
        epoch: EpochSelection,
        limit: Int = 100,
        before: Long? = null,
        after: Long? = null,
    ): ActivityResponse {
        var url = "${config.baseUrl}/api/activity".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
        if (!instanceId.isNullOrBlank()) url = url.addQueryParameter("instance", instanceId)
        epoch.queryValue()?.let { url = url.addQueryParameter("epoch", it) }
        before?.let { url = url.addQueryParameter("before", it.toString()) }
        after?.let { url = url.addQueryParameter("after", it.toString()) }
        return get(config, url.build(), ActivityResponse.serializer())
    }

    private suspend fun <T> get(
        config: ServerConfig,
        url: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = get(config, url.toHttpUrl(), deserializer)

    private suspend fun <T> get(
        config: ServerConfig,
        url: HttpUrl,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "SuperAgent-Mobile/${BuildInfo.VERSION}")
        if (config.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.token}")
        }
        val request = builder.build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(response.code, errorMessage(response.code, body))
                }
                if (body.isBlank()) {
                    throw ApiException(response.code, "服务端返回了空响应")
                }
                try {
                    ApiJson.decodeFromString(deserializer, body)
                } catch (error: Exception) {
                    throw ApiException(response.code, "响应格式无法解析：${error.message ?: "未知错误"}")
                }
            }
        } catch (error: ApiException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw ApiException(0, "连接超时，检查手机和服务的网络、地址与端口")
        } catch (error: IOException) {
            throw ApiException(0, "网络错误：${error.message ?: "无法连接"}")
        }
    }

    private fun errorMessage(status: Int, body: String): String {
        val apiError = runCatching {
            (ApiJson.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.content
        }.getOrNull()
        val detail = when (apiError) {
            "forbidden" -> "服务端 Host/Origin 校验未通过"
            "unknown_instance" -> "实例不存在"
            "invalid_instance" -> "实例参数不合法"
            "daemon_unavailable" -> "控制台连不上 daemon"
            "daemon_timeout" -> "控制台等待 daemon 超时"
            "unauthorized" -> "令牌不正确"
            "not_found" -> "接口不存在"
            null -> null
            else -> apiError
        }
        return when (status) {
            401 -> "未授权（401）${detail?.let { "：$it" } ?: ""}"
            403 -> "访问被拒绝（403）${detail?.let { "：$it" } ?: ""}。远程访问请检查 Host 校验或访问令牌。"
            404 -> "找不到接口（404）${detail?.let { "：$it" } ?: ""}，请检查服务地址。"
            502, 503, 504 -> "控制台暂时不可用（$status）${detail?.let { "：$it" } ?: ""}"
            else -> "请求失败（$status）${detail?.let { "：$it" } ?: ""}"
        }
    }
}

private object BuildInfo {
    // Avoid a BuildConfig import in the data layer so unit tests stay JVM-only.
    const val VERSION = "1.0.0"
}
