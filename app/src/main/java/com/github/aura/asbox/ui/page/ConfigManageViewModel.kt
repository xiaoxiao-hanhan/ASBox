package com.github.aura.asbox.ui.page

import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.aura.asbox.R
import com.github.aura.asbox.api.AppBootstrap
import com.github.aura.asbox.api.ApiConfig
import com.github.aura.asbox.api.ConfigParser
import com.github.aura.asbox.api.ConfigRepository
import com.github.aura.asbox.io.LocalMergedConfig
import com.github.aura.asbox.io.removeLocalCopy
import com.github.aura.asbox.util.ApiLineSignal
import com.github.aura.asbox.boot.BootGuard
import com.github.aura.asbox.util.AppContextHolder
import com.github.aura.asbox.util.KVKeys
import com.github.aura.asbox.util.HistoryHelper
import com.github.aura.asbox.util.KV
import com.github.aura.asbox.util.AppLog
import com.github.aura.asbox.util.LanguageManager
import com.github.aura.asbox.util.MD5
import com.github.aura.asbox.api.TmdbApi
import com.github.aura.asbox.api.TmdbPoster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SubscribeSource(val name: String, val url: String)

data class PendingSwitch(val item: SubscribeSource, val vod: Boolean)

// Tmdb 入口已迁到设置页；枚举值保留是为了兼容旧版 rememberSaveable 存档，界面上不再可选。
// Source 是本地片源线（.py/.js 导入）：多个片源可同时启用，启用集合合并成一份配置
// 走点播通道（与点播订阅仍互斥，同一时刻只有一条线在生效）。
enum class ConfigMode { Vod, Tmdb, Live, Source }

enum class SubscribeMode { Vod, Live, Local }

enum class TmdbPosterStyle {
    Fixed, Random, Roll;

    @StringRes
    fun labelRes(): Int = when (this) {
        Fixed -> R.string.tmdb_poster_style_fixed
        Random -> R.string.tmdb_poster_style_random
        Roll -> R.string.tmdb_poster_style_roll
    }

    companion object {
        fun of(value: Int): TmdbPosterStyle = entries.getOrElse(value) { Fixed }
    }
}

@Immutable
data class ConfigTmdbState(
    val enabled: Boolean,
    val posterStyle: TmdbPosterStyle,
    val apiKey: String,
    val apiBase: String,
    val imageBase: String,
    val apiBaseError: Boolean = false,
    val imageBaseError: Boolean = false,
)

@Immutable
data class ConfigTmdbActions(
    val setEnabled: (Boolean) -> Unit,
    val setPosterStyle: (TmdbPosterStyle) -> Unit,
    val setApiKey: (String) -> Unit,
    val setApiBase: (String) -> Unit,
    val setImageBase: (String) -> Unit,
    val commitApiBase: () -> Unit,
    val commitImageBase: () -> Unit,
    val testApi: () -> Unit,
    val testImage: () -> Unit,
)

enum class TmdbTest { None, Api, Image }

internal fun sanitizeConfigText(value: String): String =
    value.filterNot { it.isISOControl() }.trim()

internal fun parseSubscribe(value: String): SubscribeSource {
    val index = value.indexOf(SUBSCRIBE_SPLIT)
    return if (index < 0) {
        val cleaned = sanitizeConfigText(value)
        SubscribeSource(cleaned, cleaned)
    } else {
        SubscribeSource(
            sanitizeConfigText(value.substring(0, index)),
            sanitizeConfigText(value.substring(index + SUBSCRIBE_SPLIT.length)),
        )
    }
}

internal const val SUBSCRIBE_SPLIT = "\t"

internal fun vodSubscribes(): List<SubscribeSource> =
    KV.get(KVKeys.SUBSCRIBE_LIST, ArrayList<String>()).map { parseSubscribe(it) }

class ConfigManageViewModel(
    private val tmdbClientFactory: () -> TmdbApi.TmdbClient,
) : ViewModel() {

    private val _vodItems = MutableStateFlow(loadSubscribes(SubscribeMode.Vod))
    val vodItems = _vodItems.asStateFlow()
    private val _liveItems = MutableStateFlow(loadSubscribes(SubscribeMode.Live))
    val liveItems = _liveItems.asStateFlow()
    private val _localItems = MutableStateFlow(loadSubscribes(SubscribeMode.Local))
    val localItems = _localItems.asStateFlow()
    private val _localEnabled = MutableStateFlow(loadLocalEnabled())
    val localEnabled = _localEnabled.asStateFlow()

    /** 已确认是多仓（配置里带 urls 线路列表）的源地址，界面打「多仓」标记 + 允许点名字换源。 */
    private val _multiRepoUrls = MutableStateFlow<Set<String>>(emptySet())
    val multiRepoUrls = _multiRepoUrls.asStateFlow()

    private val _activeUrl = MutableStateFlow(KV.get(KVKeys.API_URL, ""))
    val activeUrl = _activeUrl.asStateFlow()
    private val _liveActiveUrl = MutableStateFlow(KV.get(KVKeys.LIVE_API_URL, ""))
    val liveActiveUrl = _liveActiveUrl.asStateFlow()
    private val _liveFollow = MutableStateFlow(ApiConfig.isLiveFollowVod())
    val liveFollow = _liveFollow.asStateFlow()
    private val _disabledUrls = MutableStateFlow(BootGuard.disabledSources().toSet())
    val disabledUrls = _disabledUrls.asStateFlow()
    private val _selected = MutableStateFlow(emptySet<String>())
    val selected = _selected.asStateFlow()
    private val _manageMode = MutableStateFlow(false)
    val manageMode = _manageMode.asStateFlow()
    private val _editTarget = MutableStateFlow<SubscribeSource?>(null)
    val editTarget: StateFlow<SubscribeSource?> = _editTarget.asStateFlow()
    private val _pendingSwitch = MutableStateFlow<PendingSwitch?>(null)
    val pendingSwitch = _pendingSwitch.asStateFlow()
    private val _toastEvent = MutableStateFlow<String?>(null)
    val toastEvent = _toastEvent.asStateFlow()
    private val tmdbState = MutableStateFlow(
        ConfigTmdbState(
            enabled = KV.get(KVKeys.TMDB_ENABLE, false),
            posterStyle = TmdbPosterStyle.of(KV.get(KVKeys.TMDB_POSTER_STYLE, 0)),
            apiKey = KV.get(KVKeys.TMDB_API_KEY, ""),
            apiBase = KV.get(KVKeys.TMDB_API_BASE, ""),
            imageBase = KV.get(KVKeys.TMDB_IMAGE_BASE, ""),
        ),
    )
    private val tmdbTesting = MutableStateFlow(TmdbTest.None)
    private val copyCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            ApiLineSignal.version.collect { refreshActiveSnapshot() }
        }
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) refreshActiveSnapshot()
            }
        }
        refreshMultiRepoMarks()
    }

    /**
     * 识别哪些源是多仓：先看已记录的标记；没标记的读一次配置（下载缓存 / 本地文件），
     * 里面带 urls 线路列表的就是多仓（顺手存标记，下次直接命中）。
     * 这样即使源已经被停用、根本不会再加载，也能亮出「多仓」。
     */
    fun refreshMultiRepoMarks() {
        viewModelScope.launch(Dispatchers.IO) {
            val found = HashSet<String>()
            val groups = listOf(_vodItems.value to false, _liveItems.value to true)
            for ((rawItems, live) in groups) {
                for (raw in rawItems) {
                    val url = parseSubscribe(raw).url
                    if (url.isEmpty()) continue
                    if (HistoryHelper.isMultiRepoSource(url, live)) {
                        found.add(url)
                        continue
                    }
                    if (configIsDepot(url)) {
                        HistoryHelper.markMultiRepoSource(url, live)
                        found.add(url)
                    }
                }
            }
            _multiRepoUrls.value = found
        }
    }

    /** 读这个源的配置（先看下载缓存，再看本地文件），判断是不是多仓（带 urls 线路列表）。 */
    private fun configIsDepot(url: String): Boolean {
        val json = readConfigText(url) ?: return false
        return try {
            ConfigParser.parseApiCollection(json).isNotEmpty()
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun readConfigText(url: String): String? {
        return try {
            val app = AppContextHolder.context()
            if (app != null) {
                val cache = File(app.filesDir.absolutePath + "/" + MD5.encode(url))
                if (cache.exists() && cache.length() > 0L) return cache.readText()
            }
            val local = localConfigFile(url)
            if (local != null && local.exists() && local.length() > 0L) return local.readText()
            null
        } catch (ignored: Throwable) {
            null
        }
    }

    /** clan://localhost/… 、file://… 这类本地源地址换算成本机文件。 */
    private fun localConfigFile(url: String): File? {
        var raw = url
        val cut = raw.indexOf(";md5;").takeIf { it >= 0 } ?: raw.indexOf(";pk;").takeIf { it >= 0 }
        if (cut != null && cut >= 0) raw = raw.substring(0, cut)
        val query = raw.indexOf('?')
        if (query >= 0) raw = raw.substring(0, query)
        return when {
            raw.startsWith("clan://localhost/") -> File(
                Environment.getExternalStorageDirectory().absolutePath + "/" +
                    Uri.decode(raw.removePrefix("clan://localhost/"))
            )
            raw.startsWith("file://") -> File(Uri.decode(raw.removePrefix("file://")))
            raw.startsWith("/") -> File(raw)
            else -> null
        }
    }

    fun tmdbState(): StateFlow<ConfigTmdbState> = tmdbState.asStateFlow()

    fun tmdbTesting(): StateFlow<TmdbTest> = tmdbTesting.asStateFlow()

    fun tmdbActions(): ConfigTmdbActions = ConfigTmdbActions(
        setEnabled = { value -> updateTmdbEnabled(value) },
        setPosterStyle = { style -> updateTmdbPosterStyle(style) },
        setApiKey = { value -> updateTmdbApiKey(value) },
        setApiBase = { value -> updateTmdbApiBase(value) },
        setImageBase = { value -> updateTmdbImageBase(value) },
        commitApiBase = { commitTmdbApiBase() },
        commitImageBase = { commitTmdbImageBase() },
        testApi = { testTmdbApi() },
        testImage = { testTmdbImage() },
    )

    private fun updateTmdbEnabled(enabled: Boolean) {
        KV.put(KVKeys.TMDB_ENABLE, enabled)
        tmdbState.value = tmdbState.value.copy(enabled = enabled)
        TmdbPoster.notifyConfigChanged()
    }

    private fun updateTmdbPosterStyle(style: TmdbPosterStyle) {
        KV.put(KVKeys.TMDB_POSTER_STYLE, style.ordinal)
        tmdbState.value = tmdbState.value.copy(posterStyle = style)
        TmdbPoster.notifyConfigChanged()
    }

    private fun updateTmdbApiKey(value: String) {
        KV.put(KVKeys.TMDB_API_KEY, value)
        tmdbState.value = tmdbState.value.copy(apiKey = value)
        TmdbPoster.notifyConfigChanged()
    }

    private fun updateTmdbApiBase(value: String) {
        tmdbState.value = tmdbState.value.copy(apiBase = value, apiBaseError = false)
    }

    private fun updateTmdbImageBase(value: String) {
        tmdbState.value = tmdbState.value.copy(imageBase = value, imageBaseError = false)
    }

    private fun commitTmdbApiBase() {
        val normalized = TmdbApi.normalizeBaseUrl(tmdbState.value.apiBase)
        KV.put(KVKeys.TMDB_API_BASE, normalized)
        tmdbState.value = tmdbState.value.copy(
            apiBase = normalized,
            apiBaseError = normalized.isNotEmpty() && !TmdbApi.isValidBaseUrl(normalized),
        )
        TmdbPoster.notifyConfigChanged()
    }

    private fun commitTmdbImageBase() {
        val normalized = TmdbApi.normalizeBaseUrl(tmdbState.value.imageBase)
        KV.put(KVKeys.TMDB_IMAGE_BASE, normalized)
        tmdbState.value = tmdbState.value.copy(
            imageBase = normalized,
            imageBaseError = normalized.isNotEmpty() && !TmdbApi.isValidBaseUrl(normalized),
        )
        TmdbPoster.notifyConfigChanged()
    }

    private fun testTmdbApi() {
        if (!beginTmdbTest(TmdbTest.Api)) return
        tmdbState.value = tmdbState.value.copy(apiBaseError = false)
        val apiKey = tmdbState.value.apiKey.trim()
        if (apiKey.isEmpty()) {
            _toastEvent.value = str(R.string.tmdb_key_required)
            endTmdbTest(TmdbTest.Api)
            return
        }
        viewModelScope.launch {
            val result = try {
                tmdbClientFactory().testApi(apiKey, tmdbState.value.apiBase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("TmdbApi", e)
                false
            }
            _toastEvent.value = str(
                if (result) R.string.tmdb_test_api_ok else R.string.tmdb_test_api_fail,
            )
            endTmdbTest(TmdbTest.Api)
        }
    }

    private fun testTmdbImage() {
        if (!beginTmdbTest(TmdbTest.Image)) return
        tmdbState.value = tmdbState.value.copy(imageBaseError = false)
        val apiKey = tmdbState.value.apiKey.trim()
        if (apiKey.isEmpty()) {
            _toastEvent.value = str(R.string.tmdb_key_required)
            endTmdbTest(TmdbTest.Image)
            return
        }
        viewModelScope.launch {
            val result = try {
                tmdbClientFactory().testImage(tmdbState.value.imageBase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("TmdbApi", e)
                false
            }
            _toastEvent.value = str(
                if (result) R.string.tmdb_test_image_ok else R.string.tmdb_test_image_fail,
            )
            endTmdbTest(TmdbTest.Image)
        }
    }

    private fun beginTmdbTest(test: TmdbTest): Boolean {
        if (tmdbTesting.value != TmdbTest.None) return false
        tmdbTesting.value = test
        return true
    }

    private fun endTmdbTest(test: TmdbTest) {
        if (tmdbTesting.value == test) tmdbTesting.value = TmdbTest.None
    }

    private fun str(resId: Int, vararg args: Any): String {
        val context = AppContextHolder.context() ?: return ""
        return LanguageManager.localized(context).getString(resId, *args)
    }

    fun clearToast() {
        _toastEvent.value = null
    }

    fun cancelPendingSwitch() {
        _pendingSwitch.value = null
    }

    private fun refreshActiveSnapshot() {
        _activeUrl.value = KV.get(KVKeys.API_URL, "")
        _liveActiveUrl.value = KV.get(KVKeys.LIVE_API_URL, "")
        _liveFollow.value = ApiConfig.isLiveFollowVod()
        // 切换源、加载完配置后重新识别一次多仓标记（新源第一次加载时就地补标记）。
        refreshMultiRepoMarks()
    }

    fun openEdit(target: SubscribeSource?) {
        _editTarget.value = target
    }

    fun closeEdit() {
        _editTarget.value = null
    }

    fun onModeChanged() {
        _manageMode.value = false
        _selected.value = emptySet()
        closeEdit()
    }

    fun exitManageMode() {
        onModeChanged()
    }

    fun toggleSelected(value: String) {
        val cur = _selected.value
        setSelected(if (value in cur) cur - value else cur + value)
    }

    fun longPressSelect(value: String) {
        _selected.value = setOf(value)
        _manageMode.value = true
    }

    private fun setSelected(next: Set<String>) {
        _selected.value = next
        if (next.isEmpty()) _manageMode.value = false
    }

    private fun isInUse(url: String, vod: Boolean): Boolean =
        if (vod) {
            url == _activeUrl.value || HistoryHelper.isApiLineSourceOf(url, _activeUrl.value)
        } else {
            (!_liveFollow.value && url == _liveActiveUrl.value) ||
                (!_liveFollow.value && HistoryHelper.isLiveApiLineSourceOf(url, _liveActiveUrl.value))
        }

    private fun activeInEitherMode(url: String): Boolean {
        val vodApi = KV.get(KVKeys.API_URL, "")
        val liveApi = KV.get(KVKeys.LIVE_API_URL, "")
        return url == vodApi || url == liveApi ||
            HistoryHelper.isApiLineSourceOf(url, vodApi) ||
            HistoryHelper.isLiveApiLineSourceOf(url, liveApi)
    }

    private fun referencedBySubscribes(url: String): Boolean =
        loadSubscribes(SubscribeMode.Vod).any { parseSubscribe(it).url == url } ||
            loadSubscribes(SubscribeMode.Live).any { parseSubscribe(it).url == url } ||
            loadSubscribes(SubscribeMode.Local).any { parseSubscribe(it).url == url }

    private fun referencedByRepo(url: String): Boolean =
        (HistoryHelper.getApiLines().orEmpty() + HistoryHelper.liveApiLines().orEmpty())
            .any { HistoryHelper.apiLineUrl(it) == url }

    private fun switchToVod(item: SubscribeSource) {
        if (_activeUrl.value == item.url) return
        val followLive = applyVodSource(item)
        _activeUrl.value = item.url
        if (followLive) {
            _liveActiveUrl.value = ""
            _liveFollow.value = true
        }
    }

    private fun switchToLive(item: SubscribeSource) {
        if (!_liveFollow.value && _liveActiveUrl.value == item.url) return
        applyLiveSource(item)
        _liveActiveUrl.value = item.url
        _liveFollow.value = false
    }

    fun requestSwitch(item: SubscribeSource, vod: Boolean) {
        if (item.url in _disabledUrls.value) {
            _pendingSwitch.value = PendingSwitch(item, vod)
        } else if (vod) {
            switchToVod(item)
        } else {
            switchToLive(item)
        }
    }

    fun enableAndSwitch() {
        val pending = _pendingSwitch.value ?: return
        _pendingSwitch.value = null
        BootGuard.enableSource(pending.item.url)
        _disabledUrls.value = _disabledUrls.value - pending.item.url
        if (pending.vod) switchToVod(pending.item) else switchToLive(pending.item)
    }

    fun followLiveNow() {
        applyLiveFollowVod()
        _liveActiveUrl.value = ""
        _liveFollow.value = true
        _toastEvent.value = str(R.string.toast_live_follow_vod)
    }

    /**
     * 点播／直播列表里的开关：开 = 启用并切到它；关 = 停用这个源。
     * 只有一个源时同样可以关（用户要求开关随便开关），关掉后应用就是「没有可用源」的状态；
     * 如果关的正是当前在用的源，先退到同列表里其它还启用的源，没有再退到空／跟着点播。
     */
    fun setSourceEnabled(item: SubscribeSource, vod: Boolean, enabled: Boolean) {
        if (enabled) {
            if (item.url in _disabledUrls.value) {
                BootGuard.enableSource(item.url)
                _disabledUrls.value = _disabledUrls.value - item.url
            }
            if (vod) switchToVod(item) else switchToLive(item)
            return
        }
        BootGuard.disableSource(item.url)
        _disabledUrls.value = _disabledUrls.value + item.url
        if (!isInUse(item.url, vod)) return
        val next = (if (vod) _vodItems.value else _liveItems.value)
            .map { parseSubscribe(it) }
            .firstOrNull { it.url != item.url && it.url !in _disabledUrls.value }
        when {
            vod && next != null -> switchToVod(next)
            vod -> clearActiveVod()
            next != null -> switchToLive(next)
            else -> followLiveNow()
        }
    }

    /** 停用了当前在用的点播源、且没有别的可用源：清空点播配置，首页回到无源状态。 */
    private fun clearActiveVod() {
        ApiConfig.get().clearVodConfig()
        _activeUrl.value = ""
        AppBootstrap.retry()
    }

    /**
     * 片源开关：多个片源可同时启用，启用集合合并成一份配置使用。
     * 打开某个片源 = 启用并切到合并配置；关闭后还有其他启用片源则重建合并配置并重载；
     * 全部关闭且当前用的正是合并配置时，清掉当前点播配置。
     */
    fun setLocalSourceEnabled(item: SubscribeSource, enabled: Boolean) {
        val current = _localEnabled.value
        if ((item.url in current) == enabled) return
        if (enabled && item.url in _disabledUrls.value) {
            BootGuard.enableSource(item.url)
            _disabledUrls.value = _disabledUrls.value - item.url
        }
        _localEnabled.value = if (enabled) current + item.url else current - item.url
        persistLocalEnabled()
        rebuildAndApplyLocal(activate = enabled)
    }

    private fun rebuildAndApplyLocal(activate: Boolean) {
        viewModelScope.launch {
            val urls = _localItems.value.map { parseSubscribe(it).url }
                .filter { it in _localEnabled.value }
            val mergedUrl = withContext(Dispatchers.IO) { LocalMergedConfig.rebuild(urls) }
            applyMergedLocal(mergedUrl, activate)
        }
    }

    private fun applyMergedLocal(mergedUrl: String?, activate: Boolean) {
        if (mergedUrl == null) {
            // 没有启用的片源了：当前用的若是合并配置就清掉。
            val merged = LocalMergedConfig.mergedUrl()
            if (merged != null && _activeUrl.value == merged) {
                ApiConfig.get().clearVodConfig()
                _activeUrl.value = ""
                AppBootstrap.retry()
            }
            return
        }
        when {
            // 合并内容变了但地址没变：让配置层按同一地址强制重载。
            _activeUrl.value == mergedUrl -> AppBootstrap.onApiUrlChanged()
            activate -> switchToVod(SubscribeSource(mergedUrl, mergedUrl))
        }
    }

    fun deleteSelected(mode: SubscribeMode) {
        val items = when (mode) {
            SubscribeMode.Vod -> _vodItems.value
            SubscribeMode.Live -> _liveItems.value
            SubscribeMode.Local -> _localItems.value
        }
        // 正在使用的源也可以直接删（han 要求）：删掉生效中的线就把对应配置清掉，
        // 不再像以前那样拦下来。
        val target = _selected.value
        val remaining = items.filterNot { it in target }
        KV.put(subscribeKeyOf(mode), ArrayList(remaining))
        val removedUrls = target.map { parseSubscribe(it).url }
        val removedActiveVod = removedUrls.any { isInUse(it, true) }
        val removedActiveLive = removedUrls.any { isInUse(it, false) }
        BootGuard.forgetSources(removedUrls)
        _disabledUrls.value = _disabledUrls.value - removedUrls
        when (mode) {
            SubscribeMode.Vod -> {
                _vodItems.value = remaining
                if (remaining.isEmpty() || removedActiveVod) {
                    ApiConfig.get().clearVodConfig()
                    _activeUrl.value = ""
                    AppBootstrap.retry()
                }
            }
            SubscribeMode.Live -> {
                _liveItems.value = remaining
                if (remaining.isEmpty() || removedActiveLive) {
                    applyLiveFollowVod()
                    _liveActiveUrl.value = ""
                    _liveFollow.value = true
                }
            }
            SubscribeMode.Local -> {
                _localItems.value = remaining
                if (_localEnabled.value.any { it in removedUrls }) {
                    _localEnabled.value = _localEnabled.value - removedUrls
                    persistLocalEnabled()
                    rebuildAndApplyLocal(activate = false)
                }
            }
        }
        // 副本清理放到最后：生效配置已先清掉，刚删掉的在用源副本也能一并清干净。
        val copyUrls = removedUrls.filterNot {
            activeInEitherMode(it) || referencedBySubscribes(it) || referencedByRepo(it)
        }
        if (copyUrls.isNotEmpty()) {
            copyCleanupScope.launch { copyUrls.forEach { removeLocalCopy(it) } }
        }
        setSelected(emptySet())
    }

    fun commitAdd(vod: Boolean, name: String, url: String) {
        val mode = if (vod) SubscribeMode.Vod else SubscribeMode.Live
        val newItems = saveSubscribe(mode, name, url)
        if (vod) _vodItems.value = newItems else _liveItems.value = newItems
        if (newItems.size == 1) {
            val item = parseSubscribe(newItems.first())
            if (vod) switchToVod(item) else switchToLive(item)
        }
    }

    /** 本地接口等非蜘蛛文件进点播订阅（外部打开方式分流用；.py/.js 走下面 addLocalLineSource）。 */
    fun addLocalSource(name: String, url: String): Boolean {
        val existed = loadSubscribes(SubscribeMode.Vod).any { parseSubscribe(it).url == url }
        _vodItems.value = saveSubscribe(SubscribeMode.Vod, name, url)
        return existed
    }

    /**
     * 片源线导入：与点播订阅分开存放。导入即启用（加入启用集合），
     * 启用集合合并成一份配置走点播通道，与点播订阅互斥（同一时刻只有一条线在生效）。
     * 多条片源可同时启用一起用，不用的在片源页用开关关掉。
     */
    fun addLocalLineSource(name: String, url: String): Boolean {
        val existed = loadSubscribes(SubscribeMode.Local).any { parseSubscribe(it).url == url }
        val newItems = saveSubscribe(SubscribeMode.Local, name, url)
        _localItems.value = newItems
        if (url !in _localEnabled.value) {
            _localEnabled.value = _localEnabled.value + url
            persistLocalEnabled()
        }
        rebuildAndApplyLocal(activate = true)
        return existed
    }

    fun commitEdit(vod: Boolean, target: SubscribeSource, name: String, url: String) {
        if (url.isEmpty()) return
        val mode = if (vod) SubscribeMode.Vod else SubscribeMode.Live
        val newValue = (name.ifEmpty { url }) + SUBSCRIBE_SPLIT + url
        val oldValue = _selected.value.firstOrNull { parseSubscribe(it).url == target.url }
        val updated = updateSubscribe(mode, target, name, url)
        if (vod) _vodItems.value = updated else _liveItems.value = updated
        if (oldValue != null) _selected.value = _selected.value - oldValue + newValue
        closeEdit()
        val item = parseSubscribe(newValue)
        if (vod) {
            if (target.url == _activeUrl.value && url != _activeUrl.value) switchToVod(item)
        } else if (!_liveFollow.value && target.url == _liveActiveUrl.value && url != _liveActiveUrl.value) {
            switchToLive(item)
        }
    }

    private fun applyVodSource(item: SubscribeSource): Boolean =
        AppBootstrap.switchVodSubscription(item.url)

    private fun applyLiveSource(item: SubscribeSource) {
        ConfigRepository.switchLiveSource(item.url)
    }

    private fun applyLiveFollowVod() {
        ConfigRepository.switchLiveSource("")
    }

    private fun subscribeKeyOf(mode: SubscribeMode): String = when (mode) {
        SubscribeMode.Vod -> KVKeys.SUBSCRIBE_LIST
        SubscribeMode.Live -> KVKeys.LIVE_SUBSCRIBE_LIST
        SubscribeMode.Local -> KVKeys.LOCAL_SOURCE_LIST
    }

    private fun loadSubscribes(mode: SubscribeMode): List<String> =
        KV.get(subscribeKeyOf(mode), ArrayList<String>()).toList()

    /**
     * 片源启用集合。首次没有存档时按旧的单线模型迁移：
     * 旧模型里正在生效的那条片源线视为启用，其余视为关闭。
     * 注意初始化顺序：这里只读 KV，不碰尚未初始化的 _activeUrl。
     */
    private fun loadLocalEnabled(): Set<String> {
        val urls = _localItems.value.map { parseSubscribe(it).url }
        if (KV.contains(KVKeys.LOCAL_SOURCE_ENABLED)) {
            val saved = KV.get(KVKeys.LOCAL_SOURCE_ENABLED, ArrayList<String>())
            return urls.filter { it in saved }.toSet()
        }
        val active = KV.get(KVKeys.API_URL, "")
        return urls.filter { it == active || HistoryHelper.isApiLineSourceOf(it, active) }.toSet()
    }

    private fun persistLocalEnabled() {
        KV.put(KVKeys.LOCAL_SOURCE_ENABLED, ArrayList(_localEnabled.value))
    }

    private fun saveSubscribe(mode: SubscribeMode, name: String, url: String): List<String> {
        val value = (name.ifEmpty { url }) + SUBSCRIBE_SPLIT + url
        val list = ArrayList(loadSubscribes(mode))
        val existIndex = list.indexOfFirst { parseSubscribe(it).url == url }
        if (existIndex >= 0) list[existIndex] = value else list.add(value)
        KV.put(subscribeKeyOf(mode), list)
        return list
    }

    private fun updateSubscribe(mode: SubscribeMode, original: SubscribeSource, name: String, url: String): List<String> {
        val value = (name.ifEmpty { url }) + SUBSCRIBE_SPLIT + url
        val list = ArrayList(loadSubscribes(mode))
        val index = list.indexOfFirst { parseSubscribe(it).url == original.url }
        if (index < 0) return list
        list[index] = value
        val dupIndex = list.indexOfFirst { it != value && parseSubscribe(it).url == url }
        if (dupIndex >= 0) list.removeAt(dupIndex)
        KV.put(subscribeKeyOf(mode), list)
        return list
    }

    companion object {
        fun defaultFactory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConfigManageViewModel { TmdbApi.DefaultTmdbClient() } }
        }
    }
}
