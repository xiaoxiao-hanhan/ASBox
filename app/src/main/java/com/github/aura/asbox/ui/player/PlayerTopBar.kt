package com.github.aura.asbox.ui.player

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.aura.asbox.R
import com.github.aura.asbox.player.state.PlayerActions
import com.github.aura.asbox.player.state.PlayerUiState

private val TopBarLineHeight = 36.dp

private val PreviewTopPadding = 4.dp

private val FullscreenTopPadding = 12.dp

private val TopBarSysInfoMinHeight = 16.dp

private val TopBarBatteryIconSize = 16.dp

internal val TopBarActionSize = 42.dp

internal val TopBarActionIconSize = 28.dp

@Composable
internal fun PlayerActionIcons(
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    size: Dp = playerDim(R.dimen.vs_60),
    iconSize: Dp = playerDim(R.dimen.vs_40),
    spread: Boolean = false,
) {
    Row(
        modifier,
        horizontalArrangement = if (spread) Arrangement.Start else Arrangement.spacedBy(playerDim(R.dimen.vs_8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerActionIconButton(
            iconRes = R.drawable.ic_detail_cast,
            contentDescription = stringResource(R.string.common_cast),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onCastClicked,
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_subtitle,
            contentDescription = stringResource(R.string.player_menu_subtitle),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onSubtitleClicked,
            onLongClick = actions::onSubtitleLongClicked,
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_danmu,
            contentDescription = stringResource(R.string.player_menu_danmu),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onDanmuSettingClicked,
            onLongClick = actions::onDanmuSettingLongClicked,
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_audio,
            contentDescription = stringResource(R.string.player_menu_audio_track),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onAudioTrackClicked,
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_video,
            contentDescription = stringResource(R.string.player_menu_video_track),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onVideoTrackClicked,
        )
    }
}

@Composable
private fun RowScope.PlayerActionIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    box: Dp,
    iconSize: Dp,
    expand: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        if (expand) Modifier.weight(1f).height(box) else Modifier.size(box),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(iconSize)
                .playerPressEffect(onTap = onClick, onLongClick = onLongClick),
        )
    }
}

internal const val TOP_BAR_ROOT_TAG = "playerTopBarRoot"

internal const val TOP_BAR_BACK_TAG = "playerTopBarBack"

@Composable
fun PlayerTopBar(state: PlayerUiState, actions: PlayerActions) {
    val configuration = LocalConfiguration.current
    val portraitActionsStyle = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val showActionsInTopBar = !portraitActionsStyle
    val rightVisible = state.topRightVisible && !state.previewMode
    val edge = playerEdgePadding()
    val topPad = if (state.previewMode) PreviewTopPadding else FullscreenTopPadding
    val batteryIconSize = if (configuration.smallestScreenWidthDp >= 600) {
        playerDim(R.dimen.vs_24)
    } else {
        TopBarBatteryIconSize
    }
    val density = LocalDensity.current
    val topInset = if (LocalConfiguration.current.screenWidthDp >= 600) {
        WindowInsets.displayCutout
    } else {
        WindowInsets.safeDrawing
    }
    val topInsetPx = topInset.getTop(density)
    var barTopPx by remember { mutableStateOf(Float.NaN) }
    val extraTop = if (barTopPx.isNaN()) {
        0.dp
    } else {
        with(density) { (topInsetPx - barTopPx).coerceIn(0f, topInsetPx.toFloat()).toDp() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .testTag(TOP_BAR_ROOT_TAG)
            .onGloballyPositioned {
                barTopPx = it.positionInWindow().y
            }
    ) {
        Box {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(playerDim(R.dimen.vs_140))
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.65f),
                                0.45f to Color.Black.copy(alpha = 0.28f),
                                1f to Color.Transparent,
                            )
                        )
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = edge,
                            end = edge,
                            top = topPad + extraTop,
                            bottom = playerDim(R.dimen.vs_5),
                        ),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (state.topLeftVisible) {
                    var titleHeightPx by remember { mutableStateOf(0f) }
                    // X 与第一行标题对齐，而不是与「标题+分辨率」整个两行块对齐。
                    // 标题高度实测后算出偏移，跨机型/字体缩放都能保持同一水平线。
                    val backBoxHeightPx = with(density) { TopBarLineHeight.toPx() }
                    val backOffsetY = if (titleHeightPx > 0f) {
                        with(density) { ((titleHeightPx - backBoxHeightPx) / 2f).toDp() }
                    } else {
                        0.dp
                    }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .size(TopBarLineHeight)
                                .offset(y = backOffsetY)
                                .playerPressEffect(onTap = { actions.onBackClicked() }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.player_ic_close),
                                contentDescription = stringResource(R.string.common_back),
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag(TOP_BAR_BACK_TAG),
                            )
                        }
                        Column(
                            modifier = Modifier.padding(start = playerDim(R.dimen.vs_10)),
                        ) {
                            Text(
                                text = state.title,
                                color = Color.White,
                                // 竖屏剧名放大一档（26 -> 32）；横屏按长边换算本就偏大，
                                // 回落到 24，避免片名撑满半屏。
                                fontSize = playerTextSize(
                                    if (portraitActionsStyle) R.dimen.ts_32 else R.dimen.ts_24,
                                ),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.onGloballyPositioned {
                                    titleHeightPx = it.size.height.toFloat()
                                },
                            )
                            val sizeLine = if (state.videoQuality.isBlank()) {
                                state.videoSize
                            } else {
                                state.videoSize + " · " + state.videoQuality
                            }
                            if (sizeLine.isNotBlank()) {
                                Text(
                                    text = sizeLine,
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = playerTextSize(
                                        if (portraitActionsStyle) R.dimen.ts_18 else R.dimen.ts_16,
                                    ),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // 分辨率行整体上移一点，贴近标题。
                                    modifier = Modifier.offset(y = -6.dp),
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (rightVisible) {
                    Column(horizontalAlignment = Alignment.End) {
                        // 右上角电量/时间信息已删除（用户标记位置）；横屏功能键仍保留在这里。
                        if (showActionsInTopBar) {
                            // 横屏按长边换算，默认 60/40mm 图标偏大，回落到 42/28mm。
                            PlayerActionIcons(
                                actions = actions,
                                size = playerDim(R.dimen.vs_40),
                                iconSize = playerDim(R.dimen.vs_24),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun batteryIcon(state: PlayerUiState): Int = when {
    state.batteryCharging -> R.drawable.ic_battery_charging
    state.batteryPercent <= 16 -> R.drawable.ic_battery_1
    state.batteryPercent <= 33 -> R.drawable.ic_battery_2
    state.batteryPercent <= 50 -> R.drawable.ic_battery_3
    state.batteryPercent <= 66 -> R.drawable.ic_battery_4
    state.batteryPercent <= 83 -> R.drawable.ic_battery_5
    else -> R.drawable.ic_battery_6
}
