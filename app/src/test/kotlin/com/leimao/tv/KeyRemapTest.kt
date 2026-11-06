package com.leimao.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 小键盘九宫格 → 遥控器键的映射（RULES §6.5）。
 *
 * ```
 *   8 上
 * 4 左 5 OK 6 右
 *   2 下      1 返回   3 菜单
 * ```
 *
 * 键码用数值字面量写死，不引 `KeyEvent.KEYCODE_*`：一来测试不依赖 android.jar 的常量
 * 能不能在纯 JVM 上读到，二来防止有人把「小键盘 5」改成了别的键，测试还跟着一起错。
 */
class KeyRemapTest {
    @Test
    fun `九宫格四个方向`() {
        assertEquals(DPAD_UP, KeyRemap.targetOf(NUMPAD_8))
        assertEquals(DPAD_DOWN, KeyRemap.targetOf(NUMPAD_2))
        assertEquals(DPAD_LEFT, KeyRemap.targetOf(NUMPAD_4))
        assertEquals(DPAD_RIGHT, KeyRemap.targetOf(NUMPAD_6))
    }

    @Test
    fun `正中那颗 5 和小键盘回车都是 OK`() {
        assertEquals(DPAD_CENTER, KeyRemap.targetOf(NUMPAD_5))
        assertEquals(DPAD_CENTER, KeyRemap.targetOf(NUMPAD_ENTER))
    }

    @Test
    fun `1 和 ESC 都是返回`() {
        assertEquals(BACK, KeyRemap.targetOf(NUMPAD_1))
        assertEquals(BACK, KeyRemap.targetOf(ESCAPE))
    }

    @Test
    fun `3 是菜单`() {
        assertEquals(MENU, KeyRemap.targetOf(NUMPAD_3))
    }

    @Test
    fun `不参与布局的键原样放行`() {
        // 7 / 9 / 0 不在九宫格里 —— 输入订阅地址、频道号时还得靠它们
        assertNull(KeyRemap.targetOf(NUMPAD_7))
        assertNull(KeyRemap.targetOf(NUMPAD_9))
        assertNull(KeyRemap.targetOf(NUMPAD_0))
        assertNull(KeyRemap.targetOf(KEY_A))
    }

    private companion object {
        const val NUMPAD_0 = 144
        const val NUMPAD_1 = 145
        const val NUMPAD_2 = 146
        const val NUMPAD_3 = 147
        const val NUMPAD_4 = 148
        const val NUMPAD_5 = 149
        const val NUMPAD_6 = 150
        const val NUMPAD_7 = 151
        const val NUMPAD_8 = 152
        const val NUMPAD_9 = 153
        const val NUMPAD_ENTER = 160
        const val ESCAPE = 111
        const val KEY_A = 29

        const val BACK = 4
        const val DPAD_UP = 19
        const val DPAD_DOWN = 20
        const val DPAD_LEFT = 21
        const val DPAD_RIGHT = 22
        const val DPAD_CENTER = 23
        const val MENU = 82
    }
}
