package com.github.aura.asbox.player.controller

import com.github.aura.asbox.bean.ParseBean

import org.json.JSONObject

import java.util.HashMap

interface VodControlListener {
    fun playNext(rmProgress: Boolean)

    fun playPre()

    fun prepared()

    fun changeParse(pb: ParseBean)

    fun updatePlayerCfg(
        suppressAutoSwitch: Boolean = false,
        suppressDecodeFallback: Boolean = false,
        mutate: (JSONObject) -> Unit,
    )

    fun replay(replay: Boolean)

    fun onNearEndProgress(positionMs: Long, durationMs: Long)

    fun selectSubtitle()

    fun closeSubtitles()

    fun selectAudioTrack()

    fun selectVideoTrack()

    fun showDanmuSetting()

    fun showEpisodes()

    fun toggleDanmu(): Boolean

    fun searchDanmuUi(longClick: Boolean)

    fun startPlayUrl(url: String, headers: HashMap<String, String>?)

    fun onM3u8ProxyUrl(proxyUrl: String, sourceUrl: String)

    fun clickCast()

    /**
     * 当前是否为小窗（详情页嵌入）播放。
     * 小窗和全屏的画面比例各存各的：在任何一边调整都不会改动另一边。
     */
    fun isWindowMode(): Boolean

    /** 当前播放方式（小窗/全屏）正在使用的画面比例，用于面板回显。 */
    fun currentScaleIndex(): Int

    /** 在小窗里改画面比例：只写小窗自己那份，不影响全屏的设置。 */
    fun applyWindowScale(index: Int)
}
