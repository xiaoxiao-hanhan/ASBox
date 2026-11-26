@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.aura.asbox.ui.page

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.aura.asbox.R
import com.github.aura.asbox.data.AppGraph
import com.github.aura.asbox.data.EpisodeTotals
import com.github.aura.asbox.data.PlaybackProgress
import com.github.aura.asbox.ui.WindowSize
import com.github.aura.asbox.ui.components.LoadState
import com.github.aura.asbox.ui.components.LoadStateBox
import com.github.aura.asbox.ui.components.ProvideTmdbPosterLookup
import com.github.aura.asbox.ui.components.WeekdayChip
import com.github.aura.asbox.ui.components.WeekdayRes
import com.github.aura.asbox.util.SubscribeList
import kotlinx.coroutines.launch

/**
 * 追剧内容：原底部导航的独立页，现作为记录页的第三个分段（历史 / 收藏 / 追剧）。
 * 编辑、删除所选等顶栏动作由记录页统一承载，状态都在 [FollowingViewModel] 里。
 */
@Composable
internal fun FollowingTab(
    vm: FollowingViewModel,
    navStart: Dp = 0.dp,
    navBottom: Dp = 0.dp,
) {
    val context = LocalContext.current

    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val editMode by vm.editMode.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val episodeTotals by vm.episodeTotals.collectAsStateWithLifecycle()
    val playedPercents by vm.playedPercents.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()

    val listState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val follows = remember(items) { items.map { it.follow } }
    val visible = remember(items, selectedDay) { FollowListRules.visible(items, selectedDay) }
    val dayLabels = WeekdayRes.map { stringResource(it) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(follows) { vm.pruneSelection(follows.map { it.id }.toSet()) }
    LaunchedEffect(selectedDay) { if (visible.isNotEmpty()) listState.scrollToItem(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (items.isNotEmpty()) {
            WeekdayFilterBar(
                topPad = 8.dp,
                navStart = navStart,
                items = items,
                today = today,
                selectedDay = selectedDay,
                onSelectDay = { vm.selectDay(it) },
            )
            val currentDay = selectedDay
            Text(
                text = if (currentDay == null) {
                    stringResource(R.string.following_section_week, visible.size)
                } else {
                    stringResource(R.string.following_section_day, dayLabels[currentDay], visible.size)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp + navStart, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ContainedLoadingIndicator(Modifier.size(64.dp))
            }

            items.isEmpty() -> LoadStateBox(
                state = LoadState.Empty,
                emptyText = stringResource(R.string.following_empty),
                errorText = "",
                retryText = "",
                emptyIconRes = R.drawable.ic_tab_following,
                modifier = Modifier.fillMaxSize(),
            )

            visible.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.following_day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { vm.selectDay(null) }) {
                    Text(stringResource(R.string.following_view_week))
                }
            }

            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = WindowSize.rowColumns((maxWidth - 32.dp - navStart).value.toInt(), 3)
                ProvideTmdbPosterLookup(vm) {
                    LazyVerticalGrid(
                        state = listState,
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp + navStart,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 8.dp + navBottom,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visible, key = { it.follow.id }) { entry ->
                            val follow = entry.follow
                            FollowRow(
                                follow = follow,
                                history = entry.history,
                                totalEpisodes = episodeTotals[EpisodeTotals.key(follow.sourceKey, follow.vodId)],
                                playedPercent = playedPercents[PlaybackProgress.key(follow.sourceKey, follow.vodId)],
                                watched = FollowListRules.isWatched(entry, selectedDay),
                                editMode = editMode,
                                selected = follow.id in selected,
                                selectedDay = selectedDay,
                                today = today,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                ),
                                onClick = {
                                    if (editMode) {
                                        vm.toggleSelected(follow.id)
                                    } else {
                                        val cid = follow.cid.orEmpty()
                                        when {
                                            cid.isEmpty() || cid == AppGraph.followRepository.currentCid() ->
                                                context.jumpToDetail(
                                                    follow.vodId, follow.sourceKey, follow.name, follow.pic,
                                                )

                                            SubscribeList.vodUrls().contains(cid) ->
                                                scope.launch {
                                                    reopenViaSubscription(
                                                        context = context,
                                                        cid = cid,
                                                        sourceKey = follow.sourceKey,
                                                        vodId = follow.vodId,
                                                        name = follow.name,
                                                        pic = follow.pic,
                                                    )
                                                }

                                            else -> context.jumpToSearch(follow.name.orEmpty())
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (editMode) vm.toggleSelected(follow.id) else vm.enterEdit(follow.id)
                                },
                                onWatchedChange = { vm.setWatched(follow.id, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayFilterBar(
    topPad: Dp,
    navStart: Dp,
    items: List<FollowEntry>,
    today: Int,
    selectedDay: Int?,
    onSelectDay: (Int?) -> Unit,
) {
    val counts = remember(items) { FollowListRules.dayCounts(items) }
    val labels = WeekdayRes.map { stringResource(it) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp + navStart, end = 16.dp, top = topPad + 8.dp),
    ) {
        WeekdayChip(
            label = stringResource(R.string.following_week),
            selected = selectedDay == null,
            onClick = { onSelectDay(null) },
            modifier = Modifier.weight(1f),
        )
        labels.forEachIndexed { index, label ->
            WeekdayChip(
                label = label,
                selected = selectedDay == index,
                badgeCount = counts[index] ?: 0,
                todayOutline = index == today,
                onClick = { onSelectDay(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
