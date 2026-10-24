package com.leimao.tv.feature.settings

import com.leimao.tv.core.model.appearance.FontScale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字号档位的说明文案（SET-02）。
 *
 * 文案本身没什么可测的，**可测的是它没走样**：四档必须都有话说、百分比必须和真实系数对上。
 * 之前出现过「标着 115% 实际乘 1.1」这类错位，用户按说明判断反而更糊涂。
 */
class FontScaleDescriptionTest {
    @Test
    fun `每一档都有说明`() {
        FontScale.entries.forEach { option ->
            assertTrue(option.name, fontScaleDescription(option).isNotBlank())
        }
    }

    @Test
    fun `百分比与真实系数一致`() {
        assertEquals2("整体缩到 90%", fontScaleDescription(FontScale.SMALL))
        assertEquals2("整体放大到 115%", fontScaleDescription(FontScale.LARGE))
        assertEquals2("整体放大到 130%", fontScaleDescription(FontScale.HUGE))
    }

    @Test
    fun `标准档不写百分比`() {
        // 「默认大小」比「100%」更像人话：没人会拿 100% 去对照什么
        assertFalse(fontScaleDescription(FontScale.STANDARD).contains("%"))
    }

    private fun assertEquals2(
        prefix: String,
        actual: String,
    ) {
        assertTrue("期望以「$prefix」开头，实际是「$actual」", actual.startsWith(prefix))
    }
}
