package com.github.aura.asbox.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.setContent
import com.github.aura.asbox.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.base.BaseActivity
import com.github.aura.asbox.bean.VodInfo
import com.github.aura.asbox.ui.components.SheetHostScaffold
import com.github.aura.asbox.player.PageHost
import com.github.aura.asbox.player.PlaybackController
import com.github.aura.asbox.player.PlaybackService
import com.github.aura.asbox.player.engine.MediaSources
import com.github.aura.asbox.player.core.PlayState
import com.github.aura.asbox.ui.player.PlayContainer
import com.github.aura.asbox.ui.settings.UiSettingsState
import com.github.aura.asbox.ui.theme.AVBoxTheme
import com.github.aura.asbox.ui.theme.AppThemeState
import com.github.aura.asbox.util.AppLog
import com.github.aura.asbox.util.PermissionHelper
import com.github.aura.asbox.util.SpiderStorageNotice
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L

class DetailActivity : BaseActivity(), PageHost {

    private val vm: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }

    var playContainer: PlayContainer? by mutableStateOf<PlayContainer?>(null)
        private set
    private var appliedFullscreen = false
    private var pendingEpisodeSync = false
    private var videoSizeWatchArmed = false

    private val localSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) playContainer?.onLocalSubtitlePicked(uri)
    }

    override fun launchLocalSubtitlePicker() {
        try {
            SpiderStorageNotice.suppress()
            localSubtitlePicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_file_picker_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    // 竖屏全屏：状态栏和底部导航键透明悬浮在画面上，不再沉浸隐藏。
    // 横屏全屏（首页点播/短剧横屏）：顶部状态栏不显示，用沉浸式隐藏。
    override fun hideSysBar() {
        if (isLandscapeFullscreen()) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    /** 当前是否横屏全屏（横屏全屏才隐藏状态栏；竖屏全屏保持悬浮）。 */
    private fun isLandscapeFullscreen(): Boolean {
        if (!vm.fullScreen.value) return false
        // 与 fullscreenOrientation(true) 同一判定：竖屏片源走竖屏全屏，其余横屏全屏。
        // 不看 resources.configuration.orientation —— 点全屏那一刻方向还没转过来。
        val container = playContainer
        val portraitVideo = container != null && container.hasVideoSize() && container.isPortraitVideo()
        return !portraitVideo
    }

    override fun keepStatusBarHidden(): Boolean = isLandscapeFullscreen()

    private var statusBarIconsLight = false

    /** 播放页（小窗播放）整屏纯黑，状态栏/导航栏图标必须强制白色。 */
    private var playerChromeBlack = false

    fun setStatusBarIconsLight(light: Boolean) {
        if (statusBarIconsLight == light) return
        statusBarIconsLight = light
        applyStatusBarAppearance()
    }

    fun setPlayerChromeBlack(black: Boolean) {
        if (playerChromeBlack == black) return
        playerChromeBlack = black
        applyStatusBarAppearance()
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (playerChromeBlack) {
                // 播放页黑底：白图标。之前这里跟着页面状态走「深色图标」，
                // 黑图标压在黑顶栏上等于状态栏消失。
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                return@apply
            }
            isAppearanceLightStatusBars = statusBarIconsLight
            // 短剧播放时整屏是黑的（画面 + 渐变蒙层），导航栏图标也要跟着变浅，
            // 否则手势条压在黑色画面上几乎看不见。
            isAppearanceLightNavigationBars = !statusBarIconsLight && !AppThemeState.isDark(systemDark)
        }
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val container = playContainer
                if (vm.enteringFullscreen.value) {
                    vm.cancelEntrySlide()
                    container?.stopForExitFullscreen()
                    return
                }
                if (vm.exitingFullscreen.value) return
                if (vm.fullScreen.value) {
                    if (container != null && container.onBackPressed()) return
                    if (DetailPlaybackPolicy.plan(DetailPlaybackEntry.ExitFullscreen).stopPlayback) {
                        container?.stopForExitFullscreen()
                    }
                    vm.onFullScreenToggleRequested(false, playbackFacts())
                    container?.hideTipOnUiThread()
                } else {
                    if (vm.backToPreviousTarget()) {
                        pendingEpisodeSync = false
                        return
                    }
                    container?.setPlayTitle(false)
                    container?.setExitingPreview(true)
                    finish()
                }
            }
        })
        vm.initFromIntent(intent)
        // 从短剧信息流「点击观看完整剧」进来（autoPlay）：先把播放容器建好再组合界面，
        // 第一帧就是播放壳。否则会先渲染详情页/加载圈，等详情回来才切到播放窗口。
        if (vm.directPlay.value) {
            ensurePlayContainer()
            // 「全屏观看」进来：直接把全屏状态置上，第一帧就是全屏播放器，
            // 不经过详情页、也不经过小窗。
            if (intent?.getBooleanExtra("openFullscreen", false) == true) {
                vm.enterFullscreenInstant()
            }
        }
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                SheetHostScaffold {
                    DetailScreen(activity = this, vm = vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingEpisodeSync = false
        vm.onFullScreenToggleRequested(false, playbackFacts())
        vm.dismissEpisodeSheet()
        playContainer?.cancelCastWait()
        playContainer?.endCastPrepare()
        playContainer?.uiState()?.clearOverlays()
        playContainer?.hideTipOnUiThread()
        vm.pushTargetFromIntent(intent)
    }

    fun ensurePlayContainer(): PlayContainer {
        if (playContainer == null) {
            playContainer = PlayContainer(this).also {
                it.pageHostRef = this
                it.setPreviewMode(true)
                it.qualitySelectedListener = PlayContainer.OnQualitySelectedListener { position -> vm.onQualitySelectionAccepted(position) }
            }
        }
        return requireNotNull(playContainer) { "play container not created" }
    }

    fun preheatWebView() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { preheatWebView() }
            return
        }
        if (!webViewPreheated.compareAndSet(false, true)) return
        val startedAt = System.nanoTime()
        try {
            WebView(this).destroy()
            AppLog.i("echo-webview-prewarm: " + (System.nanoTime() - startedAt) / 1_000_000L + "ms")
        } catch (th: Throwable) {
            AppLog.e("DetailActivity", th)
        }
    }

    fun playbackFacts(): DetailPlaybackFacts = DetailPlaybackFacts(
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )

    private fun releasePlayContainer() {
        playContainer?.hostDestroy()
        playContainer = null
    }

    override fun context(): Context = this

    override fun isPageAlive(): Boolean = !isFinishing && !isDestroyed

    override fun runOnUi(action: Runnable) {
        if (isPageAlive()) runOnUiThread(action)
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun requestNotificationPermission() {
        PermissionHelper.requestNotificationIfNeeded(this)
    }

    override fun onPlaybackLinesExhausted(): Boolean = startDetailFallbackAfterLinesExhausted()

    override fun showEpisodeSheet() {
        if (vm.vodInfo != null) vm.showEpisodeSheet()
    }

    fun playCurrent() {
        val container = ensurePlayContainer()
        container.cancelCastWait()
        container.endCastPrepare()
        container.clearCastAbort()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.clearSourceSwitchTip()
            return
        }
        container.setData(session)
    }

    fun ensurePlaying(): PlayContainer {
        val container = ensurePlayContainer()
        if (!engineOwnsDetailContent()) {
            playCurrent()
        } else {
            container.ensurePlaybackActive()
        }
        return container
    }

    /**
     * 引擎里已经装着内容、但装的是别的影片时（典型场景：在影片 A 的音乐页返回首页，
     * 再打开全新影片 B），不能直接复用，否则音乐页拿到的是 A 的海报与内容。
     */
    private fun engineOwnsDetailContent(): Boolean {
        val playing = PlaybackService.peek()?.controller()?.vod() ?: return false
        val info = vm.vodInfo ?: return false
        return contentMatches(playing, info)
    }

    private fun contentMatches(playing: VodInfo, info: VodInfo): Boolean {
        if (playing.id != info.id) return false
        // 首页复用同一播放器实例时 controller 的 sourceKey 可能取自上次会话，
        // 因此只在本页确实用过该源时才要求源一致，避免把同一影片误判成新内容。
        val expectedSource = vm.firstsourceKey
        return expectedSource.isEmpty()
            || playing.sourceKey == expectedSource
            || vm.sourceKey == expectedSource
    }

    fun openCast() {
        val container = ensurePlayContainer()
        container.cancelCastWait()
        container.beginCastPrepare()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.endCastPrepare()
            Toast.makeText(this, getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        container.setData(session)
        container.beginCastWait()
    }

    fun musicPlaybackDetected(): Boolean {
        if (!UiSettingsState.config.musicAutoOpenPage) return false
        val container = playContainer ?: return false
        val engine = PlaybackService.peek() ?: return false
        if (engine.isReleased() || engine.attachedPage() !== container) return false
        val state = engine.player().playState
        val accepted = state == PlayState.PREPARING ||
            state == PlayState.PREPARED ||
            state == PlayState.BUFFERING ||
            state == PlayState.BUFFERED ||
            state == PlayState.PLAYING
        return accepted && isAudioContent()
    }

    fun isAudioContent(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        val url = controller.webPlayUrl() ?: return false
        return PlaybackController.looksLikeAudioUrl(url) || controller.isAudioOnlyContent()
    }

    fun openMusicPlayer() {
        if (vm.vodInfo == null) {
            Toast.makeText(this, getString(R.string.detail_content_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (isIsoPlayback()) {
            Toast.makeText(this, getString(R.string.player_iso_music_unsupported), Toast.LENGTH_SHORT).show()
            return
        }
        // 先按纯音频启动，避免详情页这一帧就把视频解码器建起来（交接后无法回收）。
        prepareAudioOnlyForMusicPage()
        ensurePlaying()
        if (!handOffToMusicPlayer()) {
            Toast.makeText(this, getString(R.string.detail_no_playable_content), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isIsoPlayback(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        if (!engineOwnsDetailContent()) return false
        return MediaSources.isoHeaders(controller.webHeaderMap()) || controller.isIsoEscalated()
    }

    private fun prepareAudioOnlyForMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(true)
    }

    private fun restoreVideoAfterMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(false)
    }

    fun handOffToMusicPlayer(): Boolean {
        val container = playContainer ?: run {
            restoreVideoAfterMusicPage()
            return false
        }
        val playing = PlaybackService.peek()?.controller()?.vod()
        if (playing == null) {
            restoreVideoAfterMusicPage()
            return false
        }
        // 引擎里若还装着别的影片，先切到本页内容，避免音乐页显示上一部影片。
        val info = vm.vodInfo
        if (info != null && !contentMatches(playing, info)) {
            AppLog.i("echo-music handoff switch content: " + playing.id + " -> " + info.id)
            restoreVideoAfterMusicPage()
            playCurrent()
        }
        prepareAudioOnlyForMusicPage()
        val keepDetailPage = !isAudioContent()
        container.setExitingPreview(true)
        container.handOverToNextPage()
        MusicPlayerActivity.start(this, vm.firstsourceKey)
        if (keepDetailPage) pendingEpisodeSync = true else finish()
        return true
    }

    private fun syncEpisodeAfterMusicPage() {
        if (!pendingEpisodeSync) return
        pendingEpisodeSync = false
        vm.syncEpisodeAfterMusicReturn()
    }

    fun applyFullscreen(full: Boolean) {
        if (appliedFullscreen == full) return
        appliedFullscreen = full
        // 点「全屏」直接转横屏，不再「跟随手机方向」。
        // 之前进全屏走的是 orientationPolicyValue()（很多机型等于竖屏锁定）或 SENSOR，
        // 竖着拿手机点全屏就一直停在竖屏 —— 用户看到的就是「点了全屏横不过来」。
        requestedOrientation = fullscreenOrientation(full)
        if (full) {
            armVideoSizeWatch()
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
            // 横屏全屏（首页点播/短剧横屏）：顶部状态栏不显示，直接藏掉。
            // 竖屏全屏：状态栏和底部导航键透明悬浮在画面上——画面铺满整屏，
            // 系统栏不隐藏、不占画面，直接浮在视频之上。
            // 注意：这里不调用 applyHideStatusBarPref()，否则全局「隐藏状态栏」偏好
            // 会在 show 之后又把状态栏藏回去（上一版全屏异常的根因）。
            if (isLandscapeFullscreen()) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
            applyStatusBarAppearance()
        } else {
            videoSizeWatchArmed = false
            playContainer?.videoSizeReadyListener = null
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
            applyHideStatusBarPref()
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
        syncFullBoxSideEffects()
    }

    private fun armVideoSizeWatch() {
        val container = playContainer ?: return
        if (container.hasVideoSize()) {
            applyVideoOrientation(container.isPortraitVideo())
            return
        }
        if (videoSizeWatchArmed) return
        videoSizeWatchArmed = true
        container.videoSizeReadyListener = { portraitVideo -> onVideoSizeReady(portraitVideo) }
    }

    private fun onVideoSizeReady(portraitVideo: Boolean) {
        if (!videoSizeWatchArmed) return
        videoSizeWatchArmed = false
        playContainer?.videoSizeReadyListener = null
        applyVideoOrientation(portraitVideo)
    }

    private fun applyVideoOrientation(portraitVideo: Boolean) {
        if (!vm.fullScreen.value) return
        // 全屏方向：横屏片源强制横屏、竖屏片源保持竖屏（硬转横屏画面会被压成中间一条）。
        requestedOrientation = if (portraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * 全屏时的目标方向。
     * - 进全屏：横屏片源一律横屏；竖屏片源保持竖屏，不然画面缩成中间一条。
     * - 退全屏：回到竖屏小窗（短剧锁竖屏，其它走页面方向策略）。
     */
    private fun fullscreenOrientation(full: Boolean): Int {
        if (!full) {
            return if (isDramaMode()) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                orientationPolicyValue()
            }
        }
        val container = playContainer
        val portraitVideo = container != null && container.hasVideoSize() && container.isPortraitVideo()
        return if (portraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** 是否短剧播放（短剧点全屏要直接横屏，与普通点播区分开）。 */
    private fun isDramaMode(): Boolean = runCatching { vm.shortDrama.value }.getOrDefault(false)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 全屏期间方向由播放器说了算：基类 super 里会把方向策略（竖屏锁定）重新套上去，
        // 横屏刚转好就被改回竖屏，表现就是「点全屏闪一下又回到竖屏」。
        if (vm.fullScreen.value) {
            requestedOrientation = fullscreenOrientation(true)
            // 方向落地后按横/竖屏重新应用状态栏策略（横屏藏、竖屏悬浮）。
            hideSysBar()
            if (!isLandscapeFullscreen()) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.show(WindowInsetsCompat.Type.navigationBars())
                applyStatusBarAppearance()
            }
        }
        settleRotationAfterExit()
    }

    fun settleRotationAfterExit() {
        if (!vm.rotating.value) return
        vm.settleRotation()
        syncFullBoxSideEffects()
    }

    fun setPlayerTouchBlocked(blocked: Boolean) {
        playContainer?.touchBlocked = blocked
    }

    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return DetailFullScreenFrame.playerFullScreen(vm.rotating.value, vm.fullScreen.value, landNow)
    }

    private fun syncFullBoxSideEffects() {
        playContainer?.setPreviewMode(!isFullBox())
    }

    fun startDetailFallbackAfterLinesExhausted(): Boolean = vm.startFallbackAfterLinesExhausted()

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
        playContainer?.hostResume()
        syncEpisodeAfterMusicPage()
        val container = playContainer ?: return
        vm.applyPlaybackEntry(
            DetailPlaybackEntry.MusicReturn,
            resumable = container.hasClaimedPlayback() && !container.ownsEngineContent() && container.isPlaybackActive(),
        )
    }

    override fun onPause() {
        playContainer?.hostPause()
        super.onPause()
    }

    override fun onDestroy() {
        playContainer?.cancelCastWait()
        releasePlayContainer()
        vm.destroyEngine()
        super.onDestroy()
    }

    companion object {
        private val webViewPreheated = AtomicBoolean(false)
    }
}
