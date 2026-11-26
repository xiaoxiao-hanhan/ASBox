package com.github.aura.asbox.ui

import android.content.Context
import android.content.Intent
import com.github.aura.asbox.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.base.BaseActivity
import com.github.aura.asbox.ui.components.SheetHostScaffold
import com.github.aura.asbox.ui.page.RecordsPage
import com.github.aura.asbox.ui.theme.AVBoxTheme

/** 记录页（历史/收藏）：原底部导航页签，现从首页顶栏进入，改为独立 Activity 承载。 */
class RecordsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, RecordsActivity::class.java))
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    RecordsPage()
                }
            }
        }
    }
}
