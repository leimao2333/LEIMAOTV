package com.leimao.tv.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/**
 * 窗口底色。
 *
 * 单独提出来是因为它有两个读者：Compose 的色板，和 `MainActivity` 里
 * `window.setBackgroundDrawable()` —— 后者决定冷启动到首帧之间那块颜色。
 * 两处各写一个 0xFF0B0F12，浅色主题下冷启动就会先闪一下深色。
 */
object TvColors {
    val BackgroundDark = Color(0xFF0B0F12)
    val BackgroundLight = Color(0xFFF2F5F7)
}

/**
 * 电视环境默认是暗色：客厅光线、播放画面都得靠深底衬托。
 * 浅色是设置项（SET-02），色板在这里集中定义，页面一行都不用改 —— 页面只认
 * `MaterialTheme.colorScheme`，从没直接引用过具体颜色。
 */
private val TvDarkColors =
    darkColorScheme(
        primary = Color(0xFF7FD6FF),
        onPrimary = Color(0xFF00202E),
        secondary = Color(0xFFB6C8D4),
        onSecondary = Color(0xFF1B2A32),
        background = TvColors.BackgroundDark,
        onBackground = Color(0xFFE6EDF2),
        surface = Color(0xFF151B20),
        onSurface = Color(0xFFE6EDF2),
        surfaceVariant = Color(0xFF1F272D),
        onSurfaceVariant = Color(0xFFB0BEC7),
    )

/**
 * 浅色不是把深色反相：白底上蓝色必须压深才够对比（0xFF7FD6FF 在浅底上几乎看不见），
 * 而 background 用 0xFFF2F5F7 而不是纯白 —— 一整屏纯白在电视上偏刺眼。
 */
private val TvLightColors =
    lightColorScheme(
        primary = Color(0xFF00658C),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF4B5A63),
        onSecondary = Color(0xFFFFFFFF),
        background = TvColors.BackgroundLight,
        onBackground = Color(0xFF0F1A1F),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0F1A1F),
        surfaceVariant = Color(0xFFE4EAEE),
        onSurfaceVariant = Color(0xFF4B5A63),
    )

/**
 * 焦点视觉的三个数必须统一在一个地方（RULES §6.2）：
 * 描边 ≥ 3dp、缩放 1.05 倍、颜色有明确变化 —— 三者至少取其二，本项目全给。
 */
@Immutable
data class TvFocusVisuals(
    val borderWidth: Dp = 3.dp,
    val focusedScale: Float = 1.05f,
    val animationMillis: Int = 120,
    val idleBorderColor: Color = Color.Transparent,
    val shape: RoundedCornerShape = RoundedCornerShape(12.dp),
)

/** 焦点移动 100~150ms（§6.3 动效），太快像跳帧、太慢显得卡 */
val LocalTvFocusVisuals = staticCompositionLocalOf { TvFocusVisuals() }

val LocalTvTextStyles = staticCompositionLocalOf { tvTextStyles() }

/** 安全区四边 ≥5%（§6.3），1080p 下约 48px；用比例而不是绝对值，换个分辨率也不会挤到边 */
const val SAFE_AREA_FRACTION = 0.05f

@Composable
fun TvTheme(
    dark: Boolean = true,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    // 两个参数都参与 remember 的 key：否则改了设置要等下一次重组才生效
    val textStyles = remember(fontScale) { tvTextStyles(fontScale) }

    CompositionLocalProvider(
        LocalTvTextStyles provides textStyles,
        LocalTvFocusVisuals provides TvFocusVisuals(),
    ) {
        MaterialTheme(
            colorScheme = if (dark) TvDarkColors else TvLightColors,
            content = content,
        )
    }
}

/** 页面里拿字号的唯一入口，避免各处 `TextStyle(fontSize = 18.sp)` 各写一套 */
object TvText {
    val current: TvTextStyles
        @Composable get() = LocalTvTextStyles.current
}
