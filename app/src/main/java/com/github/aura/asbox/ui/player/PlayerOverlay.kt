package com.github.aura.asbox.ui.player

import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.aura.asbox.R
import com.github.aura.asbox.player.state.ParamsChoice
import com.github.aura.asbox.player.state.PlayerActions
import com.github.aura.asbox.player.state.PlayerUiState
import com.github.aura.asbox.util.AppLog
import com.github.aura.asbox.player.gesture.VideoGestureHandler
import com.github.aura.asbox.player.gesture.VideoGestureSession
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun PlayerOverlay(
    state: PlayerUiState,
    actions: PlayerActions,
    decodeChoice: ParamsChoice,
    gestureHandler: VideoGestureHandler,
    gestureSession: (width: Int, height: Int, screenWidth: Int, downY: Float) -> VideoGestureSession,
    onTapPending: () -> Unit,
) {
    val screenWidthPx = LocalContext.current.resources.displayMetrics.widthPixels
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 竖屏全屏时视频 View 为了铺满整屏做了左右裁切，实际宽度会大于屏幕宽度
        // （1080 屏上可能是 1350），而播放器叠层是画在这个被裁切的 View 里的。
        // 若不把叠层收回可见范围，最左/最右的功能键就会被屏幕边缘切掉。
        val hostWidthPx = with(LocalDensity.current) { maxWidth.toPx().roundToInt() }
        val clippedSidePx = ((hostWidthPx - screenWidthPx) / 2f).coerceAtLeast(0f).toInt()
        val sidePadding = with(LocalDensity.current) { clippedSidePx.toDp() }
        Box(Modifier.fillMaxSize().padding(horizontal = sidePadding)) {
            PlayerOverlayContent(
                state = state,
                actions = actions,
                decodeChoice = decodeChoice,
                gestureHandler = gestureHandler,
                gestureSession = gestureSession,
                onTapPending = onTapPending,
                screenWidthPx = screenWidthPx,
            )
        }
    }
}

@Composable
private fun PlayerOverlayContent(
    state: PlayerUiState,
    actions: PlayerActions,
    decodeChoice: ParamsChoice,
    gestureHandler: VideoGestureHandler,
    gestureSession: (width: Int, height: Int, screenWidth: Int, downY: Float) -> VideoGestureSession,
    onTapPending: () -> Unit,
    screenWidthPx: Int,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .videoGestureLayer(
                handler = gestureHandler,
                sessionProvider = { size, downY ->
                    val sizeW = size.width
                    val sizeH = size.height
                    if (state.overlayPanelOpen || sizeW <= 0 || sizeH <= 0) {
                        null
                    } else {
                        gestureSession(sizeW, sizeH, screenWidthPx, downY)
                    }
                },
                onTapPending = onTapPending,
            ),
    ) {
        val iconBox = playerIconBox(maxWidth - playerEdgePadding() * 2)
        PlayerTipLayer(state)
        // 详情页小窗（previewMode）的控制条由页面自己的 deck 负责画
        // （标题 / 网速 / 全屏按钮 + 图标行 + 进度 + 播放控制），播放器内部不要再画一套，
        // 否则同一屏会出现两组图标、两条进度条，看着像界面重叠了。
        val hostedChrome = state.previewMode
        PlayerSlideVisibility(
            visible = !hostedChrome && (state.topLeftVisible || (state.topRightVisible && !state.previewMode)),
            fromTop = true,
        ) {
            PlayerTopBar(state, actions)
        }
        PlayerSlideVisibility(
            visible = !hostedChrome && state.controlsVisible,
            fromTop = false,
            // 全屏时系统栏（底部导航键）透明悬浮在画面上，功能键整体往上挪一点点，
            // 避免最底一排被手势条压住。只挪外层容器，功能键内部排布一律不动。
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
        ) {
            PlayerBottomBar(state, actions, largeActions = true)
        }
        PlayerSlideHint(state)
        PlayerSeekHint(state)
        PlayerFadeVisibility(
            visible = state.loadingVisible && !state.tipVisible,
            enterMs = PLAYER_OSD_IN_MS,
            exitMs = if (state.tipVisible) 0 else PLAYER_OSD_OUT_MS,
        ) {
            PlayerLoadingLayer(state)
        }
        PlayerNetSpeedCenter(state)
        PlayerSideButtons(state, actions, iconBox)
        PlayerFadeVisibility(
            visible = state.infoOsdVisible,
            enterMs = PLAYER_OSD_IN_MS,
            exitMs = PLAYER_OSD_OUT_MS,
        ) {
            PlayerInfoOsd(state, actions, maxWidth)
        }
        PlayerSpeedBoostHint(state)

        state.selectDialog?.let { dialogState ->
            val onDismiss = state::dismissSelectDialog
            if (state.previewMode) {
                // 内嵌小窗（详情页/短剧）：弹窗原本画在播放器 View 内部的 ComposeView 里，
                // 上面还盖着页面的切集手势层与顶部/底部控制条，点击选项的事件会被上层吞掉
                // ——表现为「弹窗弹得出，但点哪个都没反应」。改用独立 Dialog 窗口渲染，
                // 彻底脱离被拦截的 View 层级；透明全屏容器只负责占位，外观仍由 PlayerDialog 自己画。
                Dialog(
                    onDismissRequest = onDismiss,
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        PlayerSelectDialog(dialogState = dialogState, onDismiss = onDismiss)
                    }
                }
            } else {
                PlayerSelectDialog(dialogState = dialogState, onDismiss = onDismiss)
            }
        }

        state.paramsSheet?.let { sheet ->
            PlayerParamsSheet(
                sheet = sheet,
                decodeChoice = decodeChoice,
                tab = state.paramsTab,
                onTabSelected = state::selectParamsTab,
                osdVisible = state.infoOsdVisible,
                onToggleOsd = actions::onInfoOsdClicked,
                slideFromEnd = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
                onDismiss = state::dismissParamsSheet,
            )
        }

        state.danmuSettingSheet?.let { sheet ->
            DanmuSettingSheet(sheet) { state.dismissDanmuSettingSheet() }
        }
        state.danmuSearchSheet?.let { sheet ->
            DanmuSearchSheet(sheet) { state.dismissDanmuSearchSheet() }
        }
        state.subtitleSheet?.let { sheet ->
            SubtitleSheet(sheet) { state.dismissSubtitleSheet() }
        }
        state.subtitleSearchSheet?.let { sheet ->
            SubtitleSearchSheet(sheet) { state.dismissSubtitleSearchSheet() }
        }
    }

    LaunchedEffect(state.seekHintVisible, state.seekHintText) {
        if (state.seekHintVisible) {
            delay(1000)
            actions.hideSeekHint()
        }
    }
    LaunchedEffect(state.slideHintVisible, state.slideHintText) {
        if (state.slideHintVisible) {
            delay(1000)
            actions.hideSlideHint()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { actions.refreshSystemInfo() }
                .onFailure { AppLog.e("echo-osd-refresh-failed: $it") }
            delay(1000)
        }
    }
}

private const val PLAYER_DESIGN_WIDTH = 1280f

@Composable
private fun playerMmScale(): Float {
    val container = LocalWindowInfo.current.containerSize
    val portrait = container.height > container.width
    // 横屏按长边缩放是原来的设计；竖屏（尤其短剧全屏 1080x2400）再按长边算，
    // 图标会比屏宽还大。竖屏一律按「实际可见屏宽」缩放——视频 View 为铺满做了左右裁切，
    // 它的宽度可能是 1350，不能直接拿来当 UI 宽度，否则功能键仍会被屏幕边缘切掉。
    val refPx = if (portrait) {
        val screenW = LocalContext.current.resources.displayMetrics.widthPixels
        if (screenW > 0) {
            screenW.toFloat()
        } else {
            val conf = LocalConfiguration.current
            conf.screenWidthDp * LocalDensity.current.density
        }
    } else if (container.width > 0 && container.height > 0) {
        maxOf(container.width, container.height).toFloat()
    } else {
        val conf = LocalConfiguration.current
        maxOf(conf.screenWidthDp, conf.screenHeightDp) * LocalDensity.current.density
    }
    return if (refPx > 0f) refPx / PLAYER_DESIGN_WIDTH else 1f
}

private fun rawMm(resources: Resources, @DimenRes id: Int): Float? {
    val tv = TypedValue()
    try {
        resources.getValue(id, tv, true)
    } catch (e: Resources.NotFoundException) {
        return null
    }
    if (tv.type != TypedValue.TYPE_DIMENSION) return null
    if ((tv.data and TypedValue.COMPLEX_UNIT_MASK) != TypedValue.COMPLEX_UNIT_MM) return null
    return TypedValue.complexToFloat(tv.data)
}

@Composable
internal fun playerDim(@DimenRes id: Int): Dp {
    val res = LocalContext.current.resources
    val raw = rawMm(res, id)
    val px = if (raw != null) raw * playerMmScale() else res.getDimension(id)
    val pxInt = if (px == 0f) 0 else px.roundToInt().coerceAtLeast(1)
    return with(LocalDensity.current) { pxInt.toFloat().toDp() }
}

@Composable
internal fun playerTextSize(@DimenRes id: Int): TextUnit {
    val res = LocalContext.current.resources
    val raw = rawMm(res, id)
    val px = if (raw != null) raw * playerMmScale() else res.getDimension(id)
    val safePx = if (px.isFinite()) px.coerceAtLeast(0f) else 0f
    return with(LocalDensity.current) { safePx.toSp() }
}

@Composable
internal fun playerEdgePadding(): Dp =
    if (LocalConfiguration.current.screenWidthDp >= 600) 48.dp else 16.dp

@Composable
internal fun PlayerMenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    textColor: Color = Color.White,
    @DimenRes textSizeId: Int = R.dimen.ts_20,
) {
    var pressed by remember { mutableStateOf(false) }
    val buttonModifier = modifier
        .pointerInput(onClick, onLongClick) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { onClick() },
                onLongPress = onLongClick?.let { cb -> { cb() } },
            )
        }
        .background(
            if (pressed) Color.White.copy(alpha = 0.16f) else Color.Transparent,
            RoundedCornerShape(playerDim(R.dimen.vs_5)),
        )
        .padding(horizontal = playerDim(R.dimen.vs_10), vertical = playerDim(R.dimen.vs_5))
    Text(
        text = text,
        color = textColor,
        fontSize = playerTextSize(textSizeId),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (pressed) FontWeight.Bold else FontWeight.Medium,
        modifier = buttonModifier,
    )
}

internal const val ICON_TO_BOX_RATIO = 0.55f

private const val PILL_MAX_ICONS = 8

@Composable
internal fun playerIconBox(availableWidth: Dp): Dp {
    val gap = playerDim(R.dimen.vs_8)
    return minOf(
        playerDim(R.dimen.vs_70),
        (availableWidth - gap * (PILL_MAX_ICONS + 1)) / PILL_MAX_ICONS,
    ).coerceAtLeast(1.dp)
}
