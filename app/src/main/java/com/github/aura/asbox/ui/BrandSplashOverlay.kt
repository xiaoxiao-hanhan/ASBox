package com.github.aura.asbox.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.github.aura.asbox.R
import kotlinx.coroutines.delay

/** 启动画面停留时长（毫秒）与淡出时长（毫秒）。 */
private const val SplashHoldMs = 700L
private const val SplashFadeMs = 300

/**
 * 启动画面：纯白底 + 软件全名（与「我的」页面软件信息卡上的名字一致）。
 * 不再画应用图标；文字颜色沿用原来的深色，不做反色处理。
 */
@Composable
internal fun BrandSplashOverlay(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(SplashHoldMs)
        alpha.animateTo(0f, tween(SplashFadeMs, easing = LinearOutSlowInEasing))
        visible = false
    }
    if (!visible) return
    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha.value)
            .background(colorResource(R.color.splash_background)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "AuraShadeBox",
            color = colorResource(R.color.splash_brand_text),
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center,
        )
    }
}
