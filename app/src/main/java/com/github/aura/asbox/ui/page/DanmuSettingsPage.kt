package com.github.aura.asbox.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.DanmuColorActivity
import com.github.aura.asbox.ui.DanmuFontActivity
import com.github.aura.asbox.ui.DanmuServiceActivity
import com.github.aura.asbox.ui.components.AppTopBarScaffold
import com.github.aura.asbox.ui.components.SettingsCard
import com.github.aura.asbox.ui.components.SettingsCardPosition
import com.github.aura.asbox.ui.components.SettingsGroup
import com.github.aura.asbox.ui.components.SettingsRow
import com.github.aura.asbox.ui.components.TopBarActionBox
import com.github.aura.asbox.util.DanmuSettingsState
import com.github.aura.asbox.util.KV
import com.github.aura.asbox.util.KVKeys
import java.io.File

@Composable
fun DanmuSettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var useDefault by remember { mutableStateOf(KV.get(KVKeys.DANMU_API_USE_DEFAULT, false)) }
    var fontPath by remember { mutableStateOf(KV.get(KVKeys.DANMU_FONT_PATH, "")) }
    var fontFamily by remember { mutableStateOf(KV.get(KVKeys.DANMU_FONT_FAMILY, "")) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        useDefault = KV.get(KVKeys.DANMU_API_USE_DEFAULT, false)
        fontPath = KV.get(KVKeys.DANMU_FONT_PATH, "")
        fontFamily = KV.get(KVKeys.DANMU_FONT_FAMILY, "")
    }

    val danmuConfig = DanmuSettingsState.config
    val paletteCount = DanmuSettingsState.palette().size

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.danmu_settings),
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
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = stringResource(R.string.danmu_service_title),
                        subtitle = stringResource(R.string.danmu_service_subtitle),
                        iconRes = R.drawable.ic_pref_danmu_api,
                        valueText = when {
                            useDefault -> stringResource(R.string.danmu_service_builtin_label)
                            danmuConfig.api.isNotEmpty() -> stringResource(R.string.common_set)
                            else -> stringResource(R.string.danmu_service_from_config)
                        },
                        onClick = { DanmuServiceActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.danmu_color_title),
                        subtitle = stringResource(R.string.danmu_color_subtitle),
                        iconRes = R.drawable.ic_danmu_color,
                        valueText = stringResource(R.string.danmu_color_count_fmt, paletteCount),
                        onClick = { DanmuColorActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = stringResource(R.string.danmu_font_title),
                        subtitle = stringResource(R.string.danmu_font_subtitle),
                        iconRes = R.drawable.ic_danmu_font,
                        valueText = when {
                            fontPath.isNotEmpty() -> File(fontPath).name
                            fontFamily.isNotEmpty() -> fontFamily
                            else -> stringResource(R.string.danmu_font_family_default)
                        },
                        onClick = { DanmuFontActivity.start(context) },
                    )
                }
            }
        }
    }
}
