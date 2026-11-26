package com.github.aura.asbox.ui

import androidx.activity.compose.setContent
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.base.BaseActivity
import com.github.aura.asbox.ui.components.SheetHostScaffold
import com.github.aura.asbox.ui.theme.AVBoxTheme
import com.github.aura.asbox.ui.theme.enableTransparentEdgeToEdge

class SearchActivity : BaseActivity() {

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    SearchScreen()
                }
            }
        }
    }
}
