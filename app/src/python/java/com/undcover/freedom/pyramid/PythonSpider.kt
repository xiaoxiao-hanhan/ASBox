package com.undcover.freedom.pyramid

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.widget.Toast

import androidx.media3.common.util.UriUtil

import com.chaquo.python.PyObject
import com.github.catvod.crawler.Spider
import com.github.aura.asbox.io.FileUtils
import com.github.aura.asbox.util.AppLog
import com.github.aura.asbox.util.LocalAddress
import com.github.aura.asbox.util.PyDiag
import com.github.aura.asbox.util.RegexUtils

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.HashMap
import java.util.Objects

class PythonSpider : Spider {
    private var app: PyObject? = null
    private var pySpider: PyObject? = null
    private var loadSuccess = false
    private var cachePath = ""
    private var name = ""

    constructor() : this(PythonLoader.getInstance().getCachePath())

    constructor(cache: String) : this("", cache)

    constructor(name: String, cache: String) : super() {
        this.cachePath = cache
        this.name = name
    }

    override fun init(context: Context?) {
        requireNotNull(app).callAttr("init", pySpider)
    }

    override fun init(context: Context?, url: String?) {
        init(context, url, "")
    }

    // 诊断：把最近一次 Python 异常记下来，供首页失败态展示/落盘。
    @Volatile
    var lastError: String = ""

    fun init(context: Context?, url: String?, extend: String?) {
        val pyApp = requireNotNull(PythonLoader.getInstance().pyApp)
        app = pyApp
        AppLog.e("echo--py-init 开始 name=$name url=$url")
        try {
            // 本机片源用 App 网络栈/磁盘直读拿到文件，绕开 Python requests；远端源仍走原下载逻辑。
            val pluginUrl = resolvePluginUrl(url) ?: url
            AppLog.e("echo--py-init pluginUrl=$pluginUrl")
            val retValue = pyApp.callAttr("downloadPlugin", cachePath, pluginUrl)
            val uri = Uri.parse(requireNotNull(url))
            var extInfo = extend
            if (extInfo == null || extInfo.length == 0) extInfo = uri.getQueryParameter("extend")
            if (null == extInfo) extInfo = ""
            val path = retValue.toString()
            Log.i("PyLoader", "echo-init path: " + path)
            val file = File(path)
            if (file.exists()) {
                val spider = pyApp.callAttr("loadFromDisk", path)
                pySpider = spider
                try {
                    spider.put("siteKey", name)
                } catch (ignored: Exception) {
                    AppLog.d("PyLoader", "set siteKey failed")
                }

                val poList = pyApp.callAttr("getDependence", spider).asList()
                for (po in poList) {
                    val api = po.toString()
                    Log.i("PyLoader", "echo-init api: " + api)
                    var depUrl = PythonLoader.getInstance().getUrlByApi(api)
                    if (depUrl.isEmpty()) depUrl = resolveDependenceUrl(url, api)
                    if (!depUrl.isEmpty()) {
                        Log.i("PyLoader", "echo-init depUrl: " + depUrl)
                        val depPluginUrl = resolvePluginUrl(depUrl) ?: depUrl
                        val tmpPath = pyApp.callAttr("downloadPlugin", cachePath, depPluginUrl).toString()
                        if (!File(tmpPath).exists()) {
                            lastError = "依赖文件缺失: $api"
                            PyDiag.set(name, lastError)
                            AppLog.e("echo--py-init 依赖文件缺失 name=$name api=$api")
                            PyToast.showCancelableToast(api + "加载失败!")
                            return
                        } else {
                            pyApp.callAttr("registerPluginAlias", api, tmpPath)
                            PyLog.d(api + ": 加载插件依赖成功！")
                        }
                    }
                }
                pyApp.callAttr("init", spider, extInfo)
                loadSuccess = true
                PyDiag.set(name, "")
                AppLog.e("echo--py-init 成功 name=$name path=$path")
                PyLog.d(name + ": 下載插件成功！")
            } else {
                lastError = "插件文件不存在: $path (原始url=$url)"
                PyDiag.set(name, lastError)
                AppLog.e("echo--py-init 插件文件不存在 name=$name path=$path url=$url")
                PyToast.showCancelableToast(name + "下载插件失败")
            }
        } catch (t: Throwable) {
            lastError = (t.message ?: t.toString()).take(400)
            PyDiag.set(name, "init异常: $lastError")
            AppLog.e("echo--py-init", "init异常 name=$name url=$url err=$lastError", t)
            AppLog.e("echo--py-init init异常 name=$name url=$url err=$lastError")
            PyToast.showCancelableToast("py源加载异常: $lastError", Toast.LENGTH_LONG)
        }
    }

    /**
     * 把本机片源地址解析成一个「保证存在的 file:// 路径」，绕开 Python 的 requests 下载
     * （requests 在 Chaquopy 真机环境可能连不上 127.0.0.1，导致「下载插件失败」）。
     * 支持格式：http://127.0.0.1:<port>/file/<rel> 与 clan://localhost/<rel>。
     * 顺序：① 直接读磁盘；② 磁盘读不到就用 App 网络栈(同 JS 源)取内容写入缓存。
     * 返回 null 表示不是本机地址或取不到内容，由调用方回退到原 url 走老逻辑。
     */
    private fun resolvePluginUrl(url: String?): String? {
        if (url == null) return null
        val rel = when {
            url.startsWith("http://127.0.0.1:") && url.contains("/file/") ->
                url.substringAfter("/file/").substringBefore('?')
            url.startsWith("clan://localhost/") ->
                url.removePrefix("clan://localhost/").substringBefore('?')
            else -> return null
        }.trimStart('/')
        if (rel.isEmpty() || rel.contains("..")) return null

        // ① 磁盘直读
        val disk = File(Environment.getExternalStorageDirectory(), rel)
        if (disk.isFile) {
            AppLog.e("echo--py-init 磁盘直读命中 rel=$rel")
            return "file://" + disk.absolutePath
        }

        // ② 磁盘读不到：用 App 网络栈取内容写入缓存（与 JS 源同一机制，已验证可用）
        val httpUrl = if (url.startsWith("http")) url else {
            val base = LocalAddress.get()
            if (base.isEmpty()) return null
            base + "file/" + rel
        }
        val name = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        if (name.isEmpty()) return null
        val content = try {
            FileUtils.get(httpUrl)
        } catch (t: Throwable) {
            AppLog.e("echo--py-init 本机取内容异常 url=$httpUrl err=${t.message}")
            ""
        }
        if (content.isEmpty()) {
            AppLog.e("echo--py-init 本机取内容失败 url=$httpUrl rel=$rel")
            return null
        }
        val target = File(cachePath, name + ".py")
        return try {
            target.parentFile?.let { if (!it.exists()) it.mkdirs() }
            target.writeText(content, Charsets.UTF_8)
            AppLog.e("echo--py-init 本机片源已缓存 target=${target.absolutePath} len=${content.length}")
            "file://" + target.absolutePath
        } catch (t: Throwable) {
            AppLog.e("echo--py-init 写缓存失败 target=${target.absolutePath} err=${t.message}")
            null
        }
    }

    private fun resolveDependenceUrl(baseUrl: String?, api: String?): String {
        if (api == null || api.isEmpty()) return ""
        val dep = if (api.endsWith(".py")) api else api + ".py"
        return UriUtil.resolve(baseUrl, dep)
    }

    fun getName(): String {
        return if (name.isEmpty()) {
            val po = requireNotNull(app).callAttr("getName", pySpider)
            po.toString()
        } else {
            name
        }
    }

    fun isLoadSuccess(): Boolean {
        return loadSuccess && pySpider != null
    }

    fun map2json(extend: HashMap<String, String>?): JSONObject {
        val jo = JSONObject()
        try {
            if (extend != null) {
                for (key in extend.keys) {
                    jo.put(key, extend[key])
                }
            }
        } catch (e: JSONException) {
            AppLog.e("PythonSpider", e)
        }
        return jo
    }

    fun map2json(extend: Map<*, *>?): JSONObject {
        val jo = JSONObject()
        try {
            if (extend != null) {
                for (key in extend.keys) {
                    jo.put(key.toString(), extend[key])
                }
            }
        } catch (e: JSONException) {
            AppLog.e("PythonSpider", e)
        }
        return jo
    }

    fun list2json(array: List<String>?): JSONArray {
        val ja = JSONArray()
        if (array != null) {
            for (str in array) {
                ja.put(str)
            }
        }
        return ja
    }

    fun paramLog(vararg obj: Any?): String {
        val sb = StringBuilder()
        sb.append("request params:[")
        for (o in obj) {
            sb.append(o).append("-")
        }
        sb.append("]")
        return sb.toString()
    }

    override fun proxyLocal(params: Map<String, String>?): Array<Any?>? {
        val proxyResult = requireNotNull(app).callAttr("localProxy", pySpider, map2json(params).toString())
        if (proxyResult == null) return null
        val list = proxyResult.asList()
        if (list == null || list.size < 3) return null
        val base64 = list.size > 4 && list[4].toInt() == 1
        val headerAvailable = list.size > 3 && list[3] != null
        val result = arrayOfNulls<Any?>(4)
        result[0] = list[0].toInt()
        result[1] = list[1].toString()
        result[2] = getStream(list[2], base64)
        result[3] = if (headerAvailable) getHeader(list[3]) else null
        return result
    }

    private fun getHeader(headerObj: PyObject?): Map<String, String>? {
        if (headerObj == null) {
            return null
        }
        val headerMap = HashMap<String, String>()
        for (key in headerObj.asMap().keys) {
            headerMap[key.toString()] = Objects.requireNonNull(headerObj.asMap()[key]).toString()
        }
        return headerMap
    }

    private fun getStream(o: PyObject?, base64: Boolean): ByteArrayInputStream {
        if (o == null) return ByteArrayInputStream(ByteArray(0))
        val typeStr = o.type().toString()
        if (typeStr.contains("bytes")) return ByteArrayInputStream(o.toJava(ByteArray::class.java))
        var content = o.toString()
        if (base64 && content.contains("base64,")) {
            content = RegexUtils.getPattern("base64,").split(content)[1]
        }
        return ByteArrayInputStream(if (base64) decode(content) else content.toByteArray(Charset.defaultCharset()))
    }

    fun replaceLocalUrl(content: String): String {
        return content.replace("http://127.0.0.1:UndCover/proxy", PythonLoader.getInstance().localProxyUrl())
    }

    override fun homeContent(filter: Boolean): String {
        if (pySpider == null) {
            val diag = if (lastError.isEmpty()) "pySpider未初始化(init失败)" else lastError
            PyDiag.set(name, "pySpider未加载: $diag")
            AppLog.e("echo--py-home pySpider==null name=$name diag=$diag")
            return diagClass("pySpider未加载: $diag")
        }
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("homeContent-$name", paramLog(filter))
        return try {
            val po = requireNotNull(app).callAttr("homeContent", pySpider, filter)
            val rsp = po.toString()
            PyLog.nw("homeContent-$name", rsp)
            AppLog.e("echo--py-home 返回 name=$name len=${rsp.length} head=${rsp.take(200)}")
            if (hasEmptyClass(rsp)) {
                PyDiag.set(name, "返回空数据: ${rsp.take(200)}")
                AppLog.e("echo--py-home 空class name=$name raw=$rsp")
                diagClass("homeContent返回空数据: ${rsp.take(160)}")
            } else {
                PyDiag.set(name, "")
                rsp
            }
        } catch (t: Throwable) {
            lastError = (t.message ?: t.toString()).take(400)
            PyDiag.set(name, "homeContent异常: $lastError")
            AppLog.e("echo--py-home", "homeContent异常 name=$name err=$lastError", t)
            AppLog.e("echo--py-home homeContent异常 name=$name err=$lastError")
            PyToast.showCancelableToast("首页加载异常: $lastError", Toast.LENGTH_LONG)
            diagClass("homeContent异常: $lastError")
        }
    }

    private fun diagClass(msg: String): String {
        return try {
            val jo = JSONObject()
            val arr = JSONArray()
            val cls = JSONObject()
            cls.put("type_id", "__diag__")
            cls.put("type_name", "诊断: $msg")
            arr.put(cls)
            jo.put("class", arr)
            jo.put("list", JSONArray())
            jo.toString()
        } catch (e: Exception) {
            "{\"class\":[]}"
        }
    }

    private fun hasEmptyClass(rsp: String): Boolean {
        return try {
            val jo = JSONObject(rsp)
            val arr = jo.optJSONArray("class")
            arr == null || arr.length() == 0
        } catch (e: Exception) {
            true
        }
    }

    override fun homeVideoContent(): String {
        PyLog.nw("homeVideoContent-$name", "")
        val po = requireNotNull(app).callAttr("homeVideoContent", pySpider)
        val rsp = po.toString()
        PyLog.nw("homeVideoContent-$name", rsp)
        return rsp
    }

    override fun categoryContent(tid: String?, pg: String, filter: Boolean, extend: HashMap<String, String>?): String {
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("categoryContent-$name", paramLog(tid, pg, filter, map2json(extend).toString()))
        val po = requireNotNull(app).callAttr("categoryContent", pySpider, tid, pg, filter, map2json(extend).toString())
        val rsp = po.toString()
        PyLog.nw("categoryContent-$name", rsp)
        return rsp
    }

    override fun detailContent(ids: List<String>?): String {
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("detailContent-$name", paramLog(list2json(ids).toString()))
        val po = requireNotNull(app).callAttr("detailContent", pySpider, list2json(ids).toString())
        val rsp = po.toString()
        PyLog.nw("detailContent-$name", rsp)
        return rsp
    }

    override fun searchContent(key: String?, quick: Boolean): String {
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("searchContent-$name", paramLog(key, quick))
        val po = requireNotNull(app).callAttr("searchContent", pySpider, key, quick)
        val rsp = po.toString()
        PyLog.nw("searchContent-$name", rsp)
        return rsp
    }

    override fun playerContent(flag: String?, id: String, vipFlags: List<String>?): String {
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("playerContent-$name", paramLog(flag, id, list2json(vipFlags).toString()))
        val po = requireNotNull(app).callAttr("playerContent", pySpider, flag, id, list2json(vipFlags).toString())
        val rsp = replaceLocalUrl(po.toString())
        PyLog.nw("playerContent-$name", rsp)
        return rsp
    }

    override fun liveContent(url: String?): String {
        PyLog.nw("liveContent-$name", "")
        val po = requireNotNull(app).callAttr("liveContent", pySpider, url)
        val rsp = po.toString()
        PyLog.nw("liveContent-$name", rsp)
        return rsp
    }

    override fun isVideoFormat(url: String?): Boolean {
        return false
    }

    override fun manualVideoCheck(): Boolean {
        return false
    }

    override fun destroy() {
        try {
            val pyApp = app
            if (pyApp != null && pySpider != null) pyApp.callAttr("destroy", pySpider)
        } catch (ignored: Exception) {
            AppLog.d("PyLoader", "python spider destroy failed")
        }
    }

    companion object {
        @JvmStatic
        fun decode(s: String): ByteArray {
            return decode(s, Base64.DEFAULT or Base64.NO_WRAP)
        }

        @JvmStatic
        fun decode(s: String, flags: Int): ByteArray {
            return Base64.decode(s, flags)
        }
    }
}
