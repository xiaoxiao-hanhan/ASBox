package com.github.aura.asbox.io

import android.os.Environment
import com.github.aura.asbox.util.AppLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地片源的合并配置：片源页可以同时启用多个 .py/.js 片源，
 * 启用集合的 sites 会被合并写进一个固定配置文件，点播通道加载这一个文件，
 * 效果等同于一个多站源的订阅。开关变化后重建本文件并让配置层重载。
 *
 * 单个片源的打包配置里 api 是相对路径（"./spider_x.py"，相对它自己的目录），
 * 合并后文件换了目录，相对路径必须改写成绝对 clan 地址（JS 片源的打包本来
 * 就是绝对 clan 地址，播放器侧两种都认）。
 */
object LocalMergedConfig {

    private const val MERGED_FILE_NAME = "merged_local.json"

    fun mergedFile(): File = File(File(FileUtils.externalFilesPath(), "config"), MERGED_FILE_NAME)

    fun mergedUrl(): String? =
        toClanApi(mergedFile().absolutePath, Environment.getExternalStorageDirectory().absolutePath)

    /**
     * 按启用列表重建合并配置。返回合并配置的 clan 地址；
     * 没有任何可用站点时返回 null（并删掉旧的合并文件）。
     */
    fun rebuild(enabledUrls: List<String>): String? {
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath
        val sites = JSONArray()
        val seenKeys = HashSet<String>()
        for (url in enabledUrls) {
            val file = clanFile(url, storageRoot) ?: continue
            val text = try {
                if (file.isFile) file.readText(Charsets.UTF_8) else null
            } catch (th: Throwable) {
                AppLog.e("LocalMergedConfig", th)
                null
            } ?: continue
            val root = try {
                JSONObject(text)
            } catch (th: Throwable) {
                AppLog.e("LocalMergedConfig", th)
                null
            } ?: continue
            val arr = root.optJSONArray("sites") ?: continue
            for (i in 0 until arr.length()) {
                val site = arr.optJSONObject(i) ?: continue
                val key = site.optString("key")
                if (key.isNotEmpty() && !seenKeys.add(key)) continue
                val api = site.optString("api")
                if (api.startsWith("./")) {
                    val abs = File(file.parentFile, api.substring(2)).absolutePath
                    val clan = toClanApi(abs, storageRoot)
                    if (clan != null) site.put("api", clan)
                }
                sites.put(site)
            }
        }
        val target = mergedFile()
        if (sites.length() == 0) {
            if (target.exists()) target.delete()
            return null
        }
        val out = JSONObject()
        out.put("sites", sites)
        return try {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            target.writeText(out.toString(), Charsets.UTF_8)
            val url = mergedUrl()
            AppLog.i("echo-local-merged sites=" + sites.length() + " url=" + url)
            url
        } catch (th: Throwable) {
            AppLog.e("LocalMergedConfig", th)
            null
        }
    }

    private fun clanFile(url: String, storageRoot: String): File? {
        val clean = url.substringBefore(";").trim()
        if (!clean.startsWith("clan://localhost/")) return null
        val rel = clean.removePrefix("clan://localhost/").trimStart('/')
        if (rel.isEmpty()) return null
        return File(File(storageRoot), rel.replace('/', File.separatorChar))
    }
}
