package com.github.aura.asbox.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Python 片源加载诊断信息的中转站。由 PythonSpider(在 python source set 中)写入，
 * 供 HomeViewModel(主 source set)读取并把真实错误显示到"加载失败"界面。
 * 以 sourceKey 为键，成功时写空串清除。
 */
object PyDiag {

    private val map = ConcurrentHashMap<String, String>()

    @JvmStatic
    fun set(key: String?, msg: String?) {
        if (key.isNullOrEmpty()) return
        if (msg.isNullOrEmpty()) {
            map.remove(key)
        } else {
            map[key] = msg
        }
    }

    @JvmStatic
    fun get(key: String?): String = if (key == null) "" else (map[key] ?: "")

    @JvmStatic
    fun clear() {
        map.clear()
    }
}
