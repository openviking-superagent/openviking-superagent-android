// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.openviking.superagent.mobile.data.ActivityResponse
import ai.openviking.superagent.mobile.data.ActivityRow
import ai.openviking.superagent.mobile.data.ApiClient
import ai.openviking.superagent.mobile.data.ApiException
import ai.openviking.superagent.mobile.data.EpochSelection
import ai.openviking.superagent.mobile.data.InstanceInfo
import ai.openviking.superagent.mobile.data.InstancesResponse
import ai.openviking.superagent.mobile.data.Overview
import ai.openviking.superagent.mobile.data.ServerConfig
import ai.openviking.superagent.mobile.data.ServiceState
import ai.openviking.superagent.mobile.data.SessionInfo
import ai.openviking.superagent.mobile.data.SettingsStore
import ai.openviking.superagent.mobile.model.FeedModel
import ai.openviking.superagent.mobile.model.buildFeed
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TestState(
    val running: Boolean = false,
    val ok: Boolean? = null,
    val message: String = "",
)

data class AppUiState(
    val config: ServerConfig? = null,
    val showSettings: Boolean = false,
    val instances: List<InstanceInfo> = emptyList(),
    val instanceId: String? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val currentEpoch: Int? = null,
    val selectedEpoch: EpochSelection = EpochSelection.Current,
    val service: ServiceState = ServiceState(),
    val overview: Overview = Overview(),
    val feed: FeedModel = FeedModel(),
    val nextCursor: Long? = null,
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val paused: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val lastUpdatedMs: Long? = null,
    val testState: TestState = TestState(),
) {
    val instanceLabel: String
        get() = instances.firstOrNull { it.id == instanceId }?.label
            ?: if (instanceId.isNullOrBlank()) "本机 daemon" else instanceId.orEmpty()

    val viewingHistorical: Boolean
        get() = selectedEpoch is EpochSelection.Session
            && (selectedEpoch as EpochSelection.Session).epoch != currentEpoch
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val client = ApiClient()
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val rows = LinkedHashMap<Long, ActivityRow>()
    private var generation = 0
    private var fetchJob: Job? = null
    private var pollJob: Job? = null
    private var fetching = false

    init {
        val saved = store.load()
        if (saved == null) {
            _state.update { it.copy(showSettings = true) }
        } else {
            _state.update { it.copy(config = saved, instanceId = saved.instanceId) }
            restart()
        }
    }

    fun openSettings() {
        _state.update { it.copy(showSettings = true, testState = TestState()) }
    }

    fun closeSettings() {
        if (_state.value.config != null) {
            _state.update { it.copy(showSettings = false, testState = TestState()) }
        }
    }

    fun clearTest() {
        if (_state.value.testState != TestState()) {
            _state.update { it.copy(testState = TestState()) }
        }
    }

    fun testConnection(baseUrl: String, token: String) {
        val normalized = SettingsStore.normalizeBaseUrl(baseUrl)
        if (normalized == null) {
            _state.update {
                it.copy(testState = TestState(ok = false, message = "地址需要以 http:// 或 https:// 开头"))
            }
            return
        }
        _state.update { it.copy(testState = TestState(running = true)) }
        viewModelScope.launch {
            val result = runCatching<InstancesResponse?> {
                try {
                    client.listInstances(ServerConfig(normalized, token.trim()))
                } catch (error: ApiException) {
                    // A daemon used directly has /api/activity but no instance list.
                    if (error.statusCode == 404) {
                        client.activity(
                            config = ServerConfig(normalized, token.trim()),
                            instanceId = null,
                            epoch = EpochSelection.Current,
                            limit = 1,
                        )
                        null
                    } else {
                        throw error
                    }
                }
            }
            result.fold(
                onSuccess = { response ->
                    val names = response?.instances?.joinToString("、") { it.label }
                        ?.ifBlank { "默认实例" }
                        ?: "直连 daemon"
                    _state.update {
                        it.copy(testState = TestState(ok = true, message = "连接成功：$names"))
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            testState = TestState(
                                ok = false,
                                message = error.message ?: "连接失败",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun saveAndConnect(baseUrl: String, token: String) {
        val normalized = SettingsStore.normalizeBaseUrl(baseUrl)
        if (normalized == null) {
            _state.update {
                it.copy(testState = TestState(ok = false, message = "地址需要以 http:// 或 https:// 开头"))
            }
            return
        }
        val previous = _state.value.config
        val config = ServerConfig(
            baseUrl = normalized,
            token = token.trim(),
            instanceId = previous?.instanceId?.takeIf { previous.baseUrl == normalized },
        )
        store.save(config)
        _state.update {
            it.copy(
                config = config,
                instanceId = config.instanceId,
                showSettings = false,
                testState = TestState(),
                paused = false,
            )
        }
        restart()
    }

    fun disconnect() {
        store.clear()
        generation++
        fetchJob?.cancel()
        pollJob?.cancel()
        rows.clear()
        _state.value = AppUiState(showSettings = true)
    }

    fun selectInstance(id: String) {
        val config = _state.value.config ?: return
        if (id == _state.value.instanceId) return
        val updated = config.copy(instanceId = id)
        store.save(updated)
        _state.update {
            it.copy(
                config = updated,
                instanceId = id,
                selectedEpoch = EpochSelection.Current,
                feed = FeedModel(),
                sessions = emptyList(),
                currentEpoch = null,
            )
        }
        restart()
    }

    fun selectEpoch(selection: EpochSelection) {
        if (selection == _state.value.selectedEpoch) return
        _state.update { it.copy(selectedEpoch = selection) }
        reload()
    }

    fun refresh() {
        reload()
    }

    fun togglePause() {
        val paused = !_state.value.paused
        _state.update { it.copy(paused = paused) }
        if (!paused) {
            viewModelScope.launch { pollOnce() }
        }
    }

    fun loadOlder() {
        val current = _state.value
        val config = current.config ?: return
        val cursor = current.nextCursor ?: return
        if (current.loadingOlder || current.loading) return
        _state.update { it.copy(loadingOlder = true) }
        val expectedGeneration = generation
        viewModelScope.launch {
            try {
                val data = client.activity(
                    config = config,
                    instanceId = current.instanceId,
                    epoch = current.selectedEpoch,
                    limit = PAGE_SIZE,
                    before = cursor,
                )
                if (expectedGeneration != generation) return@launch
                mergeRows(data.activities)
                applyMeta(data)
                publish(data.service, data.overview, nextCursor = data.nextCursor, loadingOlder = false)
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loadingOlder = false,
                        error = error.message ?: "加载更早记录失败",
                    )
                }
            }
        }
    }

    private fun restart() {
        val config = _state.value.config ?: return
        generation++
        fetchJob?.cancel()
        pollJob?.cancel()
        rows.clear()
        _state.update {
            it.copy(
                loading = true,
                connected = false,
                error = null,
                feed = FeedModel(),
                sessions = emptyList(),
                currentEpoch = null,
                nextCursor = null,
                paused = false,
            )
        }
        val currentGeneration = generation
        fetchJob = viewModelScope.launch { loadInitial(config, currentGeneration) }
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_MS)
                val state = _state.value
                if (!state.paused && state.selectedEpoch is EpochSelection.Current && !state.loading) {
                    pollOnce()
                }
            }
        }
    }

    private fun reload() {
        val config = _state.value.config ?: return
        generation++
        fetchJob?.cancel()
        rows.clear()
        _state.update {
            it.copy(
                loading = true,
                error = null,
                feed = FeedModel(),
                nextCursor = null,
            )
        }
        val currentGeneration = generation
        fetchJob = viewModelScope.launch { loadInitial(config, currentGeneration) }
    }

    private suspend fun loadInitial(config: ServerConfig, expectedGeneration: Int) {
        try {
            val instancesResponse = try {
                client.listInstances(config)
            } catch (error: ApiException) {
                // A daemon used directly has /api/activity but no instance list.
                if (error.statusCode == 404) {
                    InstancesResponse(instances = listOf(InstanceInfo(id = "", label = "本机 daemon")))
                } else {
                    throw error
                }
            }
            if (expectedGeneration != generation) return

            val candidates = instancesResponse.instances
            val instanceId = candidates.firstOrNull { it.id == config.instanceId }?.id
                ?: instancesResponse.defaultInstance?.takeIf { default -> candidates.any { it.id == default } }
                ?: candidates.firstOrNull()?.id

            _state.update {
                it.copy(instances = candidates, instanceId = instanceId)
            }
            persistInstance(instanceId)

            var data = client.activity(
                config = config,
                instanceId = instanceId,
                epoch = _state.value.selectedEpoch,
                limit = PAGE_SIZE,
            )
            if (expectedGeneration != generation) return
            applyMeta(data)
            mergeRows(data.activities)
            var nextCursor = data.nextCursor
            var pages = 0
            while (nextCursor != null && rows.size < INITIAL_ROWS && pages < 10) {
                data = client.activity(
                    config = config,
                    instanceId = instanceId,
                    epoch = _state.value.selectedEpoch,
                    limit = PAGE_SIZE,
                    before = nextCursor,
                )
                if (expectedGeneration != generation) return
                mergeRows(data.activities)
                nextCursor = data.nextCursor
                pages += 1
            }
            publish(data.service, data.overview, nextCursor = nextCursor, loadingOlder = false)
        } catch (error: Exception) {
            if (expectedGeneration != generation) return
            _state.update {
                it.copy(
                    loading = false,
                    connected = false,
                    error = error.message ?: "加载失败",
                )
            }
        }
    }

    private suspend fun pollOnce() {
        val current = _state.value
        val config = current.config ?: return
        if (fetching || current.loading) return
        fetching = true
        val expectedGeneration = generation
        try {
            val previousEpoch = current.currentEpoch
            val maxId = rows.keys.maxOrNull()
            var data = client.activity(
                config = config,
                instanceId = current.instanceId,
                epoch = EpochSelection.Current,
                limit = PAGE_SIZE,
                after = maxId,
            )
            if (expectedGeneration != generation) return
            applyMeta(data)
            if (expectedGeneration != generation) return
            val newEpoch = _state.value.currentEpoch
            if (previousEpoch != null && newEpoch != null && previousEpoch != newEpoch) {
                // /new or a runtime switch starts a fresh session. Rebuild rather
                // than appending the new session to rows from the old one.
                reload()
                return
            }
            mergeRows(data.activities)
            var pages = 0
            while (data.hasNewer && pages < 5) {
                val after = rows.keys.maxOrNull() ?: break
                data = client.activity(
                    config = config,
                    instanceId = current.instanceId,
                    epoch = EpochSelection.Current,
                    limit = PAGE_SIZE,
                    after = after,
                )
                if (expectedGeneration != generation) return
                applyMeta(data)
                mergeRows(data.activities)
                pages++
            }
            publish(data.service, data.overview, nextCursor = _state.value.nextCursor, loadingOlder = false)
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    connected = false,
                    error = error.message ?: "连接已断开",
                )
            }
        } finally {
            fetching = false
        }
    }

    private fun applyMeta(data: ActivityResponse) {
        val current = data.sessions.firstOrNull { it.current }?.epoch ?: data.epoch
        _state.update { state ->
            val selected = state.selectedEpoch
            val resolved = if (selected is EpochSelection.Session
                && data.sessions.isNotEmpty()
                && data.sessions.none { it.epoch == selected.epoch }
            ) {
                EpochSelection.Current
            } else {
                selected
            }
            state.copy(
                sessions = data.sessions.ifEmpty { state.sessions },
                currentEpoch = current ?: state.currentEpoch,
                service = data.service,
                overview = data.overview,
                selectedEpoch = resolved,
            )
        }
    }

    private fun mergeRows(incoming: List<ActivityRow>) {
        for (row in incoming) rows[row.id] = row
        while (rows.size > MAX_ROWS) {
            val oldest = rows.keys.firstOrNull() ?: break
            rows.remove(oldest)
        }
    }

    private fun publish(
        service: ServiceState,
        overview: Overview,
        nextCursor: Long?,
        loadingOlder: Boolean,
    ) {
        val feed = buildFeed(rows.values.toList(), service)
        _state.update {
            it.copy(
                feed = feed,
                service = service,
                overview = overview,
                nextCursor = nextCursor,
                loading = false,
                loadingOlder = loadingOlder,
                connected = true,
                error = null,
                lastUpdatedMs = System.currentTimeMillis(),
            )
        }
    }

    private fun persistInstance(instanceId: String?) {
        val config = _state.value.config ?: return
        if (config.instanceId == instanceId) return
        val updated = config.copy(instanceId = instanceId)
        store.save(updated)
        _state.update { it.copy(config = updated) }
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val INITIAL_ROWS = 400
        private const val MAX_ROWS = 5000
        private const val POLL_MS = 1_500L
    }
}
