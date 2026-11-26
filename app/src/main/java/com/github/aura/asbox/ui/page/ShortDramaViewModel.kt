package com.github.aura.asbox.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.aura.asbox.api.ApiConfig
import com.github.aura.asbox.bean.AbsSortXml
import com.github.aura.asbox.bean.AbsXml
import com.github.aura.asbox.bean.Movie
import com.github.aura.asbox.bean.MovieSort
import com.github.aura.asbox.bean.SourceBean
import com.github.aura.asbox.sourcedata.ListLoader
import com.github.aura.asbox.sourcedata.PushDetailResolver
import com.github.aura.asbox.sourcedata.SourceChannel
import com.github.aura.asbox.sourcedata.SourceResultParser
import com.github.aura.asbox.sourcedata.SourceRuntimeState
import com.github.aura.asbox.sourcedata.SourceViewModel
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 短剧页数据：从当前生效的点播配置（订阅接口或合并后的片源）里挑出名字带「短剧」的站点，
 * 逐站取首页推荐列表（复用现成的 ListLoader.homeRecList，与首页推荐同一条取数路径）。
 * 某个站点取不到内容就跳过，不影响其他站点。
 */
class ShortDramaViewModel : ViewModel() {

    data class Section(val source: SourceBean, val videos: List<Movie.Video>)

    private val _sections = MutableStateFlow<List<Section>>(emptyList())
    val sections = _sections.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _siteCount = MutableStateFlow(0)
    val siteCount = _siteCount.asStateFlow()

    private val gson = Gson()
    private val detailChannel = SourceChannel<AbsXml?>()
    private val parser = SourceResultParser(
        gson,
        SourceChannel(),
        detailChannel,
        PushDetailResolver(gson, detailChannel),
    )
    private val listLoader = ListLoader(gson, SourceRuntimeState.extendCache, SourceChannel(), parser)

    // ---- 分类浏览（按站点分类，复用点播同款 SourceViewModel 取数） ----
    private val sourceVm = SourceViewModel()
    private val _sites = MutableStateFlow<List<SourceBean>>(emptyList())
    val sites = _sites.asStateFlow()
    private val _selectedSiteKey = MutableStateFlow<String?>(null)
    val selectedSiteKey = _selectedSiteKey.asStateFlow()
    private val _categories = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val categories = _categories.asStateFlow()
    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId = _selectedCategoryId.asStateFlow()
    private val _browseVideos = MutableStateFlow<List<Movie.Video>>(emptyList())
    val browseVideos = _browseVideos.asStateFlow()
    private val _browseLoading = MutableStateFlow(false)
    val browseLoading = _browseLoading.asStateFlow()
    private val _browseEnded = MutableStateFlow(false)
    val browseEnded = _browseEnded.asStateFlow()

    private var pendingSort: ((AbsSortXml?) -> Unit)? = null
    private var pendingList: ((AbsXml?) -> Unit)? = null
    private var browsePage = 0

    init {
        viewModelScope.launch {
            sourceVm.sortResult.flow.collect { result ->
                val cb = pendingSort
                pendingSort = null
                cb?.invoke(result)
            }
        }
        viewModelScope.launch {
            sourceVm.listResult.flow.collect { result ->
                val cb = pendingList
                pendingList = null
                cb?.invoke(result)
            }
        }
    }

    /** 选站点：null 回到聚合推荐；选了站点就拉它的分类，自动打开第一个分类。 */
    fun selectSite(site: SourceBean?) {
        pendingSort = null
        pendingList = null
        _selectedSiteKey.value = site?.key
        _categories.value = emptyList()
        _selectedCategoryId.value = null
        _browseVideos.value = emptyList()
        _browseEnded.value = false
        _browseLoading.value = false
        browsePage = 0
        if (site == null) return
        _browseLoading.value = true
        pendingSort = { sortXml ->
            if (_selectedSiteKey.value == site.key) {
                val cats = sortXml?.classes?.sortList
                    ?.filter { !it.id.isNullOrBlank() }
                    ?: emptyList()
                _categories.value = cats
                _browseLoading.value = false
                cats.firstOrNull()?.let { selectCategory(it) }
            }
        }
        sourceVm.getSort(site.key, false)
    }

    fun selectCategory(cat: MovieSort.SortData) {
        if (_selectedSiteKey.value == null) return
        _selectedCategoryId.value = cat.id
        _browseVideos.value = emptyList()
        _browseEnded.value = false
        browsePage = 0
        requestBrowsePage(cat, 1)
    }

    fun loadMore() {
        if (_browseLoading.value || _browseEnded.value) return
        val cat = _categories.value.firstOrNull { it.id == _selectedCategoryId.value } ?: return
        requestBrowsePage(cat, browsePage + 1)
    }

    private fun requestBrowsePage(cat: MovieSort.SortData, page: Int) {
        val key = _selectedSiteKey.value ?: return
        _browseLoading.value = true
        pendingList = { absXml ->
            if (_selectedSiteKey.value == key && _selectedCategoryId.value == cat.id) {
                val videos = absXml?.movie?.videoList ?: emptyList()
                _browseVideos.value =
                    if (page == 1) videos else _browseVideos.value + videos
                browsePage = page
                if (videos.isEmpty()) _browseEnded.value = true
                _browseLoading.value = false
            }
        }
        sourceVm.getList(key, cat, page)
    }

    private var started = false

    fun load() {
        if (started) return
        started = true
        viewModelScope.launch {
            val sites = ApiConfig.get().getSourceBeanList().filter { bean ->
                val name = bean.name.orEmpty()
                name.contains("短剧") || name.contains("短劇")
            }
            _siteCount.value = sites.size
            _sites.value = sites
            if (sites.isEmpty()) {
                _loading.value = false
                return@launch
            }
            val deferred = sites.map { bean ->
                async { bean to runCatching { listLoader.homeRecList(bean, null) }.getOrNull() }
            }
            deferred.forEach { d ->
                val (bean, videos) = d.await()
                if (!videos.isNullOrEmpty()) {
                    _sections.value = _sections.value + Section(bean, videos)
                }
            }
            _loading.value = false
        }
    }
}
