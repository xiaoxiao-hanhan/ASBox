package com.github.aura.asbox.ui

import android.content.res.Configuration
import android.os.Build
import com.github.aura.asbox.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.base.BaseActivity
import com.github.aura.asbox.player.PlaybackService
import com.github.aura.asbox.ui.page.MainScreen
import com.github.aura.asbox.ui.theme.AVBoxTheme
import com.github.aura.asbox.ui.theme.AppThemeState
import com.github.aura.asbox.util.PermissionHelper

private const val PREWARM_ON_RESUME_DELAY_MS = 2000L

class MainActivity : BaseActivity() {

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    private fun applyStatusBarAppearance() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val light = !AppThemeState.isDark(night)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
        PlaybackService.prewarm(this, PREWARM_ON_RESUME_DELAY_MS)
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !PermissionHelper.isStorageGranted(this)) {
            PermissionHelper.requestStorage(this) { _, _ -> }
        }
        PermissionHelper.requestNotificationIfNeeded(this)
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                    // 冷启动时盖在首页上的启动画面：白底 + 软件全名，短暂停留后淡出。
                    BrandSplashOverlay()
                }
            }
        }
    }
}
