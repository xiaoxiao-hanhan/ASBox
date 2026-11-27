package com.github.aura.asbox.ui.drama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.aura.asbox.R
import com.github.aura.asbox.api.ApiConfig
import com.github.aura.asbox.bean.AbsSortXml
import com.github.aura.asbox.bean.AbsXml
import com.github.aura.asbox.bean.Movie
import com.github.aura.asbox.bean.MovieSort
import com.github.aura.asbox.bean.SourceBean
import com.github.aura.asbox.bean.VodInfo
import com.github.aura.asbox.sourcedata.ListLoader
import com.github.aura.asbox.sourcedata.PushDetailResolver
import com.github.aura.asbox.sourcedata.SourceChannel
import com.github.aura.asbox.sourcedata.SourceResultParser
import com.github.aura.asbox.sourcedata.SourceRuntimeState
import com.github.aura.asbox.sourcedata.SourceViewModel
import com.github.aura.asbox.util.KV
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 短剧播放器（竖屏信息流）的数据层。
 *
 * 数据来源与短剧片库同一条路：从当前生效的点播配置里挑出名字带「短剧」的站点，
 * 取该站的首页推荐列表当作一屏一剧的流。选中某一屏时再单独拉一次详情，
 * 拿到线路/集数后丢给播放容器播放——这样列表页不必等详情，详情也不会拖慢滑动。
 */
class DramaFeedViewModel : ViewModel() {

    enum class Tab(val labelRes: Int) {
        Anime(R.string.drama_feed_tab_anime),
        Real(R.string.drama_feed_tab_real),
        Recommend(R.string.drama_feed_tab_recommend),
    }

    data class FeedItem(
        val video: Movie.Video,
        val siteKey: String,
        val siteName: String,
        /** 来自哪个分类（如「漫剧」「真人剧」）。推荐打散时据此判断类型，比视频字段可靠。 */
        val categoryName: String = "",
    )

    /** 一屏内容准备好播放时发给播放器宿主的请求。seq 用来区分过期的请求。 */
    data class PlayRequest(val info: VodInfo, val siteKey: String, val seq: Long)

    private val gson = Gson()
    private val detailChannel = SourceChannel<AbsXml?>()
    private val parser = SourceResultParser(
        gson,
        SourceChannel(),
        detailChannel,
        PushDetailResolver(gson, detailChannel),
    )
    private val listLoader = ListLoader(gson, SourceRuntimeState.extendCache, SourceChannel(), parser)
    private val sourceVm = SourceViewModel()

    /** SourceChannel 是热流，转成一次性队列，方便按请求「取本次结果」。 */
    private val detailQueue = Channel<AbsXml?>(Channel.CONFLATED)
    private val sortQueue = Channel<AbsSortXml?>(Channel.CONFLATED)
    private val listQueue = Channel<AbsXml?>(Channel.CONFLATED)

    private val _sites = MutableStateFlow<List<SourceBean>>(emptyList())
    val sites = _sites.asStateFlow()

    private val _siteKey = MutableStateFlow("")
    val siteKey = _siteKey.asStateFlow()

    private val _tab = MutableStateFlow(Tab.Recommend)
    val tab = _tab.asStateFlow()

    private val _items = MutableStateFlow<List<FeedItem>>(emptyList())
    val items = _items.asStateFlow()

    private val _index = MutableStateFlow(0)
    val index = _index.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _preparing = MutableStateFlow(false)
    val preparing = _preparing.asStateFlow()

    private val _empty = MutableStateFlow(false)
    val empty = _empty.asStateFlow()

    /**
     * 当前分类在本源（以及能找的其它源）都没有内容。
     * 只用来弹一条轻提示——**不换掉正在播的这屏**，页面不会变黑。
     */
    private val _tabEmpty = MutableStateFlow(false)
    val tabEmpty = _tabEmpty.asStateFlow()

    private val _playRequest = MutableStateFlow<PlayRequest?>(null)
    val playRequest = _playRequest.asStateFlow()

    /** 当前这一屏的详情（标题/简介/集数用它，比列表项更全）。 */
    private val _currentInfo = MutableStateFlow<VodInfo?>(null)
    val currentInfo = _currentInfo.asStateFlow()

    private val _message = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

    /** 自动换了片源时提示源名。 */
    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notice = _notice.asSharedFlow()

    /** 用户手选的片源一屏内容都取不到时，回传源名，让界面明确提示一声。 */
    private val _switchFailed = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val switchFailed = _switchFailed.asSharedFlow()

    /** 正在切到用户手选的片源（换源比切分类慢，给个可见的进度，别让人以为按钮没反应）。 */
    private val _switching = MutableStateFlow(false)
    val switching = _switching.asStateFlow()

    /** 每个片源的首页列表，切分类 / 跨源找内容时复用，不重复请求。 */
    private val homeCache = HashMap<String, List<FeedItem>>()
    /**
     * 每个标签上一次筛好的列表。注意：点击 tab 不再直接吃这份缓存（会重复），
     * 现在只作为「重新拉取失败时的兜底」，避免刷新失败整页变空。
     */
    private val tabCache = HashMap<Tab, List<FeedItem>>()
    /** 每个 tab 最近展示过的剧 id：刷新时把没看过的排前面，避免一直重复同一批。 */
    private val tabShownIds = HashMap<Tab, LinkedHashSet<String>>()
    /** 所有内容都看过后，用刷新次数循环移位换个起点。 */
    private val tabRotation = HashMap<Tab, Int>()
    private val categoryCache = HashMap<String, List<MovieSort.SortData>>()
    private val detailCache = HashMap<String, VodInfo>()
    private var detailJob: Job? = null
    /** 列表加载任务：连续换源时把上一次取消掉，免得两次结果互相覆盖（看着像换源没反应）。 */
    private var loadJob: Job? = null
    private var playSeq = 0L
    private var started = false
    /** 最近一次起播的剧 id：换分类时用它把「当前这一屏」对上。 */
    private var lastPlayedId = ""

    init {
        // 恢复上次退出时记住的「已看过」记录：重进后直接从没看过的继续，不从头重复。
        restoreRefreshState()
        viewModelScope.launch {
            sourceVm.detailResult.flow.collect { detailQueue.trySend(it) }
        }
        viewModelScope.launch {
            sourceVm.sortResult.flow.collect { sortQueue.trySend(it) }
        }
        viewModelScope.launch {
            sourceVm.listResult.flow.collect { listQueue.trySend(it) }
        }
    }

    // ---------------- 站点 / 分类 ----------------

    fun start() {
        if (started) return
        started = true
        // 重进应用也走一次重排：把「已看过」的压到队尾，直接从没看过的继续。
        // 已有 KV 存档时不会从头开始，也没有存档时行为与之前一致。
        load(currentSite = null, refresh = true)
    }

    fun selectSite(key: String) {
        if (key.isEmpty() || key == _siteKey.value) return
        load(currentSite = key, userPicked = true)
    }

    /**
     * 点击顶部三个分类：每次都当作一次「换一批」的刷新。
     *
     * 旧实现命中 [tabCache] 就秒返回旧列表、还用 lastPlayedId 停在原来的那一屏，
     * 于是「推荐/真人剧/漫剧」点来点去都是同一批同一条——用户以为点击没反应。
     * 现在：
     * - 点已选中的 tab 也刷新（重复点就是主动换一批）；
     * - 该 tab 的首页缓存清掉、重新拉（分类视频本就实时请求）；
     * - 拉回来的列表用「最近看过的排后面」重排，起点归零，保证看到的确实换了。
     */
    fun selectTab(tab: Tab) {
        _tab.value = tab
        _tabEmpty.value = false
        // 每次点击都重新拉（refresh 会清首页缓存、分类视频本就实时请求）。
        // tabCache 保留：它是这次拉取失败时的兜底，不能在这里删。
        load(currentSite = _siteKey.value, allowSourceSwitch = true, refresh = true)
    }

    fun retry() {
        load(currentSite = _siteKey.value.ifEmpty { null }, allowSourceSwitch = true)
    }

    /**
     * 拉列表。[allowSourceSwitch] = true 时，如果当前片源没有当前分类的内容，
     * 会按站点顺序去其它片源找，找到了自动切过去并提示（漫剧/真人剧不至于点出空白页）。
     *
     * [userPicked] = true 表示用户在换源面板里**手选**了这个源：换不过去必须明确说出来，
     * 不能一声不响保持原样——以前那样看着就像「这个源换不了」。
     */
    private fun load(
        currentSite: String?,
        allowSourceSwitch: Boolean = false,
        userPicked: Boolean = false,
        refresh: Boolean = false,
    ) {
        // 上一次加载还没回来就又换源：先取消，免得两个请求的结果互相覆盖。
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (userPicked) _switching.value = true
            try {
                loadNow(currentSite, allowSourceSwitch, userPicked, refresh)
            } finally {
                if (userPicked) _switching.value = false
            }
        }
    }

    private suspend fun loadNow(
        currentSite: String?,
        allowSourceSwitch: Boolean,
        userPicked: Boolean,
        refresh: Boolean = false,
    ) {
        if (_sites.value.isEmpty()) {
            val all = ApiConfig.get().getSourceBeanList()
            val drama = all.filter { bean ->
                val name = bean.name.orEmpty()
                name.contains("短剧") || name.contains("短劇")
            }
            _sites.value = drama.ifEmpty { ApiConfig.get().switchSourceBeanList() }
        }
        val start = currentSite?.let { key -> _sites.value.firstOrNull { it.key == key } }
            ?: _sites.value.firstOrNull { it.key == _siteKey.value }
            ?: _sites.value.firstOrNull()
        if (start == null) {
            _loading.value = false
            if (_items.value.isEmpty()) _empty.value = true else _tabEmpty.value = true
            return
        }
        _loading.value = true
        _empty.value = false
        _tabEmpty.value = false

        var target = start
        val startKey = start.key.orEmpty()
        // 主动刷新：丢掉首页缓存，保证真正重新拉取；分类视频本来就是每次实时请求。
        // 分类名缓存保留（分类列表很少变，清了只是白多一次请求）。
        if (refresh) homeCache.remove(startKey)
        // 手选换源：整体给个预算。源自己挂掉时也能及时回一句「没取到内容」，
        // 不是一直转圈或者静悄悄没反应。
        var items = if (userPicked) {
            withTimeoutOrNull(SWITCH_TIMEOUT_MS) { buildItems(start, startKey) }.orEmpty()
        } else {
            buildItems(start, startKey)
        }
        if (items.isEmpty() && allowSourceSwitch) {
            val hit = searchOtherSources(start)
            if (hit != null) {
                target = hit.first
                items = hit.second
                val name = target.name.orEmpty()
                if (name.isNotEmpty()) _notice.tryEmit(name)
            }
        }

        // 主动刷新：把「最近看过的」排到后面，起点归零，保证这次看到的确实是新一批；
        // 刷新拉取失败时退回上一次的列表兜底，但同样重排，不让页面变空也不重复老顺序。
        if (refresh) {
            if (items.isNotEmpty()) {
                items = reorderForRefresh(items, _tab.value)
            } else {
                val fallback = tabCache[_tab.value].orEmpty()
                if (fallback.isNotEmpty()) items = reorderForRefresh(fallback, _tab.value)
            }
        }

        val key = target.key.orEmpty()
        if (items.isEmpty()) {
            _loading.value = false
            if (userPicked) {
                // 手选的源一屏内容都没取到：保持现在在播的这屏，明确提示一声。
                // siteKey 不跟着改，换源面板里还是原来那个源，再点一次还能重试。
                _switchFailed.tryEmit(start.name.orEmpty())
                return
            }
            // 哪里都找不到这个分类的内容：**不要**把正在播的这屏换掉，
            // 只在底栏上方挂一条可点的提示（「这个分类暂时没有内容，点左上角换片源」）。
            // 以前这里会清空列表 + 全屏黑底，点一下标签整页就黑了，像详情页被删掉一样。
            if (_items.value.isEmpty()) {
                _siteKey.value = key
                _empty.value = true
            } else {
                _tabEmpty.value = true
            }
            return
        }

        _tabEmpty.value = false
        // 换了源：各分类的缓存作废（首页缓存按源分开存，可以留着）。
        if (key != _siteKey.value) tabCache.clear()
        _siteKey.value = key

        tabCache[_tab.value] = items
        applyItems(items, resetIndex = refresh)
        _loading.value = false
        _empty.value = items.isEmpty()
        if (items.isNotEmpty()) selectIndex(_index.value)
    }

    /**
     * 「换一批」重排：把最近展示过的剧压到队尾，没看过的顶到前面；
     * 第一次（没有历史）或全都看过了，就用刷新次数把列表循环移位，起点也算换过了。
     * 只对本次列表排序，不改缓存里的原始数据。
     */
    private fun reorderForRefresh(list: List<FeedItem>, tab: Tab): List<FeedItem> {
        if (list.size <= 1) return list
        val shown = tabShownIds.getOrPut(tab) { LinkedHashSet() }
        val unseen = ArrayList<FeedItem>()
        val seen = ArrayList<FeedItem>()
        for (item in list) {
            val id = item.video.id.orEmpty().ifBlank { item.video.name.orEmpty() }
            if (id.isNotBlank() && shown.contains(id)) seen.add(item) else unseen.add(item)
        }
        val result = ArrayList<FeedItem>(unseen.size + seen.size).apply {
            addAll(unseen)
            addAll(seen)
        }
        // 没有「未看过」的内容（全都看过 / 首次），循环移位换个起点，别每次都从第一条开始。
        if (unseen.isEmpty()) {
            val n = (tabRotation[tab] ?: 0) + 1
            tabRotation[tab] = n
            val offset = n % result.size
            if (offset != 0) {
                val rotated = result.drop(offset) + result.take(offset)
                result.clear()
                result.addAll(rotated)
            }
        }
        // 记住这一批「即将展示」的条目，下次刷新它们会被压到队尾。
        result.take(REFRESH_MARK_COUNT).forEach { item ->
            val id = item.video.id.orEmpty().ifBlank { item.video.name.orEmpty() }
            if (id.isNotBlank()) shown.add(id)
        }
        while (shown.size > SHOWN_HISTORY_LIMIT && shown.isNotEmpty()) {
            shown.remove(shown.iterator().next())
        }
        persistRefreshState(tab, shown)
        return result
    }

    /** 把某个 tab 的「已看过」记录落到 MMKV，退出重进后还能接着看。 */
    private fun persistRefreshState(tab: Tab, shown: LinkedHashSet<String>) {
        runCatching {
            KV.put(
                "${KV_PREFIX_SHOWN}${tab.name}",
                ArrayList(shown.toList()),
            )
            tabRotation[tab]?.let { KV.put("${KV_PREFIX_ROTATION}${tab.name}", it) }
        }
    }

    /** 启动时恢复三个 tab 的记录；首次使用（没有存档）保持空，行为与之前一致。 */
    private fun restoreRefreshState() {
        for (tab in Tab.entries) {
            runCatching {
                val saved = KV.get<ArrayList<String>>("${KV_PREFIX_SHOWN}${tab.name}", ArrayList())
                if (saved.isNotEmpty()) {
                    tabShownIds[tab] = LinkedHashSet(saved.takeLast(SHOWN_HISTORY_LIMIT))
                }
                val rotation = KV.get("${KV_PREFIX_ROTATION}${tab.name}", 0)
                if (rotation > 0) tabRotation[tab] = rotation
            }
        }
    }

    /**
     * 在某个片源里按当前标签取内容，顺序是「能取到内容优先」：
     * 1. 推荐标签走 [recommendItems]：首页 + 多分类合并、按热度排序、类型打散；
     * 2. 漫剧/真人剧走站点分类：命中语义分类时直接用其结果，通用分类再按视频字段筛，
     *    **扩展 API（type 4）的源没有首页推荐**，分类是这类源的主力路径；
     * 3. 分类取不到再退回首页推荐（按 tab 过滤）。
     */
    private suspend fun buildItems(target: SourceBean, key: String): List<FeedItem> {
        val tab = _tab.value
        val siteName = target.name.orEmpty()
        if (tab == Tab.Recommend) return recommendItems(target, key, siteName)
        val cats = fetchCategories(key)
        if (cats.isNotEmpty()) {
            val preferred = pickCategory(cats, tab)
                ?: if (tab == Tab.Recommend) defaultCategory(cats) else null
            val tries = (listOfNotNull(preferred) + cats)
                .distinctBy { it.id }
                .take(MAX_CATEGORY_TRIES)
            for (cat in tries) {
                val catName = cat.name.orEmpty()
                // 真人剧 tab 跳过明确是漫剧的分类，避免把动画内容塞进真人剧。
                if (tab == Tab.Real && isAnimeName(catName)) continue
                val videos = fetchCategoryVideos(key, cat)
                if (videos.isEmpty()) continue
                val items = toItems(videos, key, siteName)
                // 分类名已明确表达 tab 语义（如「漫剧」「真人」）时，分类本身就是权威信号：
                // 直接用它的结果，不再按 isAnime 二次过滤。短剧列表接口普遍不返回 type/tag，
                // 二次过滤会把整批漫剧清空，正是「片源里有漫剧却提示没有内容」的根因。
                // 通用分类（全部/最新…）仍走视频字段过滤，避免真人剧混进漫剧。
                val hit = if (tab == Tab.Recommend || categoryMatchesTab(catName, tab)) {
                    items
                } else {
                    items.filter { matches(it.video, tab) }
                }
                if (hit.isNotEmpty()) return hit
            }
        }
        val home = homeItems(target, key)
        return if (tab == Tab.Recommend) home else home.filter { matches(it.video, tab) }
    }

    /** 推荐标签要一个「默认分类」：优先全部/短剧/推荐这类通用分类。 */
    private fun defaultCategory(cats: List<MovieSort.SortData>): MovieSort.SortData? =
        cats.firstOrNull { cat ->
            val name = cat.name.orEmpty()
            DefaultCategoryWords.any { name.contains(it) }
        } ?: cats.firstOrNull()

    private suspend fun homeItems(target: SourceBean, key: String): List<FeedItem> {
        homeCache[key]?.let { return it }
        val videos = runCatching { listLoader.homeRecList(target, null) }.getOrNull().orEmpty()
        val items = toItems(videos, key, target.name.orEmpty())
        if (items.isNotEmpty()) homeCache[key] = items
        return items
    }

    /**
     * 「推荐」tab：按热度排序的混合信息流。
     *
     * 旧实现只拿首页推荐（或单个默认分类）的第一页，既不排序也不混合类型，
     * 结果常常一水儿真人剧。现在合并「首页 + 漫剧/真人剧/热门等分类」的内容池，
     * 去重后按播放量降序，再做轻度类型打散，让漫剧和真人剧都能刷到。
     * 全程有总预算，挨个分类拉不会把界面卡在加载圈里。
     */
    private suspend fun recommendItems(
        target: SourceBean,
        key: String,
        siteName: String,
    ): List<FeedItem> = withTimeoutOrNull(RECOMMEND_TIMEOUT_MS) {
        val pool = LinkedHashMap<String, FeedItem>()
        fun merge(list: List<FeedItem>) {
            for (item in list) {
                val id = item.video.id.orEmpty()
                val k = key + "|" + id.ifBlank { item.video.name.orEmpty() }
                if (id.isNotBlank() && !pool.containsKey(k)) pool[k] = item
            }
        }
        // 首页推荐先入池（快），它自带热门/最新语义。
        merge(homeItems(target, key))
        // 再按分类补内容，优先漫剧、真人剧、热门/全部，保证类型混合。
        val cats = fetchCategories(key)
        if (cats.isNotEmpty()) {
            val preferred = LinkedHashSet<MovieSort.SortData>()
            cats.firstOrNull { isAnimeName(it.name.orEmpty()) }?.let { preferred.add(it) }
            cats.firstOrNull { it.name.orEmpty().contains("真人") }?.let { preferred.add(it) }
            cats.firstOrNull { cat ->
                HotCategoryWords.any { cat.name.orEmpty().contains(it) }
            }?.let { preferred.add(it) }
            val tries = (preferred + cats).distinctBy { it.id }.take(MAX_RECOMMEND_CATEGORIES)
            for (cat in tries) {
                val videos = fetchCategoryVideos(key, cat)
                if (videos.isEmpty()) continue
                merge(toItems(videos, key, siteName, cat.name.orEmpty()))
            }
        }
        if (pool.isEmpty()) return@withTimeoutOrNull emptyList()
        // 按热度降序；无热度数据时保持来源顺序（首页在前）。
        val ranked = pool.values.toList().sortedByDescending { hotScore(it.video) }
        // 轻度打散：连续多条同类型时插一条异类型，避免一水儿真人剧。
        interleaveTypes(ranked)
    } ?: emptyList()

    /** 播放量字符串 → 数字，支持「12345」「1.2万」「3.5w」「8亿」等写法。 */
    private fun hotScore(video: Movie.Video): Long {
        val raw = listOf(video.hits, video.hitsWeek).firstOrNull { !it.isNullOrBlank() } ?: return 0L
        val s = raw.trim().lowercase().replace(",", "")
        return try {
            when {
                s.endsWith("万") -> ((s.dropLast(1).toDoubleOrNull() ?: 0.0) * 10_000).toLong()
                s.endsWith("w") -> ((s.dropLast(1).toDoubleOrNull() ?: 0.0) * 10_000).toLong()
                s.endsWith("亿") -> ((s.dropLast(1).toDoubleOrNull() ?: 0.0) * 100_000_000).toLong()
                else -> s.toDoubleOrNull()?.toLong() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /** 内容属于漫剧还是真人剧：分类名优先，回退视频字段。 */
    private fun isAnimeItem(item: FeedItem): Boolean {
        val cat = item.categoryName
        if (cat.isNotBlank()) {
            if (isAnimeName(cat)) return true
            if (cat.contains("真人")) return false
        }
        return isAnime(item.video)
    }

    /**
     * 轻度类型打散：按热度降序的基础上，若连续 SAME_TYPE_LIMIT 条同类型，
     * 就从后面换一条异类型过来，避免推荐页刷出来全是真人剧（或全是漫剧）。
     * 只交换位置、不增删内容，排序主体仍是热度。
     */
    private fun interleaveTypes(ranked: List<FeedItem>): List<FeedItem> {
        if (ranked.size < SAME_TYPE_LIMIT + 1) return ranked
        val out = ranked.toMutableList()
        var i = 0
        while (i < out.size) {
            val type = isAnimeItem(out[i])
            var j = i + 1
            while (j < out.size && isAnimeItem(out[j]) == type) j++
            if (j - i >= SAME_TYPE_LIMIT && j < out.size) {
                // 连续同类型达到阈值：找后面第一条异类型换到当前连续段的中间。
                val swap = (j until out.size).firstOrNull { isAnimeItem(out[it]) != type }
                if (swap != null) {
                    val insertAt = i + SAME_TYPE_LIMIT - 1
                    val tmp = out[insertAt]
                    out[insertAt] = out[swap]
                    out[swap] = tmp
                }
            }
            i = maxOf(j, i + 1)
        }
        return out
    }

    /**
     * 当前片源没有这个分类的内容时，按站点顺序去其它片源找。
     * 先比首页推荐（快），首页拿不到的（扩展 API 源）再走它的分类；总时长有上限，
     * 不会因为挨个源试分类把界面卡在加载圈里。
     */
    private suspend fun searchOtherSources(start: SourceBean): Pair<SourceBean, List<FeedItem>>? {
        val tab = _tab.value
        if (tab == Tab.Recommend) return null
        val sites = _sites.value
        if (sites.size <= 1) return null
        val startIndex = sites.indexOfFirst { it.key == start.key }
        if (startIndex < 0) return null
        return withTimeoutOrNull(CROSS_SOURCE_TIMEOUT_MS) {
            for (offset in 1 until sites.size) {
                val site = sites[(startIndex + offset) % sites.size]
                val key = site.key.orEmpty()
                if (key.isEmpty()) continue
                val home = homeItems(site, key).filter { matches(it.video, tab) }
                if (home.isNotEmpty()) return@withTimeoutOrNull site to home
                // buildItems 内部已按 tab 处理过（命中语义分类时直接用分类结果，
                // 通用分类才按 isAnime 过滤）。这里不能再 filter 一次，
                // 否则会把刚挑出来的漫剧分类结果重新清空，跨源兜底失效。
                val fromCategory = buildItems(site, key)
                if (fromCategory.isNotEmpty()) return@withTimeoutOrNull site to fromCategory
            }
            null
        }
    }

    private fun toItems(
        videos: List<Movie.Video>,
        key: String,
        siteName: String,
        categoryName: String = "",
    ): List<FeedItem> =
        videos
            .filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }
            .distinctBy { it.name.orEmpty() + "|" + it.id }
            .map { FeedItem(it, key, siteName, categoryName) }

    private fun matches(video: Movie.Video, tab: Tab): Boolean = when (tab) {
        Tab.Recommend -> true
        Tab.Anime -> isAnime(video)
        Tab.Real -> !isAnime(video)
    }

    /**
     * 换标签/换源后重新落列表：空态、当前屏一起更新（不留下「有画面还说没内容」）。
     * [resetIndex] = true 时从第一条开始——「换一批」必须换个开头，否则即使列表重排了，
     * lastPlayedId 还会把位置对回原来那一条，看着就像没刷新。
     */
    private fun applyItems(list: List<FeedItem>, resetIndex: Boolean = false) {
        _items.value = list
        _empty.value = list.isEmpty()
        val keep = if (resetIndex) -1 else currentIndexIn(list)
        _index.value = if (keep >= 0) keep else 0
    }

    private fun currentIndexIn(list: List<FeedItem>): Int {
        val id = lastPlayedId.ifBlank { _currentInfo.value?.id.orEmpty() }
        if (id.isBlank()) return -1
        return list.indexOfFirst { it.video.id == id }
    }

    /** 按分类名挑：漫剧挑「漫 / 动画 / 动漫」，真人剧优先「真人」。 */
    private fun pickCategory(cats: List<MovieSort.SortData>, tab: Tab): MovieSort.SortData? {
        if (cats.isEmpty()) return null
        fun nameOf(cat: MovieSort.SortData) = cat.name.orEmpty()
        return when (tab) {
            Tab.Anime -> cats.firstOrNull { isAnimeName(nameOf(it)) }
            Tab.Real -> cats.firstOrNull { nameOf(it).contains("真人") }
            Tab.Recommend -> null
        }
    }

    private suspend fun fetchCategories(siteKey: String): List<MovieSort.SortData> {
        categoryCache[siteKey]?.let { return it }
        while (sortQueue.tryReceive().isSuccess) Unit
        sourceVm.getSort(siteKey, false)
        val sort = withTimeoutOrNull(SORT_TIMEOUT_MS) {
            while (true) {
                val data = sortQueue.receive()
                if (!data?.sourceKey.isNullOrEmpty() && data?.sourceKey != siteKey) continue
                return@withTimeoutOrNull data
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: return emptyList()
        val cats = sort.classes?.sortList
            ?.filter { !it.id.isNullOrBlank() }
            .orEmpty()
        if (cats.isNotEmpty()) categoryCache[siteKey] = cats
        return cats
    }

    private suspend fun fetchCategoryVideos(siteKey: String, cat: MovieSort.SortData): List<Movie.Video> {
        while (listQueue.tryReceive().isSuccess) Unit
        sourceVm.getList(siteKey, cat, 1)
        val data = withTimeoutOrNull(LIST_TIMEOUT_MS) {
            while (true) {
                val xml = listQueue.receive() ?: continue
                if (!xml.sourceKey.isNullOrEmpty() && xml.sourceKey != siteKey) continue
                return@withTimeoutOrNull xml
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: return emptyList()
        return data.movie?.videoList.orEmpty()
    }

    /**
     * 判断是不是漫剧（动画短剧）。
     * 先看类型/标签/备注里有没有明确的动画词，再单独看类型里有没有「漫」；
     * 不拿剧名里单个「漫」字判断，避免把「浪漫」这类真人剧误分进去。
     */
    private fun isAnime(video: Movie.Video): Boolean {
        val fields = listOf(video.type, video.tag, video.note, video.name, video.des, video.action)
        if (fields.any { text -> AnimeWords.any { !text.isNullOrBlank() && text.contains(it, ignoreCase = true) } }) {
            return true
        }
        val kind = video.type.orEmpty() + " " + video.tag.orEmpty()
        return kind.contains("漫")
    }

    /** 分类名是否明确是「漫剧」类（动画/动漫/二次元…）。分类名是比视频字段更权威的信号。 */
    private fun isAnimeName(name: String): Boolean =
        AnimeWords.any { name.contains(it, ignoreCase = true) }

    /**
     * 分类名是否明确表达了当前 tab 的语义。命中即视为权威信号：该分类拉到的内容
     * 直接归入对应 tab，不再按视频字段二次过滤。
     * 短剧列表接口普遍不返回 type/tag，若再过滤会把「漫剧」分类整批清空。
     * 通用分类（全部/最新）返回 false，仍走视频字段过滤，避免真人剧混进漫剧。
     */
    private fun categoryMatchesTab(name: String, tab: Tab): Boolean = when (tab) {
        Tab.Anime -> isAnimeName(name)
        Tab.Real -> name.contains("真人")
        Tab.Recommend -> false
    }

    // ---------------- 播放 ----------------

    fun selectIndex(index: Int) {
        val list = _items.value
        if (list.isEmpty()) return
        val safe = index.coerceIn(0, list.size - 1)
        _index.value = safe
        playItem(list[safe])
    }

    /** 上滑：换这个片源里的下一条内容（换片源只走左上角圆标）。 */
    fun next() {
        val list = _items.value
        if (_loading.value) return
        if (list.isEmpty()) {
            retry()
            return
        }
        selectIndex((_index.value + 1) % list.size)
    }

    /** 下滑：换这个片源里的上一条内容。 */
    fun previous() {
        val list = _items.value
        if (_loading.value) return
        if (list.isEmpty()) {
            retry()
            return
        }
        selectIndex((_index.value - 1 + list.size) % list.size)
    }

    /** 从别的播放页回来：用当前这一屏重新起一次播放（播放内核被别的页面接管过）。 */
    fun replayCurrent() {
        val info = _currentInfo.value ?: run {
            _items.value.getOrNull(_index.value)?.let { playItem(it) }
            return
        }
        requestPlay(info, info.sourceKey.orEmpty())
    }

    private fun playItem(item: FeedItem) {
        lastPlayedId = item.video.id.orEmpty()
        val cacheKey = item.siteKey + "|" + item.video.id
        detailCache[cacheKey]?.let { cached ->
            _currentInfo.value = cached
            requestPlay(cached, item.siteKey)
            return
        }
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _preparing.value = true
            val data = fetchDetail(item.siteKey, item.video.id.orEmpty())
            _preparing.value = false
            val detail = data?.movie?.videoList?.firstOrNull()
            val video = detail ?: item.video
            if (video.id.isNullOrEmpty()) video.id = item.video.id
            if (video.name.isNullOrEmpty()) video.name = item.video.name
            if (video.pic.isNullOrEmpty()) video.pic = item.video.pic
            if (video.des.isNullOrEmpty()) video.des = item.video.des
            var info = VodInfo.fromVideo(video).copy(
                sourceKey = item.siteKey,
                sourceName = item.siteName,
            )
            if (info.seriesMap.isNullOrEmpty()) {
                _message.tryEmit(R.string.drama_feed_play_failed)
                _currentInfo.value = info
                return@launch
            }
            val flag = info.playFlag ?: info.seriesMap?.keys?.firstOrNull()
            info = info.copy(playFlag = flag, playIndex = 0)
            if (detail == null) _message.tryEmit(R.string.drama_feed_detail_failed)
            detailCache[cacheKey] = info
            _currentInfo.value = info
            requestPlay(info, item.siteKey)
        }
    }

    private fun requestPlay(info: VodInfo, siteKey: String) {
        _playRequest.value = PlayRequest(info, siteKey, ++playSeq)
    }

    /** 选集：同一个源、同一个线路，只换集号。 */
    fun playEpisode(index: Int) {
        val info = _currentInfo.value ?: return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (index !in list.indices) return
        val updated = info.copy(playIndex = index)
        _currentInfo.value = updated
        val key = info.sourceKey.orEmpty() + "|" + info.id
        if (detailCache.containsKey(key)) detailCache[key] = updated
        requestPlay(updated, info.sourceKey.orEmpty())
    }

    private suspend fun fetchDetail(siteKey: String, id: String): AbsXml? {
        if (id.isEmpty()) return null
        // 丢掉上一条残留，避免把别人的详情当成这一次的。
        while (detailQueue.tryReceive().isSuccess) Unit
        sourceVm.getDetail(siteKey, id, false)
        return withTimeoutOrNull(DETAIL_TIMEOUT_MS) {
            while (true) {
                val data = detailQueue.receive()
                if (data?.movie?.videoList.isNullOrEmpty()) continue
                // 热流可能带着上一条请求的残留，源对不上就丢掉。
                if (!data.sourceKey.isNullOrEmpty() && data.sourceKey != siteKey) continue
                return@withTimeoutOrNull data
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    override fun onCleared() {
        detailJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val DETAIL_TIMEOUT_MS = 20_000L
        const val SORT_TIMEOUT_MS = 6_000L
        const val LIST_TIMEOUT_MS = 12_000L
        /** 跨源找内容的整体预算：再慢也得有个头，不能一直转圈。 */
        const val CROSS_SOURCE_TIMEOUT_MS = 15_000L
        /** 手选换源的整体预算：换不过去就明说，别无限等。 */
        const val SWITCH_TIMEOUT_MS = 20_000L
        /** 一个源最多试几个分类（每个分类都有超时，试太多会很慢）。 */
        const val MAX_CATEGORY_TRIES = 2

        /** 推荐 tab 合并内容池的总预算：首页 + 若干分类，不能一直转圈。 */
        const val RECOMMEND_TIMEOUT_MS = 18_000L
        /** 推荐 tab 最多合并几个分类（含漫剧/真人剧/热门）。 */
        const val MAX_RECOMMEND_CATEGORIES = 4
        /** 连续同类型达到这个数量就插一条异类型，避免刷出来全是一种剧。 */
        const val SAME_TYPE_LIMIT = 3

        /** 每次「换一批」记住多少条为已展示：下批把它们压到队尾，优先展示没看过的。 */
        const val REFRESH_MARK_COUNT = 6
        /** 已展示 id 记住的上限，超出后丢掉最早的，避免无限增长 / 永远轮不到。 */
        const val SHOWN_HISTORY_LIMIT = 120
        /** MMKV 存档前缀：短剧三个 tab 的「已看过」记录，跨进程保留。 */
        const val KV_PREFIX_SHOWN = "drama_feed_shown_"
        const val KV_PREFIX_ROTATION = "drama_feed_rotation_"

        /** 推荐 tab 视为热门的通用分类。 */
        val HotCategoryWords = listOf("热门", "熱門", "最热", "最熱", "排行榜", "推荐", "推薦")

        /** 推荐标签默认拿的分类：通用分类优先。 */
        val DefaultCategoryWords = listOf("全部", "短剧", "短劇", "推荐", "推薦", "最新", "热门", "熱門")

        /** 认作「漫剧」的词：只看类型/标签/备注/简介，避免剧名里的「浪漫」误伤。 */
        val AnimeWords = listOf(
            "动画", "動畫", "动漫", "動漫", "漫剧", "漫劇", "漫画", "漫畫",
            "二次元", "动态漫", "動態漫", "国漫", "國漫", "漫改", "3d漫", "3D漫",
        )
    }
}
