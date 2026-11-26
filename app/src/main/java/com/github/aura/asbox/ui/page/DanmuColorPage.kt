package com.github.aura.asbox.ui.page

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.components.AVBoxAlertDialog
import com.github.aura.asbox.ui.components.AppTopBarScaffold
import com.github.aura.asbox.ui.components.SettingsCard
import com.github.aura.asbox.ui.components.SettingsCardPosition
import com.github.aura.asbox.ui.components.SettingsGroup
import com.github.aura.asbox.ui.components.SettingsSwitchRow
import com.github.aura.asbox.ui.components.TopBarActionBox

private fun parseColorOrNull(hex: String): Color? {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DanmuColorScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val vm: DanmuColorViewModel = viewModel()
    val state by vm.state.collectAsState()
    val toastEvent by vm.toastEvent.collectAsState()

    var addText by rememberSaveable { mutableStateOf("") }
    var gradientStart by rememberSaveable { mutableStateOf("#44CEF6") }
    var gradientEnd by rememberSaveable { mutableStateOf("#FF2D51") }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(toastEvent) {
        toastEvent?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.danmu_color_title),
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
                        title = stringResource(R.string.danmu_color_random_mode),
                        checked = state.randomColor,
                        onCheckedChange = { vm.setRandomColor(it) },
                    )
                }
            }

            // 效果预览
            SettingsGroup(title = stringResource(R.string.danmu_color_preview)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .background(Color(0xFF14181D), RoundedCornerShape(10.dp))
                            .padding(14.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val previewColors = state.palette.take(6)
                            previewColors.forEachIndexed { index, hex ->
                                Text(
                                    text = stringResource(R.string.danmu_color_preview_text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = parseColorOrNull(hex) ?: Color.White,
                                    modifier = Modifier.padding(start = 22.dp * (index % 3)),
                                )
                            }
                        }
                    }
                }
            }

            // 随机色板
            SettingsGroup(
                title = stringResource(R.string.danmu_color_palette) + " · " +
                    stringResource(R.string.danmu_color_count_fmt, state.palette.size),
            ) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    Column(Modifier.padding(16.dp)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.palette.forEach { hex ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .size(16.dp)
                                                .background(
                                                    parseColorOrNull(hex) ?: Color.Transparent,
                                                    RoundedCornerShape(4.dp),
                                                ),
                                        )
                                        Text(
                                            text = hex.uppercase(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                        IconButton(
                                            onClick = { vm.removeColor(hex) },
                                            modifier = Modifier.size(30.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.player_ic_close),
                                                contentDescription = stringResource(R.string.common_clear),
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 14.dp),
                        ) {
                            OutlinedTextField(
                                value = addText,
                                onValueChange = { addText = it },
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.danmu_color_hex_hint)) },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                vm.addColor(addText)
                                addText = ""
                            }) {
                                Text(stringResource(R.string.danmu_color_add))
                            }
                        }
                        Row(modifier = Modifier.padding(top = 6.dp)) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(vm.exportText()))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.danmu_color_exported),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }) {
                                Text(stringResource(R.string.danmu_color_export))
                            }
                            TextButton(onClick = { showImportDialog = true }) {
                                Text(stringResource(R.string.danmu_color_import))
                            }
                            TextButton(onClick = { vm.resetPalette() }) {
                                Text(stringResource(R.string.danmu_color_reset))
                            }
                        }
                    }
                }
            }

            // 渐变色生成
            SettingsGroup(title = stringResource(R.string.danmu_color_gradient)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        parseColorOrNull(gradientStart) ?: Color.Transparent,
                                        RoundedCornerShape(6.dp),
                                    ),
                            )
                            OutlinedTextField(
                                value = gradientStart,
                                onValueChange = { gradientStart = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.danmu_color_gradient_start)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        parseColorOrNull(gradientEnd) ?: Color.Transparent,
                                        RoundedCornerShape(6.dp),
                                    ),
                            )
                            OutlinedTextField(
                                value = gradientEnd,
                                onValueChange = { gradientEnd = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.danmu_color_gradient_end)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        TextButton(
                            onClick = { vm.applyGradient(gradientStart, gradientEnd) },
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Text(stringResource(R.string.danmu_color_gradient_apply))
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        var importText by rememberSaveable { mutableStateOf("") }
        AVBoxAlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.danmu_color_import)) },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholder = { Text(stringResource(R.string.danmu_color_hex_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.importPalette(importText)
                    showImportDialog = false
                }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}
