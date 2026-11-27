@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.aura.asbox.ui

import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.aura.asbox.R
import com.github.aura.asbox.player.BasePlayerView
import com.github.aura.asbox.player.state.PlayerUiState
import com.github.aura.asbox.ui.player.CastSheet
import com.github.aura.asbox.ui.player.DramaDeck
import com.github.aura.asbox.ui.player.DramaFullscreenBar
import com.github.aura.asbox.ui.player.DramaTopBar
import com.github.aura.asbox.ui.player.EmbeddedPlayerDeck
import com.github.aura.asbox.ui.player.EmbeddedTopBar
import com.github.aura.asbox.ui.player.PlayContainer
import com.github.aura.asbox.ui.components.HomeBackdrop
import com.github.aura.asbox.ui.components.ImagePalette
import com.github.aura.asbox.ui.components.LoadState
import com.github.aura.asbox.ui.components.LoadStateBox
import com.github.aura.asbox.ui.components.LocalTopBarGlassBackdrop
import com.github.aura.asbox.ui.components.PosterBackdrop
import com.github.aura.asbox.ui.components.VodCardMenu
import com.github.aura.asbox.ui.components.rememberVodCardMenuState
import com.github.aura.asbox.ui.theme.AppThemeState
import com.github.aura.asbox.ui.settings.UiSettingsState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import com.github.aura.asbox.ui.page.jumpToSearch

private const val EntrySlideDurationMs = 300
private const val ContentSlideRatio = 0.2f

/** 短剧底部控制条（全屏观看 + 剧名/进度/选集）的估算高度，首帧测量前先用它兜底。 */
private val DeckBelowEstimate = 240.dp

/** 视频区的最小高度，屏幕特别矮时不至于被控制条挤没。 */
private val MinPlayerBand = 200.dp

private const val FlatScrollScrimAlpha = 0.45f

private val FlatPageScrimStops = arrayOf(
    0f to Color.Black.copy(alpha = 0.05f),
    0.45f to Color.Black.copy(alpha = 0.28f),
    0.75f to Color.Black.copy(alpha = 0.48f),
    1f to Color.Black.copy(alpha = 0.62f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity, vm: DetailViewModel) {
    val menuContext = LocalContext.current
    val pageState by vm.pageState.collectAsState()
    val full by vm.fullScreen.collectAsState()
    val rotating by vm.rotating.collectAsState()
    val playSignal by vm.playSignal.collectAsState()
    val directPlay by vm.directPlay.collectAsState()
    val collectedState by vm.collected.collectAsState()
    val dramaMode by vm.shortDrama.collectAsState()
    // 从短剧信息流进来时带了「竖屏片源」提示：画面尺寸还没回来之前也先按竖屏小窗排。
    val dramaPortraitHint by vm.dramaPortrait.collectAsState()
    val enteringFullscreen by vm.enteringFullscreen.collectAsState()
    val exitingFullscreen by vm.exitingFullscreen.collectAsState()
    val vodMenu = rememberVodCardMenuState(
        lookupCollected = vm::isVideoCollected,
        onCollectToggle = vm::toggleVideoCollect,
    )

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val heroFadePx = with(density) { HeroScrollFadeDistance.roundToPx() }
    val heroProgress by remember(listState, heroFadePx) {
        derivedStateOf {
            heroScrollProgress(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                heroFadePx,
            )
        }
    }
    val heroGone by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val configuration = LocalConfiguration.current
    val flatHero = flatHeroLayout(
        configuration.smallestScreenWidthDp,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    // 短剧播放时视频铺到状态栏底下，顶栏又是黑色渐变，图标必须用白色；
    // 其它情况沿用「海报滑走/扁平大图」的判断。
    LaunchedEffect(heroGone, flatHero, dramaMode) {
        activity.setStatusBarIconsLight(dramaMode || (heroGone && !flatHero))
    }

    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullBox = DetailFullScreenFrame.fullBox(rotating, full, isLandscapeNow)
    val inTransition = enteringFullscreen || exitingFullscreen
    var slideWidthPx by remember { mutableFloatStateOf(0f) }

    val container = activity.playContainer
    var embeddedChromePx by remember { mutableStateOf(0) }
    // 播放器那一层的实测高度：短剧竖屏按它算视频区尺寸，避免用 configuration 屏高重复扣系统栏。
    var playerBoxPx by remember { mutableStateOf(0) }
    var playerBoxWidthPx by remember { mutableStateOf(0) }
    // 底部控制条（全屏观看/进度/选集…）的实测高度：画面高度 = 整层高度 - 顶栏 - 它。
    var deckBelowPx by remember { mutableStateOf(0) }
    // 竖屏铺满时，控制条里「进度条那一块」的高度（含导航栏）：
    // 画面从顶栏下沿一直铺到它的上沿，中间那排功能图标就浮在画面上。
    var bottomBlockPx by remember { mutableStateOf(0) }
    // 进度条那一行的 y（相对窗口）：画面矩形下沿照信息流页那样贴到它上面一点。
    var seekBarTopPx by remember { mutableStateOf(0) }
    val seekGapPx = with(density) { 6.dp.toPx() }.toInt()
    val bottomDropPx = with(density) { 9.dp.toPx() }.toInt()

    val embedded = container != null && !full && !rotating && !inTransition
    // 首页点播的竖屏片源：小窗里画面铺满（裁剪边缘），并且不显示「全屏」按钮。
    // 横屏片源的小窗、短剧小窗都不走这一套。
    val stageVideoAspect = remember(container?.uiState()?.videoSize) {
        parseVideoAspect(container?.uiState()?.videoSize ?: "")
    }
    val portraitPointPlay = embedded && !dramaMode && stageVideoAspect in 0.01f..0.99f
    // 播放页整屏纯黑：状态栏/导航栏图标切成白色，不然黑图标压黑底会像状态栏没了。
    LaunchedEffect(embedded, container, dramaMode, portraitPointPlay) {
        activity.setPlayerChromeBlack(embedded)
        // 小窗和全屏的画面比例各存一份：切回小窗时套用小窗自己的那份，
        // 在全屏里调过的比例不会带回来；短剧小窗又是单独一份，与首页点播互不影响。
        container?.applyWindowMode(embedded, dramaMode)
        // 竖屏点播小窗强制「铺满」（中心裁剪），横屏与短剧保持各自的设置。
        container?.setForcedWindowScale(
            if (portraitPointPlay) BasePlayerView.SCREEN_SCALE_CENTER_CROP else null
        )
    }
    val scope = rememberCoroutineScope()
    var backdropPic by remember { mutableStateOf("") }
    var backdropSeed by remember { mutableStateOf<Int?>(null) }
    val pendingPic = vm.pendingPicture
    val backdropSource = backdropPic.ifEmpty { pendingPic }
    val backdropSeedArgb = backdropSeed ?: HomeBackdrop.seedOf(pendingPic)
    val darkTheme = AppThemeState.isDark(isSystemInDarkTheme()) || flatHero
    val schemeSeed = backdropSeedArgb ?: if (flatHero) AppThemeState.config.seedArgb else null
    val colorScheme = schemeSeed?.let { argb ->
        remember(argb, darkTheme) {
            AppThemeState.customScheme(argb, darkTheme, AppThemeState.config.style)
        }
    } ?: MaterialTheme.colorScheme

    LaunchedEffect(pageState) {
        if (pageState is DetailViewModel.PageState.Loading) {
            backdropPic = ""
            backdropSeed = null
        }
    }

    LaunchedEffect(pageState) {
        if (pageState is DetailViewModel.PageState.Ready) {
            withFrameNanos { }
            withFrameNanos { }
            activity.preheatWebView()
        }
    }

    // 从短剧信息流的「点击观看完整剧」进来（directPlay）：第一帧就要有播放容器，
    // 直接摆出播放壳；否则会先渲染一帧详情页，再被播放窗口盖掉（用户说的「先跳详情页再跳播放窗口」）。
    LaunchedEffect(full, playSignal, directPlay) {
        if (activity.playContainer == null && (full || playSignal > 0 || directPlay)) {
            activity.ensurePlayContainer()
        }
    }

    LaunchedEffect(vm) {
        vm.playbackCommands.collect { command ->
            val c = activity.playContainer ?: return@collect
            when (command) {
                is PlaybackCommand.StopForContentSwitch -> c.stopForContentSwitch()
                is PlaybackCommand.StopForSourceSwitch -> c.stopForSourceSwitch(command.tip)
                is PlaybackCommand.ClearSourceSwitchTip -> c.clearSourceSwitchTip()
                is PlaybackCommand.SetEpisodeSheetOpen -> c.setEpisodeSheetOpen(command.open)
                is PlaybackCommand.SelectQuality -> c.selectQuality(command.position)
            }
        }
    }

    LaunchedEffect(playSignal) {
        if (playSignal > 0) activity.playCurrent()
    }

    var musicWatch by remember { mutableStateOf(false) }
    var musicArmed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { musicWatch = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { musicWatch = false }
    LaunchedEffect(playSignal) {
        if (playSignal > 0) musicArmed = true
    }
    LaunchedEffect(musicWatch, musicArmed) {
        if (!musicWatch || !musicArmed) return@LaunchedEffect
        while (musicArmed) {
            snapshotFlow {
                activity.playContainer?.uiState()?.playState to UiSettingsState.config.musicAutoOpenPage
            }
                .filter { (_, enabled) -> enabled }
                .first { activity.musicPlaybackDetected() }
            if (activity.handOffToMusicPlayer()) {
                musicArmed = false
            } else {
                delay(300)
            }
        }
    }

    val slideAnim = remember { Animatable(0f) }

    fun slideProgress(): Float = if (inTransition) slideAnim.value else if (full) 1f else 0f

    LaunchedEffect(enteringFullscreen, exitingFullscreen) {
        when {
            exitingFullscreen -> {
                slideAnim.animateTo(0f, tween(EntrySlideDurationMs, easing = FastOutSlowInEasing))
                try {
                    vm.onExitSlideFinished()
                } finally {
                    activity.settleRotationAfterExit()
                }
            }

            enteringFullscreen -> {
                activity.settleRotationAfterExit()
                slideAnim.animateTo(1f, tween(EntrySlideDurationMs, easing = FastOutSlowInEasing))
                vm.onEntrySlideFinished()
            }
        }
    }

    LaunchedEffect(inTransition, container) {
        activity.setPlayerTouchBlocked(inTransition)
    }

    LaunchedEffect(full) {
        activity.applyFullscreen(full)
    }

    LaunchedEffect(Unit) {
        vm.toastEvent.collect { message ->
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.finishEvent.collect {
            activity.finish()
        }
    }

    val pageBackdrop = rememberLayerBackdrop(onDraw = { drawContent() })
    MaterialTheme(colorScheme = colorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { slideWidthPx = it.width.toFloat() }
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (flatHero) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                        val info = vm.vodInfo
                        if (info != null) {
                            DetailHeroPoster(
                                vm = vm,
                                title = info.name ?: "TVBox",
                                altTitle = info.en,
                                picture = info.pic,
                                year = info.year,
                                onImage = { image ->
                                    scope.launch {
                                        val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                                        if (seed != null && backdropSeed == null) backdropSeed = seed
                                    }
                                },
                                onBackdropPic = { backdropPic = it },
                                paused = full,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(*FlatPageScrimStops)),
                        )
                    }
                    FlatScrollScrim(progress = { heroProgress })
                }
            } else {
                PosterBackdrop(
                    pic = backdropSource,
                    onImage = { image ->
                        scope.launch {
                            val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                            if (seed != null && backdropSeed == null) backdropSeed = seed
                        }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (embedded) with(density) { embeddedChromePx.toDp() } else 0.dp)
                    .offset {
                        IntOffset((-slideProgress() * slideWidthPx * ContentSlideRatio).toInt(), 0)
                    },
            ) {
                if (!fullBox || exitingFullscreen) {
                    when (val state = pageState) {
                        is DetailViewModel.PageState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ContainedLoadingIndicator(Modifier.size(64.dp))
                            }
                        }

                        is DetailViewModel.PageState.Empty -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                LoadStateBox(
                                    state = LoadState.Empty,
                                    emptyText = state.msg ?: stringResource(R.string.detail_empty_source),
                                    errorText = "",
                                    retryText = "",
                                    modifier = Modifier.weight(1f),
                                )
                                SourceSection(vm, currentSourceName = null)
                            }
                        }

                        is DetailViewModel.PageState.Ready -> {
                            CompositionLocalProvider(LocalTopBarGlassBackdrop provides pageBackdrop) {
                                DetailContent(
                                    activity = activity,
                                    vm = vm,
                                    listState = listState,
                                    onPosterPic = { backdropPic = it },
                                    onSeed = { argb -> if (argb != null) backdropSeed = argb },
                                    playerEmbedded = embedded,
                                )
                            }
                        }
                    }
                }
                if (!fullBox || exitingFullscreen) {
                    DetailTopScrim(
                        modifier = Modifier.align(Alignment.TopCenter),
                        alpha = if (flatHero) 1f else 1f - heroProgress,
                    )
                }
            }

            val playerContainer = container
            if (playerContainer != null) {
                // 播放器自始至终只有一个 AndroidView 宿主：嵌入/全屏只改它的摆放几何，
                // 绝不在两个宿主之间搬家——搬家会把视频表面弄坏（全屏时画面只剩左上角一小块）。
                val statusBarTopPx = WindowInsets.statusBars.getTop(density)
                val embeddedTopPx = statusBarTopPx +
                    with(density) { 56.dp.roundToPx() }
                // 片源画幅（宽/高）：只用来判断竖屏片源，未知时是 0（退回原来的做法）。
                val playerUiState = playerContainer.uiState()
                val videoAspect = remember(playerUiState.videoSize) {
                    parseVideoAspect(playerUiState.videoSize)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged {
                            playerBoxPx = it.height
                            playerBoxWidthPx = it.width
                        },
                ) {
                    // 以播放器这一层的实际高度为准算尺寸：不再用 configuration 的屏高去减系统栏
                    // （会重复扣减，导致底部漏出半截推荐内容）。
                    val boxPx = if (playerBoxPx > 0) {
                        playerBoxPx
                    } else {
                        with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()
                    }
                    val minBandPx = with(density) { MinPlayerBand.roundToPx() }
                    // 竖屏小窗（短剧与点播同一套）：顶栏下方 → 底部控制条上沿，整块都留给画面。
                    // 控制条贴屏幕底，首屏就是一整块播放器，不再是「上面一条黑、中间一条画面」。
                    val deckPx = if (deckBelowPx > 0) {
                        deckBelowPx
                    } else {
                        with(density) { DeckBelowEstimate.roundToPx() }
                    }
                    // 视频窗可用区域：顶栏下沿 → 底部控制条上沿。
                    // 控制条永远贴屏幕底，画面怎么摆都不影响功能键的位置。
                    val bandPx = if (!embedded) {
                        boxPx
                    } else {
                        (boxPx - embeddedTopPx - deckPx).coerceAtLeast(minBandPx)
                    }
                    // 竖屏片源的短剧：按「画面宽度撑满屏幕」来放大（高度 = 宽 / 画幅）。
                    // 放不下时向下延伸，多出来的那截被底部控制条的黑底盖住——
                    // 之前按高度塞进控制条上方，竖屏画面两边会留一条黑边，看起来小一圈。
                    val portraitCover = dramaMode &&
                        (videoAspect in 0.01f..0.99f || (videoAspect <= 0.01f && dramaPortraitHint))
                    // 首页竖屏点播小窗：画面铺到「进度条那块黑底」的上沿为止
                    // （用户划的那条红线），中间的功能图标浮在画面上。
                    val portraitFillPx = if (portraitPointPlay && bottomBlockPx > 0) {
                        (boxPx - embeddedTopPx - bottomBlockPx).coerceAtLeast(minBandPx)
                    } else {
                        bandPx
                    }
                    val videoTopPx = embeddedTopPx
                    if (embedded) {
                        // 播放页整屏黑底（不偏移）：顶栏、画面上下多出来的那截、控制条背后
                        // 全是纯黑，任何半透明的渐变/模糊都不会再透出海报底色那种偏蓝的灰。
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                        )
                    }
                    AndroidView(
                        factory = { adoptPlayerView(playerContainer) },
                        modifier = Modifier
                            .fillMaxSize()
                            .layout { measurable, constraints ->
                                if (embedded) {
                                    val w = constraints.maxWidth
                                    if (portraitCover) {
                                        // 竖屏短剧：画面定位整套照搬信息流页（图一）——用户红线
                                        // 就是照那套算法画的，两页用同一公式才会落在同一位置。
                                        //   顶沿 = 屏高 5%；
                                        //   基准下沿 = 进度条 y − 6dp，画面底沿 = 基准下沿 + 9dp；
                                        //   顶部锚点用「基准框」里 contain 的真实顶沿，保持不漂。
                                        val screenH = constraints.maxHeight
                                        val frameTop = (screenH * 5f / 100f).toInt().coerceAtLeast(0)
                                        val baseBottom = if (seekBarTopPx > 0) {
                                            (seekBarTopPx - seekGapPx).coerceIn(frameTop + 1, screenH)
                                        } else {
                                            screenH
                                        }
                                        val frameBottom = (baseBottom + bottomDropPx)
                                            .coerceIn(frameTop + 1, screenH)
                                        // 切集瞬间 forgetVideoSize 会把画幅清成「0 X 0」，
                                        // 这时 w/videoAspect 会除零得到 Int.MAX_VALUE，
                                        // 后面 Constraints.fixed 拿到巨大高度会直接把播放器崩掉。
                                        // 画幅没回来前先按 0 处理（画面按框居中），新尺寸上来再按真实画幅排。
                                        val baseVh = if (videoAspect > 0.01f) (w / videoAspect).toInt() else 0
                                        val baseBoxH = baseBottom - frameTop
                                        val baseBoxAspect = w.toFloat() / baseBoxH
                                        val oldTop = if (videoAspect > baseBoxAspect) {
                                            frameTop + (baseBoxH - baseVh) / 2
                                        } else {
                                            frameTop
                                        }
                                        val targetTop = oldTop.coerceAtMost(frameBottom - 1)
                                        val vh = frameBottom - targetTop
                                        // 宽度至少撑满屏宽：多出来的左右各裁一点，绝不留黑边。
                                        val vw = (vh * videoAspect).toInt().coerceAtLeast(w)
                                        val placeable = measurable.measure(Constraints.fixed(vw, vh))
                                        layout(w, screenH) {
                                            placeable.place((w - vw) / 2, targetTop)
                                        }
                                    } else {
                                        // 小窗（16:9 片源/点播）：窗口位置保持原样——顶栏下沿
                                        // 铺到底部控制条上沿，画面按片源原画幅居中显示。
                                        val h = if (portraitPointPlay) {
                                            portraitFillPx
                                        } else {
                                            bandPx
                                        }
                                        val placeable = measurable.measure(Constraints.fixed(w, h))
                                        layout(w, constraints.maxHeight) {
                                            placeable.place(0, videoTopPx)
                                        }
                                    }
                                } else {
                                    // 全屏：画面铺满整个屏幕（y=0 到屏底），顶部状态栏和底部
                                    // 导航键透明悬浮在画面之上——不给它们留黑边。
                                    // 竖屏片源按「更高/更宽」等比裁切铺满整屏；横屏片源仍整屏居中。
                                    val sw = constraints.maxWidth
                                    val sh = constraints.maxHeight
                                    if (videoAspect in 0.01f..0.99f ||
                                        (videoAspect <= 0.01f && dramaPortraitHint)
                                    ) {
                                        val screenAspect = sw.toFloat() / sh
                                        val vw: Int
                                        val vh: Int
                                        // 切集瞬间画幅会被清成「0 X 0」（forgetVideoSize + onNewSession）。
                                        // 老代码这里 sw/videoAspect 除零 → Infinity.toInt()=Int.MAX_VALUE，
                                        // 再 Constraints.fixed(vw, 21亿) 直接把播放器崩掉、退回主页。
                                        // 画幅没回来前先整屏铺满，新尺寸上来再按真实画幅等比裁切。
                                        if (videoAspect <= 0.01f) {
                                            vw = sw
                                            vh = sh
                                        } else if (videoAspect > screenAspect) {
                                            // 视频相对更宽：按屏高撑满，左右裁一点。
                                            vh = sh
                                            vw = (sh * videoAspect).toInt()
                                        } else {
                                            // 视频相对更高：按屏宽撑满，上下裁一点。
                                            vw = sw
                                            vh = (sw / videoAspect).toInt()
                                        }
                                        val placeable = measurable.measure(Constraints.fixed(vw, vh))
                                        val x = ((1f - slideProgress()) * slideWidthPx).toInt()
                                        layout(sw, sh) {
                                            placeable.place((sw - vw) / 2 + x, (sh - vh) / 2)
                                        }
                                    } else {
                                        val placeable = measurable.measure(constraints)
                                        val x = ((1f - slideProgress()) * slideWidthPx).toInt()
                                        layout(constraints.maxWidth, constraints.maxHeight) {
                                            placeable.place(x, 0)
                                        }
                                    }
                                }
                            },
                    )
                    // 倍速/选集等对话框打开时，整层手势直接不组合：弹窗画在播放器自身的
                    // ComposeView 里，而手势层是盖在它之上的兄弟节点（Z 序更高）。哪怕只是
                    // 一个空的 pointerInput，也会先拿到 down 事件，导致弹窗选项「点不动」。
                    // 只把 enabled 设为 false 仍会保留这层 Box，所以这里直接整体跳过组合。
                    if (embedded && dramaMode && !playerContainer.uiState().overlayPanelOpen) {
                        // 竖屏小窗：视频区上下滑动切上一集/下一集（上滑下一集、下滑上一集）。
                        // 高度正好等于画面，不压到底部控制条，控制条上的按钮照常点。
                        val dramaActions = playerContainer.playerActions()
                        // 手势层盖到「全屏观看胶囊上沿」为止：视频满屏铺着，
                        // 但底部那颗胶囊要能正常点，不能被手势层抢走。
                        // 手势层高度 = 画面高度，位置和画面本身对齐（顶栏下方开始）。
                        val swipePx = bandPx.coerceAtLeast(minBandPx)
                        DramaSwipeLayer(
                            onPrevious = { dramaActions?.onPreClicked() },
                            onNext = { dramaActions?.onNextClicked() },
                            onTap = { dramaActions?.onPlayerClicked() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset { IntOffset(0, embeddedTopPx) }
                                .height(with(density) { swipePx.toDp() }),
                        )
                    }
                    if (embedded && dramaMode) {
                        // 「全屏观看」上移到进度条上方、贴着画面下沿：
                        // 以前它悬在画面和底部控制条之间那一大片黑里，离画面太远。
                        val videoH = if (videoAspect > 0.01f && playerBoxWidthPx > 0) {
                            (playerBoxWidthPx / videoAspect).coerceAtMost(bandPx.toFloat())
                        } else {
                            bandPx.toFloat()
                        }
                        val barPx = with(density) { 58.dp.toPx() }
                        val want = embeddedTopPx + (bandPx + videoH) / 2f +
                            with(density) { 12.dp.toPx() }
                        val deckTop = (boxPx - deckPx).toFloat()
                        val barTop = want.coerceAtMost(deckTop - barPx)
                            .coerceAtLeast(embeddedTopPx + barPx)
                        DramaFullscreenBar(
                            onFullscreen = {
                                vm.onFullScreenToggleRequested(true, activity.playbackFacts())
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset { IntOffset(0, barTop.toInt()) },
                        )
                    }
                    if (embedded) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .onSizeChanged { embeddedChromePx = it.height },
                        ) {
                            val chromeUi = playerContainer.uiState()
                            if (dramaMode) {
                                // 左上角只显示第几集：剧名/简介改到底部控制条里（红果那种排法）。
                                val episodeNo = (chromeUi.sessionVod?.playIndex
                                    ?: vm.vodInfo?.playIndex ?: 0) + 1
                                DramaTopBar(
                                    title = stringResource(R.string.drama_feed_episode, episodeNo),
                                    actions = playerContainer.playerActions(),
                                    onBack = { activity.onBackPressedDispatcher.onBackPressed() },
                                )
                            } else {
                                // 剧名 + 集数并到顶栏左上角（原来压在视频下方的文字已删除），
                                // 分辨率/画质放名字下面一行。
                                EmbeddedTopBar(
                                    title = chromeUi.title.ifEmpty { vm.vodInfo?.name ?: "" },
                                    subtitle = if (chromeUi.videoQuality.isBlank()) {
                                        chromeUi.videoSize
                                    } else {
                                        chromeUi.videoSize + " · " + chromeUi.videoQuality
                                    },
                                    collected = collectedState,
                                    onBack = { activity.onBackPressedDispatcher.onBackPressed() },
                                    onCollect = { vm.toggleCollect() },
                                )
                            }
                            // 控制条始终贴屏幕底（横屏短剧/点播/竖屏短剧同一位置），
                            // 画面铺到它的上沿为止，不给中间留黑缝。
                            Spacer(Modifier.weight(1f))
                            // 底部控制条：贴屏幕底、避开导航栏。高度实测回传给画面，
                            // 画面正好铺满到它的上沿。
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { deckBelowPx = it.height }
                                    // 短剧：第一行「全屏观看」不铺底色，让竖屏画面从它背后
                                    // 一直铺到进度条上方（画面下沿到用户划的那条红线）；
                                    // 进度/选集那一块才铺纯黑。点播保持原来的内外边距。
                                    // 竖屏点播铺满时同理：导航栏那截留白挪进黑块里算。
                                    .then(
                                        if (dramaMode || portraitPointPlay) {
                                            Modifier
                                        } else {
                                            Modifier.navigationBarsPadding()
                                        }
                                    ),
                            ) {
                                val actions = playerContainer.playerActions()
                                if (actions != null) {
                                    if (dramaMode) {
                                        // 进度 + 选集：铺纯黑到底（含导航栏区域），
                                        // 画面正好在它上沿被挡住。
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                // 和信息流页保持一致：剧名/简介浮在画面上，
                                                // 详情块上半截透明，画面一直铺到进度条上沿；
                                                // 只有进度/选集那一截才是纯黑底。
                                                .background(
                                                    Brush.verticalGradient(
                                                        0f to Color.Transparent,
                                                        0.38f to Color.Transparent,
                                                        0.5f to Color.Black,
                                                        1f to Color.Black,
                                                    )
                                                )
                                                .navigationBarsPadding(),
                                        ) {
                                            // 红果式排法：进度 → 剧名（点进详情）→「第N集 | 简介 … 展开」→ 选集（带总集数）。
                                            val deckUi = playerContainer.uiState()
                                            val deckSession = deckUi.sessionVod
                                            val deckEpisode = (deckSession?.playIndex
                                                ?: vm.vodInfo?.playIndex ?: 0) + 1
                                            val deckCount = deckSession?.seriesMap
                                                ?.get(deckSession.playFlag)?.size
                                                ?: vm.vodInfo?.seriesMap
                                                    ?.get(vm.vodInfo?.playFlag)?.size
                                                ?: 0
                                            DramaDeck(
                                                state = deckUi,
                                                actions = actions,
                                                dramaName = vm.vodInfo?.name
                                                    ?: deckSession?.name.orEmpty(),
                                                dramaDescription = vm.vodInfo?.des
                                                    ?: deckSession?.des.orEmpty(),
                                                episodeLabel = stringResource(
                                                    R.string.drama_feed_episode,
                                                    deckEpisode,
                                                ),
                                                episodeCount = deckCount,
                                                onNameClick = null,
                                                // 进度条 y 回传给画面 View（和信息流页同一锚点）。
                                                onSeekBarY = { seekBarTopPx = it },
                                            )
                                        }
                                    } else {
                                        EmbeddedPlayerDeck(
                                            state = playerContainer.uiState(),
                                            actions = actions,
                                            onFullscreen = {
                                                vm.onFullScreenToggleRequested(true, activity.playbackFacts())
                                            },
                                            // 竖屏铺满时不再显示全屏按钮；横屏小窗保留。
                                            showFullscreen = !portraitPointPlay,
                                            // 竖屏铺满：控制条上半截透明，画面从背后铺到进度条上沿。
                                            transparentTop = portraitPointPlay,
                                            onBottomBlockHeight = { px ->
                                                if (bottomBlockPx != px) bottomBlockPx = px
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            container?.uiState()?.let { PlayerCastOverlay(it) }

            if (inTransition) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                )
            }
        }

        EpisodeSheet(vm, slideFromEnd = fullBox && isLandscapeNow, showQuality = full)
        VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
    }
}

/** 播放器 View 会在小窗口布局和全屏布局之间换父容器，挂载前先脱离旧 parent。 */
private fun adoptPlayerView(view: PlayContainer): PlayContainer {
    (view.parent as? ViewGroup)?.removeView(view)
    return view
}

/** 从 "1080 X 1920"（未知为 "0 X 0"）解析画幅 宽/高，解析不出来返回 0。 */
private fun parseVideoAspect(videoSize: String): Float {
    val parts = videoSize.split("X", "x")
    val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
    val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 0f
}

/**
 * 短剧竖屏小窗的切集手势层，覆盖在视频区上：
 * 竖直滑动超过阈值就切上一集/下一集（上滑 = 下一集，下滑 = 上一集），可以连续滑；
 * 轻点仍然当作「点播放器」处理（显示/隐藏控制条），不会把视频区的点击吞掉。
 */
@Composable
private fun DramaSwipeLayer(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    Box(
        modifier.pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var total = 0f
                var moved = false
                var switched = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    total += change.positionChange().y
                    if (!moved && abs(total) > slop) moved = true
                    if (moved && abs(total) > threshold) {
                        switched = true
                        if (total < 0f) onNext() else onPrevious()
                        total = 0f
                        change.consume()
                    }
                }
                if (!switched && !moved) onTap()
            }
        },
    )
}

@Composable
private fun PlayerCastOverlay(state: PlayerUiState) {
    state.castSheet?.let { sheet ->
        CastSheet(sheet) { state.dismissCastSheet() }
    }
}

@Composable
private fun FlatScrollScrim(progress: () -> Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = FlatScrollScrimAlpha * progress().coerceIn(0f, 1f),
                ),
            ),
    )
}
