package com.leimao.tv.core.designsystem.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.leimao.tv.core.designsystem.theme.SAFE_AREA_FRACTION

/**
 * 过扫描安全区：四边各留 ≥5%（RULES §6.3）。
 *
 * 电视有 overscan —— 边缘会被切掉一截，不同盒子切得还不一样多。
 * 用「比例」而不是固定 dp，是为了换到 720p 盒子时仍然留够。
 */
@Composable
fun SafeAreaContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontal = maxWidth * SAFE_AREA_FRACTION
        val vertical = maxHeight * SAFE_AREA_FRACTION
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontal, vertical = vertical),
            content = content,
        )
    }
}
