package com.github.aura.asbox.ui.drama

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.github.aura.asbox.R
import com.github.aura.asbox.player.PageHost
import com.github.aura.asbox.player.PlaybackSession
import com.github.aura.asbox.ui.base.BaseActivity
import com.github.aura.asbox.ui.components.SheetHostScaffold
import com.github.aura.asbox.ui.page.jumpToDetail
import com.github.aura.asbox.ui.player.PlayContainer
import com.github.aura.asbox.ui.theme.AVBoxTheme
import com.github.aura.asbox.ui.theme.enableTransparentEdgeToEdge
import com.github.aura.asbox.util.DanmuSettingsState
import com.github.aura.asbox.util.PermissionHelper
import com.github.aura.asbox.util.SpiderStorageNotice

/**
 * 短剧信息流播放器：从主界面直播键长按菜单进入。
 *
 * 一屏一剧，上下滑只在这个片源的内容里换下一条（换片源点左上角圆标）；
 * 画面由本页自己的 [PlayContainer] 播放（与详情页同一套内核，
 * 线路切换、解析、弹幕全部复用）。
 * - 「点击观看完整剧」→ 跳原来的短剧小窗播放器（详情页短剧模式）；
 * - 「全屏观看」→ 跳原来的短剧横屏全屏播放器（详情页短剧模式 + 自动播放 + 全屏）。
 *
 * 本页是纯黑底但不做沉浸式：状态栏和导航栏保持显示（图标用白色），
 * 内容靠 statusBarsPadding / navigationBarsPadding 让开系统栏。
 */
class DramaFeedActivity : BaseActivity(), PageHost {

    private val vm: DramaFeedViewModel by lazy {
        ViewModelProvider(this)[DramaFeedViewModel::class.java]
    }

    var playContainer: PlayContainer? by mutableStateOf<PlayContainer?>(null)
        private set

    var episodesOpen by mutableStateOf(false)

    private var lastPlayedSeq = 0L
    /** 播放器视图挂上去之前先攒着，等真正上了窗口再 setData，免得首帧没有画面。 */
    private var playerViewAttached = false
    private var pendingPlay: DramaFeedViewModel.PlayRequest? = null
    /** 跳去全屏播放器时先把这边的播放交出去，回来再补一次。 */
    private var resumeOnReturn = false

    private val localSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) playContainer?.onLocalSubtitlePicked(uri)
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun init() {
        enableTransparentEdgeToEdge()
        // 整页黑底，但状态栏/导航栏要保持显示：图标用白色，内容再让开它们。
        showSystemBars()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                playContainer?.setPlayTitle(false)
                playContainer?.setExitingPreview(true)
                finish()
            }
        })
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                SheetHostScaffold {
                    DramaFeedScreen(
                        vm = vm,
                        container = playContainer,
                        episodesOpen = episodesOpen,
                        onCloseEpisodes = { episodesOpen = false },
                        onEnterFullscreen = ::openOldFullscreenPlayer,
                        onEnterDetail = ::openDetailPage,
                        onOpenDramaWindow = ::openDramaWindow,
                        onRequestPlay = ::play,
                        onPlayerAttached = ::onPlayerAttached,
                        onToggleDanmaku = ::toggleDanmaku,
                    )
                }
            }
        }
    }

    /** 本页不做沉浸式：基类 onResume 里的自动隐藏会顺手把状态栏吞掉，这里覆盖成空。 */
    override fun hideSysBar() = Unit

    /** 清掉基类可能残留的全屏 flag，并明确要求显示状态栏与导航栏（图标白色）。 */
    private fun showSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.show(WindowInsetsCompat.Type.statusBars())
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }

    /** 一屏内容就绪：交给播放内核。同一个容器不重建，只换内容。 */
    private fun play(request: DramaFeedViewModel.PlayRequest) {
        if (request.seq <= lastPlayedSeq) return
        lastPlayedSeq = request.seq
        ensurePlayContainer()
        if (!playerViewAttached) {
            pendingPlay = request
            return
        }
        playContainer?.setData(PlaybackSession(request.info, request.siteKey, false))
    }

    /** AndroidView 把播放器挂到窗口后回调：把攒着的第一次播放补上。 */
    private fun onPlayerAttached() {
        playerViewAttached = true
        val request = pendingPlay ?: return
        pendingPlay = null
        playContainer?.post {
            playContainer?.setData(PlaybackSession(request.info, request.siteKey, false))
        }
    }

    private fun ensurePlayContainer(): PlayContainer {
        val existing = playContainer
        if (existing != null) return existing
        return PlayContainer(this).also {
            it.pageHostRef = this
            it.setPreviewMode(true)
            playContainer = it
        }
    }

    /**
     * 「进入详情页」：打开**普通详情页**（封面海报 / 简介 / 选集 / 相关推荐），不自动起播。
     * 它和「点击观看完整剧」（跳短剧小窗播放器）是两个独立功能，各自走各自的入口。
     * 带上 playFlag / playIndex 只是让详情页的选集定位在当前这一集。
     */
    private fun openDetailPage() {
        val info = vm.currentInfo.value
        val item = vm.items.value.getOrNull(vm.index.value)
        val id = info?.id ?: item?.video?.id
        if (id.isNullOrEmpty()) return
        resumeOnReturn = true
        playContainer?.stopForContentSwitch()
        jumpToDetail(
            id = id,
            sourceKey = info?.sourceKey ?: item?.siteKey,
            title = info?.name ?: item?.video?.name,
            picture = info?.pic ?: item?.video?.pic,
            playFlag = info?.playFlag,
            playIndex = info?.playIndex ?: -1,
        )
    }

    /**
     * 「点击观看完整剧」：跳**原来的**短剧小窗播放器。
     * 也就是详情页的短剧模式（横屏片源摆横屏小窗、竖屏片源摆竖屏小窗），
     * 接着当前这一集播，不自动全屏。
     *
     * autoPlay 必须为 true：详情页只有收到开播信号才会创建播放容器，
     * 容器不存在时 embedded=false，落进去会变成带封面/简介的普通详情页。
     * 带上开播信号才是截图里那个「顶栏 + 画面 + 全屏观看 + 进度 + 选集」的小窗播放器。
     */
    private fun openDramaWindow() {
        val info = vm.currentInfo.value
        val items = vm.items.value
        val item = items.getOrNull(vm.index.value)
        val id = info?.id ?: item?.video?.id
        if (id.isNullOrEmpty()) return
        resumeOnReturn = true
        playContainer?.stopForContentSwitch()
        val portrait = playContainer?.let { it.hasVideoSize() && it.isPortraitVideo() } == true
        jumpToDetail(
            id = id,
            sourceKey = info?.sourceKey ?: item?.siteKey,
            title = info?.name ?: item?.video?.name,
            picture = info?.pic ?: item?.video?.pic,
            shortDrama = true,
            playFlag = info?.playFlag,
            playIndex = info?.playIndex ?: -1,
            portraitDrama = portrait,
            autoPlay = true,
        )
    }

    /**
     * 「全屏观看」：跳老的短剧横屏全屏播放器。
     * 用详情页的短剧模式 + 自动播放 + 直接全屏，落进去就是原来那个横屏全屏界面。
     */
    private fun openOldFullscreenPlayer() {
        val info = vm.currentInfo.value
        val items = vm.items.value
        val item = items.getOrNull(vm.index.value)
        val id = info?.id ?: item?.video?.id
        if (id.isNullOrEmpty()) return
        resumeOnReturn = true
        playContainer?.stopForContentSwitch()
        val portrait = playContainer?.let { it.hasVideoSize() && it.isPortraitVideo() } == true
        jumpToDetail(
            id = id,
            sourceKey = info?.sourceKey ?: item?.siteKey,
            title = info?.name ?: item?.video?.name,
            picture = info?.pic ?: item?.video?.pic,
            shortDrama = true,
            playFlag = info?.playFlag,
            playIndex = info?.playIndex ?: -1,
            portraitDrama = portrait,
            autoPlay = true,
            openFullscreen = true,
        )
    }

    fun toggleDanmaku(): Boolean {
        val container = playContainer
        val controller = container?.danmuLoadController
        if (controller == null) {
            Toast.makeText(this, getString(R.string.drama_feed_danmaku_unavailable), Toast.LENGTH_SHORT).show()
            return DanmuSettingsState.open
        }
        val next = !DanmuSettingsState.open
        DanmuSettingsState.open = next
        controller.applySettings(true)
        return next
    }

    override fun onResume() {
        super.onResume()
        showSystemBars()
        playContainer?.hostResume()
        if (resumeOnReturn) {
            resumeOnReturn = false
            // 从别的播放页回来：播放内核刚被那边用过，用当前这一屏重新起一次。
            vm.replayCurrent()
        }
    }

    override fun onPause() {
        playContainer?.hostPause()
        super.onPause()
    }

    override fun onDestroy() {
        playContainer?.hostDestroy()
        playContainer = null
        super.onDestroy()
    }

    // ---------------- PageHost ----------------

    override fun context(): Context = this

    override fun isPageAlive(): Boolean = !isFinishing && !isDestroyed

    override fun runOnUi(action: Runnable) {
        if (isPageAlive()) runOnUiThread(action)
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun launchLocalSubtitlePicker() {
        try {
            SpiderStorageNotice.suppress()
            localSubtitlePicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_file_picker_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun requestNotificationPermission() {
        PermissionHelper.requestNotificationIfNeeded(this)
    }

    override fun showEpisodeSheet() {
        episodesOpen = true
    }

    override fun onPlaybackLinesExhausted(): Boolean = false

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DramaFeedActivity::class.java))
        }
    }
}
