package com.github.aura.asbox.ui.page

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.components.AppTopBarScaffold
import com.github.aura.asbox.ui.components.TopBarActionBox

/**
 * TMDB 独立设置页：内容沿用原配置管理里的 [ConfigTmdbScreen]，
 * 状态与动作仍由 [ConfigManageViewModel] 提供，数据口径不变。
 */
@Composable
fun TmdbSettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ConfigManageViewModel = viewModel(factory = ConfigManageViewModel.defaultFactory())
    val tmdbState by vm.tmdbState().collectAsState()
    val tmdbTesting by vm.tmdbTesting().collectAsState()
    val toastEvent by vm.toastEvent.collectAsState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(toastEvent) {
        toastEvent?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.common_tmdb),
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
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
        ) {
            ConfigTmdbScreen(
                state = tmdbState,
                actions = remember(vm) { vm.tmdbActions() },
                testing = tmdbTesting,
                modifier = Modifier.padding(top = topPad),
            )
        }
    }
}
