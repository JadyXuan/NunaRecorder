package com.example.nunarecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private val LightColors = lightColorScheme(
    primary          = NunaPrimary,
    onPrimary        = NunaSurface,
    primaryContainer = NunaPrimaryLight,
    onPrimaryContainer = NunaSurface,
    secondary        = NunaSecondary,
    onSecondary      = NunaSurface,
    secondaryContainer = NunaSecondaryLight,
    background       = NunaBg,
    onBackground     = Color(0xFF1A222C),
    surface          = NunaSurface,
    onSurface        = Color(0xFF1A222C),
    outline          = NunaOutline,
    error            = NunaError,
    onError          = NunaSurface
)

private val DarkColors = darkColorScheme(
    primary          = NunaPrimaryDarkScheme,
    onPrimary        = NunaBgDark,
    primaryContainer = NunaPrimaryDark,
    onPrimaryContainer = NunaPrimaryLight,
    secondary        = NunaSecondaryLight,
    onSecondary      = NunaBgDark,
    background       = NunaBgDark,
    onBackground     = Color(0xFFD6E4EF),
    surface          = NunaSurfaceDark,
    onSurface        = Color(0xFFD6E4EF),
    outline          = NunaOutlineDark,
    error            = NunaError,
    onError          = NunaBgDark
)

/**
 * 修 2026-07-31 佩戴者报告的「白底白字」。
 *
 * 成因不是取错 scheme，也不是被动态取色覆盖（本主题从不调用
 * `dynamicLightColorScheme` / `dynamicDarkColorScheme`），而是**背景和文字来自两个来源**：
 *
 * - 文字颜色来自 Compose。系统处于深色模式时 [isSystemInDarkTheme] 为 true，
 *   于是 `onSurface` = `#D6E4EF`，接近白色。
 * - 背景来自窗口。`MaterialTheme` 本身不画背景，而原来根布局是裸的 `Column`，
 *   没有任何 `Surface`，于是透出 `themes.xml` 里 `android:Theme.Material.Light` 的白色窗口底。
 *
 * 结果就是深色模式下白底 + 近白色文字。浅色模式和 Preview 都复现不了，
 * 所以这是「机型相关」——实际上是「系统深色模式相关」。
 *
 * 修法是让背景和文字必须来自同一个 scheme：根部加一层
 * `Surface(color = colorScheme.background)`。窗口底色另见 `values-night/colors.xml`，
 * 避免启动瞬间闪一下白。
 */
@Composable
fun NunaRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = Typography
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content
        )
    }
}
