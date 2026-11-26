@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.aura.asbox.ui.page

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.aura.asbox.R
import com.github.aura.asbox.bean.Movie
import com.github.aura.asbox.ui.components.VodCard

/**
 * 短剧页：顶部先选站点（「全部」=聚合推荐，一站一段三列网格），
 * 选了某个站点后出现该站的分类标签，点分类按页浏览该分类的短剧。
 * 点任意一部进点播详情页，播放走点播播放器（短剧模式：竖屏页、高视频区、无收藏）。
 */
@Composable
fun ShortDramaPage(vm: ShortDramaViewModel, onBack: () -> Unit) {
    val sections by vm.sections.collectAsState()
    val loading by vm.loading.collectAsState()
    val sites by vm.sites.collectAsState()
    val selectedSiteKey by vm.selectedSiteKey.collectAsState()
    val categories by vm.categories.collectAsState()
    val selectedCategoryId by vm.selectedCategoryId.collectAsState()
    val browseVideos by vm.browseVideos.collectAsState()
    val browseLoading by vm.browseLoading.collectAsState()
    val browseEnded by vm.browseEnded.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Text(
                text = stringResource(R.string.short_drama_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (sites.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    DramaChip(
                        text = stringResource(R.string.short_drama_all),
                        selected = selectedSiteKey == null,
                        onClick = { vm.selectSite(null) },
                    )
                }
                items(sites) { site ->
                    DramaChip(
                        text = site.name.orEmpty(),
                        selected = selectedSiteKey == site.key,
                        onClick = { vm.selectSite(site) },
                    )
                }
            }
        }

        if (selectedSiteKey != null && categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(categories) { cat ->
                    DramaChip(
                        text = cat.name.orEmpty(),
                        selected = selectedCategoryId == cat.id,
                        onClick = { vm.selectCategory(cat) },
                    )
                }
            }
        }

        if (selectedSiteKey == null) {
            when {
                sections.isEmpty() && loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(48.dp))
                }

                sections.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.short_drama_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    sections.forEach { section ->
                        item {
                            Text(
                                text = section.source.name.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
                            )
                        }
                        items(section.videos.chunked(3)) { rowVideos ->
                            DramaGridRow(rowVideos) { video ->
                                context.jumpToDetail(
                                    video.id,
                                    section.source.key,
                                    video.name,
                                    video.pic,
                                    shortDrama = true,
                                )
                            }
                        }
                    }
                    if (loading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                ContainedLoadingIndicator(Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        } else {
            when {
                browseVideos.isEmpty() && browseLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(48.dp))
                }

                browseVideos.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.short_drama_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                ) {
                    items(browseVideos.chunked(3)) { rowVideos ->
                        DramaGridRow(rowVideos) { video ->
                            context.jumpToDetail(
                                video.id,
                                selectedSiteKey,
                                video.name,
                                video.pic,
                                shortDrama = true,
                            )
                        }
                    }
                    if (browseLoading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                ContainedLoadingIndicator(Modifier.size(28.dp))
                            }
                        }
                    } else if (!browseEnded) {
                        item {
                            LaunchedEffect(Unit) { vm.loadMore() }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DramaChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DramaGridRow(rowVideos: List<Movie.Video>, onClick: (Movie.Video) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rowVideos.forEach { video ->
            VodCard(
                video = video,
                onClick = { onClick(video) },
                onLongClick = {},
                modifier = Modifier.weight(1f),
            )
        }
        repeat(3 - rowVideos.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}
