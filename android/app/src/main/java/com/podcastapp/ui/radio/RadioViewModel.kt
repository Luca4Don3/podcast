package com.podcastapp.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.AudioSourceImport
import com.podcastapp.data.HttpSourceUrl
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.remote.StationDto
import com.podcastapp.data.remote.StreamProbeDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RadioUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val stations: List<StationDto> = emptyList(),
    val name: String = "",
    val url: String = "",
    val batch: String = "",
    val probe: StreamProbeDto? = null,
    val probing: Boolean = false,
)

/** User-owned sources only. Directory compatibility remains in the API, not in this UI. */
class RadioViewModel : ViewModel() {
    private val repo = ServiceLocator.stationRepository
    private var refreshVersion = 0
    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() { _message.value = null }
    fun showMessage(message: String) { _message.value = message }
    fun setName(value: String) { _state.value = _state.value.copy(name = value) }
    fun setUrl(value: String) { _state.value = _state.value.copy(url = value, probe = null) }
    fun setBatch(value: String) { _state.value = _state.value.copy(batch = value) }

    fun refresh() {
        val version = ++refreshVersion
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val stations = repo.list()
                if (version == refreshVersion) _state.value = _state.value.copy(stations = stations)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == refreshVersion) {
                    _state.value = _state.value.copy(error = error.message ?: "加载音源失败，请重试")
                }
            } finally {
                if (version == refreshVersion) _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun addStation() {
        if (_state.value.saving) return
        val request = try {
            AudioSourceImport.single(_state.value.name, _state.value.url)
        } catch (error: IllegalArgumentException) {
            showMessage(error.message ?: "音源格式无效")
            return
        }
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch {
            try {
                val station = repo.add(name = request.name, streamUrl = request.streamUrl)
                _state.value = _state.value.copy(name = "", url = "", probe = null)
                showMessage("已保存：" + station.name)
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage("保存失败，输入已保留：" + (error.message ?: "请检查网络后重试"))
            } finally {
                _state.value = _state.value.copy(saving = false)
            }
        }
    }

    fun importBatch() {
        if (_state.value.saving) return
        val requests = try {
            AudioSourceImport.batch(_state.value.batch)
        } catch (error: IllegalArgumentException) {
            showMessage("尚未导入，请修正：\n" + error.message)
            return
        }
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch {
            try {
                val result = repo.importSources(requests)
                if (result.imported == requests.size) {
                    _state.value = _state.value.copy(batch = "")
                    showMessage("已导入 " + result.imported + " 条音源")
                } else {
                    showMessage("服务端确认导入 " + result.imported + "/" + requests.size +
                        " 条；未全部确认成功，输入已保留。请先核对我的音源，勿直接重复提交。")
                }
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage("导入失败或结果未确认，输入已保留：" + (error.message ?: "网络异常") +
                    "。请刷新核对我的音源后再重试。")
                refresh()
            } finally {
                _state.value = _state.value.copy(saving = false)
            }
        }
    }

    fun removeStation(id: String) {
        viewModelScope.launch {
            try {
                repo.remove(id)
                showMessage("已删除")
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage("删除失败：" + (error.message ?: "请重试"))
            }
        }
    }

    fun toggleFavorite(station: StationDto) {
        viewModelScope.launch {
            try {
                repo.setFavorite(station.id, !station.favorite)
                showMessage(if (station.favorite) "已取消收藏" else "已收藏")
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage("收藏操作失败：" + (error.message ?: "请重试"))
            }
        }
    }

    fun probe() {
        if (_state.value.probing) return
        val url = try {
            HttpSourceUrl.parse(_state.value.url)
        } catch (error: IllegalArgumentException) {
            showMessage(error.message ?: "URL 格式无效")
            return
        }
        _state.value = _state.value.copy(probing = true, probe = null)
        viewModelScope.launch {
            try {
                val result = repo.probe(url)
                if (_state.value.url.trim() == url) _state.value = _state.value.copy(probe = result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage("连通性检测失败：" + (error.message ?: "请重试"))
            } finally {
                _state.value = _state.value.copy(probing = false)
            }
        }
    }
}
