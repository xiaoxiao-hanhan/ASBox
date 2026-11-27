package com.github.aura.asbox.util

import java.util.ArrayList

object HistoryHelper {
    private val hisNumArray = arrayOf(30, 50, 100)
    private const val API_LINE_SPLIT = "\t"

    @JvmStatic
    fun getHisNum(index: Int): Int {
        var value: Int
        if (index >= 0 && index < hisNumArray.size) {
            value = hisNumArray[index]
        } else {
            value = hisNumArray[0]
        }
        return value
    }

    @JvmStatic
    fun isIncognito(): Boolean {
        return KV.get(KVKeys.INCOGNITO, false)
    }

    @JvmStatic
    fun setSearchHistory(title: String) {
        if (isIncognito()) return
        val history = KV.get(KVKeys.SEARCH_HISTORY, ArrayList<String>())
        history.remove(title)
        history.add(0, title)
        if (history.size > 20) {
            history.removeAt(history.size - 1)
        }
        KV.put(KVKeys.SEARCH_HISTORY, history)
    }

    @JvmStatic
    fun clearSearchHistory() {
        KV.put(KVKeys.SEARCH_HISTORY, ArrayList<String>())
    }

    @JvmStatic
    fun removeSearchHistory(title: String) {
        val history = KV.get(KVKeys.SEARCH_HISTORY, ArrayList<String>())
        history.remove(title)
        KV.put(KVKeys.SEARCH_HISTORY, history)
    }

    @JvmStatic
    fun setLiveApiHistory(value: String) {
        val history = KV.get(KVKeys.LIVE_API_HISTORY, ArrayList<String>())
        if (!history.contains(value)) {
            history.add(0, value)
        }
        if (history.size > 30) {
            history.removeAt(30)
        }
        KV.put(KVKeys.LIVE_API_HISTORY, history)
    }

    @JvmStatic
    fun setApiHistory(value: String) {
        val history = KV.get(KVKeys.API_HISTORY, ArrayList<String>())
        if (!history.contains(value)) {
            history.add(0, value)
        }
        if (history.size > 30) {
            history.removeAt(30)
        }
        KV.put(KVKeys.API_HISTORY, history)
    }

    @JvmStatic
    fun buildApiLine(name: String?, url: String?): String {
        var lineName = if (name == null) "" else name.trim { it <= ' ' }
        val lineUrl = if (url == null) "" else url.trim { it <= ' ' }
        if (lineName.isEmpty()) {
            lineName = lineUrl
        }
        return lineName + API_LINE_SPLIT + lineUrl
    }

    @JvmStatic
    fun apiLineName(value: String?): String {
        if (value == null) return ""
        val splitIndex = value.indexOf(API_LINE_SPLIT)
        val name = if (splitIndex >= 0) value.substring(0, splitIndex) else value
        return name.trim { it <= ' ' }
    }

    @JvmStatic
    fun apiLineUrl(value: String?): String {
        if (value == null) return ""
        val splitIndex = value.indexOf(API_LINE_SPLIT)
        val url = if (splitIndex >= 0) value.substring(splitIndex + API_LINE_SPLIT.length) else value
        return url.trim { it <= ' ' }
    }

    @JvmStatic
    fun isApiLineUrl(url: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        val trimUrl = url.trim { it <= ' ' }
        val apiLines = KV.get(KVKeys.API_LINE_LIST, ArrayList<String>())
        for (apiLine in apiLines) {
            if (trimUrl == apiLineUrl(apiLine)) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun isApiLineSource(url: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        val source = KV.get(KVKeys.API_LINE_SOURCE, "")
        return url.trim { it <= ' ' } == source
    }

    @JvmStatic
    fun isApiLineHistory(url: String?): Boolean {
        return isApiLineSource(url) || isApiLineUrl(url)
    }

    @JvmStatic
    fun clearApiLineList() {
        KV.put(KVKeys.API_LINE_LIST, ArrayList<String>())
        KV.put(KVKeys.API_LINE_SOURCE, "")
        ApiLineSignal.notifyChanged()
    }

    @JvmStatic
    fun getApiLines(): ArrayList<String> {
        return KV.get(KVKeys.API_LINE_LIST, ArrayList<String>())
    }

    @JvmStatic
    fun isLiveApiLineUrl(url: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        val trimUrl = url.trim { it <= ' ' }
        val apiLines = KV.get(KVKeys.LIVE_API_LINE_LIST, ArrayList<String>())
        for (apiLine in apiLines) {
            if (trimUrl == apiLineUrl(apiLine)) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun isLiveApiLineSource(url: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        return url.trim { it <= ' ' } == KV.get(KVKeys.LIVE_API_LINE_SOURCE, "")
    }

    @JvmStatic
    fun isLiveApiLineHistory(url: String?): Boolean {
        return isLiveApiLineSource(url) || isLiveApiLineUrl(url)
    }

    @JvmStatic
    fun liveApiLines(): ArrayList<String> {
        return KV.get(KVKeys.LIVE_API_LINE_LIST, ArrayList<String>())
    }

    @JvmStatic
    fun clearLiveApiLineList() {
        KV.put(KVKeys.LIVE_API_LINE_LIST, ArrayList<String>())
        KV.put(KVKeys.LIVE_API_LINE_SOURCE, "")
        ApiLineSignal.notifyChanged()
    }

    @JvmStatic
    fun isApiLineSourceOf(url: String?, activeUrl: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        if (!isApiLineUrl(activeUrl)) return false
        return url.trim { it <= ' ' } == KV.get(KVKeys.API_LINE_SOURCE, "")
    }

    @JvmStatic
    fun isLiveApiLineSourceOf(url: String?, activeUrl: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        if (!isLiveApiLineUrl(activeUrl)) return false
        return url.trim { it <= ' ' } == KV.get(KVKeys.LIVE_API_LINE_SOURCE, "")
    }

    /**
     * 记住「这个源是多仓配置」（配置里带 urls 线路列表）。
     * 单仓配置重新加载成功时用 clearMultiRepoSource 抹掉标记，避免误标。
     */
    @JvmStatic
    fun markMultiRepoSource(url: String?, live: Boolean) {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return
        val key = if (live) KVKeys.MULTI_REPO_LIVE_SOURCES else KVKeys.MULTI_REPO_SOURCES
        val list = KV.get(key, ArrayList<String>())
        if (list.contains(url)) return
        list.add(url)
        KV.put(key, list)
    }

    @JvmStatic
    fun clearMultiRepoSource(url: String?, live: Boolean) {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return
        val key = if (live) KVKeys.MULTI_REPO_LIVE_SOURCES else KVKeys.MULTI_REPO_SOURCES
        val list = KV.get(key, ArrayList<String>())
        if (!list.contains(url)) return
        list.remove(url)
        KV.put(key, list)
    }

    @JvmStatic
    fun isMultiRepoSource(url: String?, live: Boolean): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        val key = if (live) KVKeys.MULTI_REPO_LIVE_SOURCES else KVKeys.MULTI_REPO_SOURCES
        return KV.get(key, ArrayList<String>()).contains(url.trim { it <= ' ' })
    }

    /**
     * 多仓源当前正在使用的线路名。
     * 只有「这批线路确实来自这个源」时才知道（否则那批线路属于别的源，不能张冠李戴）。
     */
    @JvmStatic
    fun currentApiLineName(sourceUrl: String?, activeUrl: String?, live: Boolean): String {
        if (sourceUrl == null || sourceUrl.trim { it <= ' ' }.isEmpty()) return ""
        val sourceKey = if (live) KVKeys.LIVE_API_LINE_SOURCE else KVKeys.API_LINE_SOURCE
        if (KV.get(sourceKey, "") != sourceUrl.trim { it <= ' ' }) return ""
        val lines = if (live) liveApiLines() else getApiLines()
        if (lines.isEmpty()) return ""
        val matched = lines.firstOrNull { apiLineUrl(it) == activeUrl } ?: lines.first()
        return apiLineName(matched)
    }
}
