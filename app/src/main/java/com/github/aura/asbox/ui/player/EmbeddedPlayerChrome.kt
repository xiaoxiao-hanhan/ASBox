package com.github.aura.asbox.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.aura.asbox.R
import com.github.aura.asbox.util.PlayerUtils.stringForTime
import com.github.aura.asbox.player.state.PlayerActions
import com.github.aura.asbox.player.state.PlayerUiState

/**
 * 小窗口（嵌入详情页）播放时的顶部导航栏：返回、剧名、收藏。
 * 播放器嵌进详情页后，原本压在 Hero 上的返回/收藏行会被播放器盖住，
 * 这里在页面最上方固定一条同等功能的导航栏。
 */
@Composable
internal fun EmbeddedTopBar(
    title: String,
    subtitle: String = "",
    collected: Boolean,
    onBack: () -> Unit,
    onCollect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 顶栏一律纯黑：之前用的是「黑→透明」的渐变，渐变下半截会透出海报，
    // 顶栏看起来就是一片模糊的彩色（点播偏红、短剧偏棕）。纯黑和下面的画面区连成一片。
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.common_back),
                tint = Color.White,
            )
        }
        // 标题行：剧名 + 集数（原来压在视频下方，现在并到顶栏左上角），
        // 分辨率/画质信息缩到第二行，正好在名字下面。
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onCollect) {
            Icon(
                painter = painterResource(
                    if (collected) R.drawable.ic_tab_collect_filled else R.drawable.ic_tab_collect
                ),
                contentDescription = stringResource(
                    if (collected) R.string.detail_uncollect else R.string.detail_collect
                ),
                tint = if (collected) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
    }
}

/**
 * 小窗口播放时放在视频下方的控制 deck：与全屏同一套功能。
 * 标题/集数/分辨率已移到顶栏，这里第一行只留网速和全屏按钮，下面直接复用全屏的
 * [PlayerBottomBar]（deckMode 下把竖屏预览模式藏起来的功能行也显示出来）。
 */
@Composable
internal fun EmbeddedPlayerDeck(
    state: PlayerUiState,
    actions: PlayerActions,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    showFullscreen: Boolean = true,
    /** 竖屏铺满：整块不铺黑，画面从按钮背后铺到进度条上沿。 */
    transparentTop: Boolean = false,
    /** 回传进度条那一块的高度，画面据此定下沿。 */
    onBottomBlockHeight: ((Int) -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (transparentTop) Modifier else Modifier.background(Color.Black)),
    ) {
        // 竖屏点播小窗：把「全屏」按钮也去掉（横屏小窗保留），
        // 这一行没有内容时整行不占高度，画面能多铺一点。
        val showNetSpeed = state.netSpeedTopRightVisible && state.netSpeedTopRight.isNotBlank()
        if (showFullscreen || showNetSpeed) Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 剧名/集数/分辨率已经上移到顶栏，这里不再重复显示（原位置留空）。
            Spacer(Modifier.weight(1f))
            if (showNetSpeed) {
                Text(
                    text = state.netSpeedTopRight,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = playerTextSize(R.dimen.ts_18),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            if (showFullscreen) {
                Box(
                    Modifier
                        .size(42.dp)
                        .playerPressEffect(onTap = onFullscreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_player_fullscreen),
                        contentDescription = stringResource(R.string.embedded_fullscreen),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
        PlayerBottomBar(
            state = state,
            actions = actions,
            deckMode = true,
            transparentTop = transparentTop,
            onBottomBlockHeight = onBottomBlockHeight,
        )
    }
}

/**
 * 短剧模式顶栏（红果风）：返回 + 当前集标题 + 倍速 + 更多（参数）。
 * 按 han 的要求，短剧播放器里不放收藏（也没有评论/点赞）。
 */
@Composable
internal fun DramaTopBar(
    title: String,
    actions: PlayerActions?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            // 短剧顶栏同样纯黑，不用「黑→透明」渐变，避免透出海报变成一片模糊色。
            .background(Color.Black)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.common_back),
                tint = Color.White,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { actions?.onSpeedClicked() },
            enabled = actions != null,
        ) {
            Text(
                text = stringResource(R.string.drama_speed),
                color = Color.White,
            )
        }
        IconButton(
            onClick = { actions?.onParamsClicked() },
            enabled = actions != null,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = null,
                tint = Color.White,
            )
        }
    }
}

/** 短剧模式视频区下方的「全屏观看」胶囊按钮（红果风），点了进横屏全屏。 */
@Composable
internal fun DramaFullscreenBar(
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            // 底色交给外层的黑色渐变蒙层，这里不再铺一块纯黑，
            // 否则满屏画面下方会出现一条硬边。
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onFullscreen)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_player_fullscreen),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.drama_watch_fullscreen),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}


/**
 * 短剧模式的干净控制台（红果式）：剧名 + 一行进度（播放/暂停、当前时间、进度条、总时长）+ 选集条。
 * 不带点播那一整套按钮（投屏/字幕/弹幕/音轨/上下集等），需要时进全屏里有。
 */
@Composable
fun DramaDeck(state: PlayerUiState, actions: PlayerActions, dramaName: String) {
    Column(
        Modifier
            .fillMaxWidth()
            // 满屏播放时这一层浮在画面上，底色交给外层渐变蒙层。
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (dramaName.isNotBlank()) {
            Text(
                text = dramaName,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PreviewPlayPauseButton(state, actions)
            CurrentTimeText(state)
            PlayerSeekRow(state, actions, Modifier.weight(1f).padding(horizontal = 8.dp))
            Text(
                text = stringForTime(state.duration),
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .clickable { actions.onEpisodeClicked() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.drama_episodes),
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                painter = painterResource(R.drawable.ic_arrow_down),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(180f),
            )
        }
    }
}
