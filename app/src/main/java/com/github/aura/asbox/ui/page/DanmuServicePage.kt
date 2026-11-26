package com.github.aura.asbox.ui.page

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.components.AVBoxAlertDialog
import com.github.aura.asbox.ui.components.AppTopBarScaffold
import com.github.aura.asbox.ui.components.SettingsCard
import com.github.aura.asbox.ui.components.SettingsCardPosition
import com.github.aura.asbox.ui.components.SettingsGroup
import com.github.aura.asbox.ui.components.SettingsRow
import com.github.aura.asbox.ui.components.SettingsSwitchRow
import com.github.aura.asbox.ui.components.TopBarActionBox

/**
 * 弹幕服务配置（列表式，照 han 给的样式）：顶部「自动匹配弹幕」开关，
 * 下面是弹幕服务列表（内置默认地址固定一条 + 用户添加的服务），点选即生效，
 * 右上角 + 新增（名称+地址两栏，不要剪贴板按钮），每条带 ⋮ 菜单可编辑/删除。
 */
@Composable
fun DanmuServiceScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val vm: DanmuServiceViewModel = viewModel()
    val state by vm.state.collectAsState()
    val toastEvent by vm.toastEvent.collectAsState()

    var addDialogOpen by rememberSaveable { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<DanmuServiceEntry?>(null) }

    LaunchedEffect(toastEvent) {
        toastEvent?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.danmu_service_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                R.drawable.ic_arrow_left,
                stringResource(R.string.common_back),
                onClick = onNavigateBack,
            )
        },
        actions = {
            TopBarActionBox(
                iconRes = R.drawable.ic_subscribe_add,
                contentDescription = stringResource(R.string.danmu_service_add_title),
                onClick = { addDialogOpen = true },
            )
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Spacer(Modifier.height(topPad - 20.dp))

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.danmu_auto_match),
                        subtitle = stringResource(R.string.danmu_auto_match_sub),
                        checked = state.autoMatch,
                        onCheckedChange = { vm.setAutoMatch(it) },
                    )
                }
            }

            SettingsGroup(title = null) {
                val total = 1 + state.entries.size
                SettingsCard(
                    if (total == 1) SettingsCardPosition.SINGLE else SettingsCardPosition.FIRST,
                ) {
                    DanmuServiceRow(
                        name = stringResource(R.string.danmu_service_builtin_label),
                        active = state.useDefault,
                        onClick = { vm.selectBuiltin() },
                    )
                }
                state.entries.forEachIndexed { index, entry ->
                    SettingsCard(
                        if (index == state.entries.lastIndex) {
                            SettingsCardPosition.LAST
                        } else {
                            SettingsCardPosition.MIDDLE
                        },
                    ) {
                        DanmuServiceRow(
                            name = entry.name,
                            active = !state.useDefault && state.activeUrl == entry.url,
                            onClick = { vm.selectEntry(entry) },
                            onEdit = { editingEntry = entry },
                            onDelete = { vm.deleteEntry(entry) },
                        )
                    }
                }
            }

            if (state.entries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_subscribe_source),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.danmu_service_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.danmu_service_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = stringResource(R.string.danmu_service_add_local),
                        subtitle = stringResource(R.string.danmu_service_local_addr),
                        onClick = { vm.applyLocal() },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.danmu_service_test),
                        enabled = !state.testing,
                        onClick = { vm.test() },
                    )
                }
            }
        }
    }

    if (addDialogOpen) {
        DanmuServiceEditDialog(
            title = stringResource(R.string.danmu_service_add_title),
            initialName = "",
            initialUrl = "",
            onDismiss = { addDialogOpen = false },
            onConfirm = { name, url ->
                vm.addEntry(name, url)
                addDialogOpen = false
            },
        )
    }

    editingEntry?.let { entry ->
        DanmuServiceEditDialog(
            title = stringResource(R.string.danmu_service_edit_title),
            initialName = entry.name,
            initialUrl = entry.url,
            onDismiss = { editingEntry = null },
            onConfirm = { name, url ->
                vm.updateEntry(entry, name, url)
                editingEntry = null
            },
        )
    }
}

@Composable
private fun DanmuServiceRow(
    name: String,
    active: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (active) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.danmu_service_in_use),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (onEdit != null && onDelete != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DanmuServiceEditDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.danmu_service_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.danmu_service_addr_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
