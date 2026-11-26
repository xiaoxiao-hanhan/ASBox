package com.github.aura.asbox.ui.page

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.components.AppTopBarScaffold
import com.github.aura.asbox.ui.components.SettingsCard
import com.github.aura.asbox.ui.components.SettingsCardPosition
import com.github.aura.asbox.ui.components.SettingsGroup
import com.github.aura.asbox.ui.components.SettingsOptionMenuRow
import com.github.aura.asbox.ui.components.SettingsRow
import com.github.aura.asbox.ui.components.TopBarActionBox
import java.io.File

@Composable
fun DanmuFontScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val vm: DanmuFontViewModel = viewModel()
    val state by vm.state.collectAsState()
    val toastEvent by vm.toastEvent.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importFont(uri)
    }

    LaunchedEffect(toastEvent) {
        toastEvent?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refresh()
    }

    val familyOptions = listOf(
        stringResource(R.string.danmu_font_family_default),
        stringResource(R.string.danmu_font_family_sans),
        stringResource(R.string.danmu_font_family_serif),
        stringResource(R.string.danmu_font_family_mono),
    )
    val currentLabel = when {
        state.fontPath.isNotEmpty() -> File(state.fontPath).name
        state.familyIndex > 0 -> familyOptions[state.familyIndex]
        else -> stringResource(R.string.danmu_font_family_default)
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.danmu_font_title),
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

            SettingsGroup(title = stringResource(R.string.danmu_font_current)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.danmu_font_system)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.danmu_font_system),
                        leadingIconRes = R.drawable.ic_danmu_font,
                        options = familyOptions,
                        selectedIndex = state.familyIndex,
                        onSelect = { vm.selectFamily(it) },
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.danmu_font_local)) {
                if (state.localFonts.isEmpty()) {
                    SettingsCard(SettingsCardPosition.SINGLE) {
                        Text(
                            text = stringResource(R.string.danmu_font_local_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    state.localFonts.forEachIndexed { index, font ->
                        SettingsCard(
                            when {
                                state.localFonts.size == 1 -> SettingsCardPosition.SINGLE
                                index == 0 -> SettingsCardPosition.FIRST
                                index == state.localFonts.lastIndex -> SettingsCardPosition.LAST
                                else -> SettingsCardPosition.MIDDLE
                            },
                        ) {
                            Column {
                                SettingsRow(
                                    title = font.name,
                                    subtitle = font.path,
                                    valueText = if (state.fontPath == font.path) {
                                        stringResource(R.string.danmu_font_current)
                                    } else {
                                        null
                                    },
                                    onClick = { vm.selectLocal(font) },
                                )
                                Row(Modifier.padding(start = 8.dp)) {
                                    TextButton(onClick = { vm.deleteLocal(font) }) {
                                        Text(
                                            text = stringResource(R.string.danmu_font_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    SettingsRow(
                        title = stringResource(R.string.danmu_font_import),
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.danmu_font_downloadable)) {
                DanmuFontViewModel.PRESETS.forEachIndexed { index, preset ->
                    val downloaded = state.downloaded.contains(preset.fileName)
                    val fontItem = state.localFonts.firstOrNull { it.name == preset.fileName }
                    SettingsCard(
                        when {
                            DanmuFontViewModel.PRESETS.size == 1 -> SettingsCardPosition.SINGLE
                            index == 0 -> SettingsCardPosition.FIRST
                            index == DanmuFontViewModel.PRESETS.lastIndex -> SettingsCardPosition.LAST
                            else -> SettingsCardPosition.MIDDLE
                        },
                    ) {
                        SettingsRow(
                            title = stringResource(preset.nameRes),
                            subtitle = preset.fileName,
                            valueText = when {
                                state.busyFile == preset.fileName ->
                                    stringResource(R.string.local_service_downloading)
                                downloaded -> stringResource(R.string.danmu_font_downloaded)
                                else -> stringResource(R.string.danmu_font_download)
                            },
                            enabled = state.busyFile == null,
                            onClick = {
                                if (downloaded && fontItem != null) {
                                    vm.selectLocal(fontItem)
                                } else {
                                    vm.download(preset)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
