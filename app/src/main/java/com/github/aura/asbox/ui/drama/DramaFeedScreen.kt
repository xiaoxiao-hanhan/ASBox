@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.aura.asbox.ui.drama

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.aura.asbox.R
import com.github.aura.asbox.player.state.PlayerActions
import com.github.aura.asbox.ui.components.AVBoxAlertDialog
import com.github.aura.asbox.ui.page.jumpToSearch
import com.github.aura.asbox.ui.player.CurrentTimeText
import com.github.aura.asbox.ui.player.PlayContainer
import com.github.aura.asbox.ui.player.PlayerSeekRow
import com.github.aura.asbox.ui.player.PreviewPlayPauseButton
import com.github.aura.asbox.util.DanmuSettingsState
import com.github.aura.asbox.util.PlayerUtils.stringForTime
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.abs

private val FeedAccent = Color(0xFFFFD24A)

/**
 * 短剧竖屏信息流播放器（红果风）：
 * 顶部「漫剧 / 真人剧 / 推荐 + 搜索」，左上角圆标换源；画面铺满整屏；
 * 底部是剧名、集数、简介、进度与「点击观看完整剧」。
 *
 * 上下滑只在这个片源的内容里换下一条；换片源走左上角圆标。
 * 「点击观看完整剧」跳到原来的短剧小窗播放器，「全屏观看」跳到原来的短剧全屏播放器。
 */
@Composable
fun DramaFeedScreen(
    vm: DramaFeedViewModel,
    container: PlayContainer?,
    episodesOpen: Boolean,
    onCloseEpisodes: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onEnterDetail: () -> Unit,
    onOpenDramaWindow: () -> Unit,
    onRequestPlay: (DramaFeedViewModel.PlayRequest) -> Unit,
    onPlayerAttached: () -> Unit,
    onToggleDanmaku: () -> Boolean,
) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val index by vm.index.collectAsState()
    val loading by vm.loading.collectAsState()
    val preparing by vm.preparing.collectAsState()
    val empty by vm.empty.collectAsState()
    val tabEmpty by vm.tabEmpty.collectAsState()
    val switching by vm.switching.collectAsState()
    val tab by vm.tab.collectAsState()
    val siteKey by vm.siteKey.collectAsState()
    val sites by vm.sites.collectAsState()
    val info by vm.currentInfo.collectAsState()
    val request by vm.playRequest.collectAsState()

    var showSources by remember { mutableStateOf(false) }
    var danmakuOn by remember { mutableStateOf(DanmuSettingsState.open) }

    val density = LocalDensity.current
    var bottomPanelPx by remember { mutableStateOf(0) }
    // 进度条这一行的 y（相对整页），画面矩形的下沿就贴到它上面一点点。
    var seekBarTopPx by remember { mutableStateOf(0) }
    val seekGapPx = with(density) { 6.dp.toPx() }.toInt()
    // 画面底沿在基准下再往下挪一点点（红线 y≈2084，约 26px ≈ 9dp）。
    val bottomDropPx = with(density) { 9.dp.toPx() }.toInt()

    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(request) { request?.let(onRequestPlay) }
    LaunchedEffect(Unit) {
        vm.message.collect { res ->
            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
        }
    }
    // 当前片源没有这个分类时，会自动去别的片源找；找到了说一声，别让用户以为源没变。
    LaunchedEffect(Unit) {
        vm.notice.collect { name ->
            Toast.makeText(
                context,
                context.getString(R.string.drama_feed_source_switched, name),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    // 手选的源一屏内容都没取到：说清楚，别让用户以为点了个没反应的按钮。
    LaunchedEffect(Unit) {
        vm.switchFailed.collect { name ->
            Toast.makeText(
                context,
                context.getString(R.string.drama_feed_source_failed, name),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    // 分类里确实没内容：把还在播的上一部停掉，别让「没内容」和画面同时出现。
    LaunchedEffect(empty) {
        if (empty) container?.stopForContentSwitch()
    }

    val item = items.getOrNull(index)
    val state = container?.uiState()
    val actions: PlayerActions? = container?.playerActions()
    val title = info?.name?.takeIf { it.isNotBlank() } ?: item?.video?.name.orEmpty()
    val description = info?.des?.takeIf { it.isNotBlank() } ?: item?.video?.des.orEmpty()
    val sessionVod = state?.sessionVod
    val episodeIndex = sessionVod?.playIndex ?: 0
    val episodeName = sessionVod?.seriesMap?.get(sessionVod.playFlag)?.getOrNull(episodeIndex)?.name

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (container != null) {
            // 按用户标红线的位置摆画面：
            //   上沿 = 状态栏下沿 /「推荐」tab 上方一点（红线 y≈122，约屏高 5%）——不再动；
            //   下沿 = 进度条上方一点（红线 y≈2080），画面底部正好贴到这里。
            // 画面顶部对齐上沿、底部贴齐下沿，视频等比放大铺满；宽度撑满屏宽，
            // 左右各裁一丁点、绝不留黑边。顶栏和详情文字浮在画面上。
            val videoAspect = parseVideoAspect(state?.videoSize.orEmpty())
            AndroidView(
                factory = { adoptPlayerView(container).also { onPlayerAttached() } },
                modifier = Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val screenW = constraints.maxWidth
                        val screenH = constraints.maxHeight
                        // 红线框：上沿 = 状态栏下沿/tab 上方（约屏高 5%），下沿贴进度条上方
                        // 再往下一点点（红线 y≈2084）。顶部锚点用「基准下沿」算，保持不动。
                        val frameTop = (screenH * 5f / 100f).toInt().coerceAtLeast(0)
                        val baseBottom = if (seekBarTopPx > 0) {
                            (seekBarTopPx - seekGapPx).coerceIn(frameTop + 1, screenH)
                        } else {
                            screenH
                        }
                        val frameBottom = (baseBottom + bottomDropPx).coerceIn(frameTop + 1, screenH)
                        val boxH = frameBottom - frameTop
                        if (videoAspect in 0.01f..0.99f) {
                            // 竖屏。顶沿用「上一版基准框」里 contain 的真实顶沿算，保持不动；
                            // 底沿 = frameBottom（再往下一点，红线 y≈2084）。视频等比放大
                            // 铺满这段，宽度至少撑满屏宽、左右各裁一丁点，绝不留黑边。
                            val baseVh = (screenW / videoAspect).toInt()
                            val baseBoxH = baseBottom - frameTop
                            val baseBoxAspect = screenW.toFloat() / baseBoxH
                            val oldTop = if (videoAspect > baseBoxAspect) {
                                frameTop + (baseBoxH - baseVh) / 2
                            } else {
                                frameTop
                            }
                            val targetTop = oldTop.coerceAtMost(frameBottom - 1)
                            val vh = frameBottom - targetTop
                            // 宽度至少撑满屏宽：多出来的左右各裁一点，绝不留黑边。
                            val vw = (vh * videoAspect).toInt().coerceAtLeast(screenW)
                            val placeable = measurable.measure(Constraints.fixed(vw, vh))
                            layout(screenW, screenH) {
                                placeable.place((screenW - vw) / 2, targetTop)
                            }
                        } else {
                            // 横屏 / 未知画幅：占满这块矩形，播放器内部按原画幅居中。
                            val placeable = measurable.measure(Constraints.fixed(screenW, boxH))
                            layout(screenW, screenH) {
                                placeable.place(0, frameTop)
                            }
                        }
                    },
            )
        }

        // 画面上的手势层：上下滑换这个片源里的下一条内容，轻点点播/暂停。
        FeedGestureLayer(
            onPrevious = vm::previous,
            onNext = vm::next,
            onTap = { actions?.onPlayPauseClicked() },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部压暗，保证白色标题/图标在亮画面上也看得清。
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent),
                    ),
                ),
        )

        Column(Modifier.fillMaxSize()) {
            FeedTopBar(
                tab = tab,
                onTab = vm::selectTab,
                onSwitchSource = { showSources = true },
                onSearch = { context.jumpToSearch(title, siteKey) },
            )

            Spacer(Modifier.weight(1f))

            // 正在换到用户手选的片源：给个可见的进度，别让人以为点了没反应。
            if (switching) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 1.6.dp,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.drama_feed_switching),
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 12.sp,
                    )
                }
            }

            // 这个分类在本源（以及找过的其它源）没有内容：只在底栏上方挂一条可点的小提示，
            // 正在播的画面和下面的详情保持原样，绝不整页变黑。
            if (tabEmpty && tab != DramaFeedViewModel.Tab.Recommend) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { showSources = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.drama_feed_tab_empty),
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.drama_feed_switch_source),
                        color = FeedAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            FeedBottomPanel(
                title = title,
                description = description,
                episodeLabel = episodeName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.drama_feed_episode, episodeIndex + 1),
                danmakuOn = danmakuOn,
                state = state,
                actions = actions,
                hasContent = item != null,
                onDanmaku = { danmakuOn = onToggleDanmaku() },
                onFullscreen = onEnterFullscreen,
                onEpisode = { actions?.onEpisodeClicked() },
                onEnterDetail = onEnterDetail,
                onWatchFull = onOpenDramaWindow,
                // 只量「集数/简介/进度/按钮」这一截：它对应短剧小窗播放器底部控制条的高度，
                // 画面窗口铺到它的上沿，两个页面的画面就落在同一水平。
                onLowerHeight = { bottomPanelPx = it },
                // 量进度条这一行在整页里的 y：画面矩形的下沿就贴到它上面一点。
                onSeekBarY = { seekBarTopPx = it },
            )
        }

        if ((loading && items.isEmpty()) || preparing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            }
        }

        if (empty && items.isEmpty() && !loading && !preparing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (tab == DramaFeedViewModel.Tab.Recommend) {
                            R.string.drama_feed_empty
                        } else {
                            R.string.drama_feed_tab_empty
                        },
                    ),
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 14.sp,
                )
            }
        }
    }

    if (showSources) {
        SourcePickerDialog(
            // 没 key 的源点了也没法切（会静悄悄没反应），干脆不列出来。
            sites = sites
                .map { it.key.orEmpty() to it.name.orEmpty() }
                .filter { it.first.isNotBlank() },
            current = siteKey,
            onPick = { key ->
                showSources = false
                vm.selectSite(key)
            },
            onDismiss = { showSources = false },
        )
    }

    if (episodesOpen) {
        val dramaSeries = info?.seriesMap?.get(info?.playFlag).orEmpty()
        EpisodePickerDialog(
            episodes = dramaSeries.map { it.name.orEmpty() },
            current = info?.playIndex ?: 0,
            onPick = { position ->
                onCloseEpisodes()
                vm.playEpisode(position)
            },
            onDismiss = onCloseEpisodes,
        )
    }
}

/** 选集：一行一个，最多铺满半屏，当前集高亮。 */
@Composable
private fun EpisodePickerDialog(
    episodes: List<String>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drama_episodes)) },
        text = {
            if (episodes.isEmpty()) {
                Text(stringResource(R.string.drama_feed_play_failed), fontSize = 13.sp)
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    episodes.forEachIndexed { position, name ->
                        val selected = position == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) {
                                        androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { onPick(position) }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name.ifBlank { stringResource(R.string.drama_feed_episode, position + 1) },
                                fontSize = 14.sp,
                                color = if (selected) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun FeedTopBar(
    tab: DramaFeedViewModel.Tab,
    onTab: (DramaFeedViewModel.Tab) -> Unit,
    onSwitchSource: () -> Unit,
    onSearch: () -> Unit,
) {
    // 三段独立摆放：圆标贴左、标签组在正中间、搜索贴右。
    // 之前是一整行 Row 顺排，标签被圆标顶着往左偏，看着不在屏幕中间。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 10.dp),
    ) {
        // 左上角圆标：点一下换源（就是这个圈原来的位置）。
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onSwitchSource),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_brand_logo_round),
                contentDescription = stringResource(R.string.drama_feed_switch_source),
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DramaFeedViewModel.Tab.entries.forEach { entry ->
                val selected = entry == tab
                Column(
                    modifier = Modifier
                        .clickable { onTab(entry) }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(entry.labelRes),
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.62f),
                        fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(if (selected) 18.dp else 0.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) Color.White else Color.Transparent),
                    )
                }
            }
        }

        IconButton(
            onClick = onSearch,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.drama_feed_search),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun FeedBottomPanel(
    title: String,
    description: String,
    episodeLabel: String,
    danmakuOn: Boolean,
    state: com.github.aura.asbox.player.state.PlayerUiState?,
    actions: PlayerActions?,
    hasContent: Boolean,
    onDanmaku: () -> Unit,
    onFullscreen: () -> Unit,
    onEpisode: () -> Unit,
    /** 「进入详情页」：打开详情页（封面/简介/选集/相关推荐那种），不直接起播。 */
    onEnterDetail: () -> Unit,
    /** 「点击观看完整剧」：打开原来的短剧小窗播放器。 */
    onWatchFull: () -> Unit,
    /** 集数/简介/进度/按钮那一截的实际高度，用来把画面窗口对齐小窗播放器。 */
    onLowerHeight: (Int) -> Unit,
    /** 进度条那一行在整页里的 y（px），用来把画面下沿贴到进度条上方。 */
    onSeekBarY: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.66f),
                        Color.Black.copy(alpha = 0.94f),
                    ),
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 28.dp, bottom = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onFullscreen)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_fullscreen),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.drama_watch_fullscreen),
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            DanmakuButton(on = danmakuOn, onClick = onDanmaku)
        }

        Spacer(Modifier.height(8.dp))

        Column(Modifier.fillMaxWidth().onSizeChanged { onLowerHeight(it.height) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = episodeLabel,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = hasContent, onClick = onEpisode)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(FeedAccent.copy(alpha = 0.18f))
                    .border(1.dp, FeedAccent.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable(enabled = hasContent, onClick = onEnterDetail)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.drama_feed_open_detail),
                    color = FeedAccent,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = null,
                    tint = FeedAccent,
                    modifier = Modifier
                        .size(13.dp)
                        .padding(start = 2.dp)
                        .rotate(180f),
                )
            }
        }

        if (description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state != null && actions != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.onGloballyPositioned { onSeekBarY(it.positionInWindow().y.toInt()) },
            ) {
                PreviewPlayPauseButton(state, actions)
                CurrentTimeText(state)
                PlayerSeekRow(
                    state = state,
                    actions = actions,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = stringForTime(state.duration),
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(enabled = hasContent, onClick = onWatchFull),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.drama_feed_watch_full),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "›",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 17.sp,
            )
        }
        }
    }
}

/** 标题右侧的「弹」开关：亮着=弹幕开，点一下临时关掉（再点开回来）。 */
@Composable
private fun DanmakuButton(on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(shape)
            .background(if (on) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.2.dp, Color.White.copy(alpha = if (on) 0.92f else 0.45f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.drama_feed_danmaku_short),
            color = Color.White.copy(alpha = if (on) 1f else 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SourcePickerDialog(
    sites: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        // 标题和列表都居中摆：卡片比较宽，靠左排看起来整块都偏在左边。
        title = {
            Text(
                text = stringResource(R.string.drama_feed_switch_source),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                sites.forEach { (key, name) ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(key) }
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = key == current, onClick = { onPick(key) })
                        Text(
                            text = name,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * 画面手势层：上下滑换一屏（信息流换片源 / 小窗换集），轻点点播/暂停。
 * 阈值与详情页短剧手势一致，可以连续滑。
 */
@Composable
private fun FeedGestureLayer(
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

private fun adoptPlayerView(view: PlayContainer): PlayContainer {
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    return view
}

/** 从 "1080 X 1920"（未知为 "0 X 0"）解析画幅 宽/高，解析不出来返回 0。 */
private fun parseVideoAspect(videoSize: String): Float {
    val parts = videoSize.split("X", "x")
    val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
    val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 0f
}
