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

/**
 * 电视环境默认是暗色：客厅光线、播放画面都得靠深底衬托。
 * 浅色主题是设置项（SET-02），M0 只落深色，但色板已经在这里集中定义，
 * 之后加浅色只需要多一套 scheme，页面一行都不用改。
 */
private val TvDarkColors =
    darkColorScheme(
        primary = Color(0xFF7FD6FF),
        onPrimary = Color(0xFF00202E),
        secondary = Color(0xFFB6C8D4),
        onSecondary = Color(0xFF1B2A32),
        background = Color(0xFF0B0F12),
        onBackground = Color(0xFFE6EDF2),
        surface = Color(0xFF151B20),
        onSurface = Color(0xFFE6EDF2),
        surfaceVariant = Color(0xFF1F272D),
        onSurfaceVariant = Color(0xFFB0BEC7),
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
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val textStyles = remember(fontScale) { tvTextStyles(fontScale) }

    CompositionLocalProvider(
        LocalTvTextStyles provides textStyles,
        LocalTvFocusVisuals provides TvFocusVisuals(),
    ) {
        MaterialTheme(colorScheme = TvDarkColors, content = content)
    }
}

/** 页面里拿字号的唯一入口，避免各处 `TextStyle(fontSize = 18.sp)` 各写一套 */
object TvText {
    val current: TvTextStyles
        @Composable get() = LocalTvTextStyles.current
}
