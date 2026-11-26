package com.github.aura.asbox.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.aura.asbox.R
import com.github.aura.asbox.api.DanmakuApi
import com.github.aura.asbox.util.AppContextHolder
import com.github.aura.asbox.util.DanmuSettingsState
import com.github.aura.asbox.util.KV
import com.github.aura.asbox.util.KVKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DanmuServiceEntry(val name: String, val url: String)

data class DanmuServiceUiState(
    val autoMatch: Boolean = true,
    val entries: List<DanmuServiceEntry> = emptyList(),
    val useDefault: Boolean = false,
    val activeUrl: String = "",
    val testing: Boolean = false,
)

/**
 * 弹幕服务配置（列表式）：可存多条弹幕服务（名称+地址），点选哪条哪条生效；
 * 生效通道仍是原有的 DANMU_API / DANMU_API_USE_DEFAULT，播放器自动匹配与手动搜索都读它，
 * 所以这里选中的服务是真生效，不是摆设。另有一条固定的「内置默认地址」不可删。
 * 「自动匹配弹幕」开关写 DANMU_AUTO_MATCH，只管播放时的自动搜索。
 */
class DanmuServiceViewModel : ViewModel() {

    private val _state = MutableStateFlow(DanmuServiceUiState())
    val state: StateFlow<DanmuServiceUiState> = _state.asStateFlow()

    private val _toastEvent = MutableStateFlow<String?>(null)
    val toastEvent = _toastEvent.asStateFlow()

    init {
        seedIfNeeded()
        refresh()
    }

    private fun str(resId: Int): String = AppContextHolder.context()?.getString(resId) ?: ""

    fun clearToast() {
        _toastEvent.value = null
    }

    private fun loadEntries(): List<DanmuServiceEntry> =
        KV.get(KVKeys.DANMU_SERVICES, ArrayList<String>()).mapNotNull { raw ->
            val tab = raw.indexOf('\t')
            if (tab <= 0) null else DanmuServiceEntry(raw.substring(0, tab), raw.substring(tab + 1))
        }

    private fun saveEntries(entries: List<DanmuServiceEntry>) {
        KV.put(KVKeys.DANMU_SERVICES, ArrayList(entries.map { it.name + "\t" + it.url }))
    }

    /** 首次没有服务列表时，把旧的单个自定义地址迁进来（名字按地址给）。 */
    private fun seedIfNeeded() {
        if (KV.contains(KVKeys.DANMU_SERVICES)) return
        val api = DanmuSettingsState.config.api.trim()
        if (api.isEmpty()) {
            saveEntries(emptyList())
            return
        }
        val nameRes = if (api.contains("9321")) {
            R.string.danmu_service_name_local
        } else {
            R.string.danmu_service_name_default
        }
        saveEntries(listOf(DanmuServiceEntry(str(nameRes), api)))
    }

    fun refresh() {
        _state.value = DanmuServiceUiState(
            autoMatch = KV.get(KVKeys.DANMU_AUTO_MATCH, true),
            entries = loadEntries(),
            useDefault = KV.get(KVKeys.DANMU_API_USE_DEFAULT, false),
            activeUrl = DanmuSettingsState.config.api.trim(),
            testing = _state.value.testing,
        )
    }

    fun setAutoMatch(enabled: Boolean) {
        KV.put(KVKeys.DANMU_AUTO_MATCH, enabled)
        refresh()
    }

    fun selectBuiltin() {
        KV.put(KVKeys.DANMU_API_USE_DEFAULT, true)
        refresh()
    }

    fun selectEntry(entry: DanmuServiceEntry) {
        // DanmuSettingsState.api 的 setter 会清掉 USE_DEFAULT，选中即生效。
        DanmuSettingsState.api = entry.url
        refresh()
    }

    fun addEntry(name: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val entries = loadEntries()
        val existing = entries.firstOrNull { it.url == cleanUrl }
        if (existing != null) {
            selectEntry(existing)
            return
        }
        val entry = DanmuServiceEntry(name.trim().ifEmpty { str(R.string.danmu_service_name_default) }, cleanUrl)
        saveEntries(entries + entry)
        selectEntry(entry)
    }

    fun updateEntry(old: DanmuServiceEntry, name: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val wasActive = !_state.value.useDefault && _state.value.activeUrl == old.url
        val updated = DanmuServiceEntry(name.trim().ifEmpty { old.name }, cleanUrl)
        saveEntries(loadEntries().map { if (it.url == old.url && it.name == old.name) updated else it })
        if (wasActive) {
            DanmuSettingsState.api = cleanUrl
        }
        refresh()
    }

    fun deleteEntry(entry: DanmuServiceEntry) {
        val wasActive = !_state.value.useDefault && _state.value.activeUrl == entry.url
        saveEntries(loadEntries().filterNot { it.url == entry.url && it.name == entry.name })
        if (wasActive) {
            // 删掉在用的服务后回落到内置默认地址，保证弹幕还有得用。
            // 顺序不能反：api 的 setter 会删掉 USE_DEFAULT，必须先清空再置 true。
            DanmuSettingsState.api = ""
            KV.put(KVKeys.DANMU_API_USE_DEFAULT, true)
        }
        refresh()
    }

    fun applyLocal() {
        addEntry(str(R.string.danmu_service_name_local), "http://127.0.0.1:9321")
    }

    fun test() {
        if (_state.value.testing) return
        _state.value = _state.value.copy(testing = true)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val result = DanmakuApi.search("斗罗大陆", "")
                    !result.isNullOrEmpty()
                } catch (e: Exception) {
                    false
                }
            }
            _state.value = _state.value.copy(testing = false)
            _toastEvent.value = str(
                if (ok) R.string.danmu_service_test_ok else R.string.danmu_service_test_fail,
            )
        }
    }
}
