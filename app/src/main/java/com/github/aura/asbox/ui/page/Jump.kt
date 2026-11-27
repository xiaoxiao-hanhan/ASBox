package com.github.aura.asbox.ui.page

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.aura.asbox.ui.DetailActivity
import com.github.aura.asbox.ui.SearchActivity

fun Context.jumpToDetail(
    id: String?,
    sourceKey: String?,
    title: String?,
    picture: String?,
    collect: Boolean = false,
    shortDrama: Boolean = false,
    playFlag: String? = null,
    playIndex: Int = -1,
    portraitDrama: Boolean = false,
    /** 进去就开播（不从详情页的播放按钮起步）。 */
    autoPlay: Boolean = false,
    /** 进去直接全屏（老的短剧横屏全屏播放器）。 */
    openFullscreen: Boolean = false,
) {
    val bundle = Bundle()
    bundle.putString("id", id.orEmpty())
    bundle.putString("sourceKey", sourceKey.orEmpty())
    bundle.putString("title", title)
    bundle.putString("picture", picture)
    bundle.putBoolean("collect", collect)
    bundle.putBoolean("shortDrama", shortDrama)
    // 从短剧信息流跳过来：接着播同一集，并告诉详情页这是竖屏片源（首帧就摆竖屏小窗）。
    if (!playFlag.isNullOrEmpty()) bundle.putString("playFlag", playFlag)
    if (playIndex >= 0) bundle.putInt("playIndex", playIndex)
    if (portraitDrama) bundle.putBoolean("portraitDrama", true)
    if (autoPlay) bundle.putBoolean("autoPlay", true)
    if (openFullscreen) bundle.putBoolean("openFullscreen", true)
    startActivity(Intent(this, DetailActivity::class.java).putExtras(bundle))
}

fun Context.jumpToSearch(title: String) {
    jumpToSearch(title, null)
}

/**
 * 带站点限定的搜索：传了 [siteKey] 就只搜这一个源（短剧播放器右上角搜索用）。
 */
fun Context.jumpToSearch(title: String, siteKey: String?) {
    val bundle = Bundle()
    bundle.putString("title", title)
    if (!siteKey.isNullOrEmpty()) bundle.putString("site_key", siteKey)
    startActivity(Intent(this, SearchActivity::class.java).putExtras(bundle))
}
