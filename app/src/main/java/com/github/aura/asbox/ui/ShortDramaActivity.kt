package com.github.aura.asbox.ui

import com.github.aura.asbox.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.base.BaseActivity
import com.github.aura.asbox.ui.components.SheetHostScaffold
import com.github.aura.asbox.ui.page.ShortDramaPage
import com.github.aura.asbox.ui.page.ShortDramaViewModel
import com.github.aura.asbox.ui.theme.AVBoxTheme

/** 短剧页：从主界面直播键长按菜单进入，聚合当前配置里的短剧站点。 */
class ShortDramaActivity : BaseActivity() {

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    val vm: ShortDramaViewModel = viewModel()
                    ShortDramaPage(vm = vm, onBack = { finish() })
                }
            }
        }
    }
}
