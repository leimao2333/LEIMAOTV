package com.leimao.tv.ui

import androidx.compose.ui.input.key.Key
import com.leimao.tv.ui.ExitConfirmTarget.EXIT
import com.leimao.tv.ui.ExitConfirmTarget.STAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 退出确认弹层里方向键的去向（2026-11-02）。
 *
 * 这一条对应一个真实故障：弹层从 `Dialog` 窗口改成树内覆盖层之后，**窗口不再帮它挡焦点**，
 * 方向键会把焦点拽到底下的导航栏上 —— 屏幕上弹层还画着，焦点却已经跑到「设置」上，
 * 再按 OK 的结果和用户看到的完全不一致。锁焦点的办法是在预览阶段截住方向键，
 * 自己把焦点钉在两个按钮之间；「哪个键交给谁」就是这里的纯函数。
 *
 * 所以这里钉的是两件事：
 * 1. 四个方向都有明确去处，**没有一个方向会是 null**（漏一个就等于开了个洞）；
 * 2. 非方向键（OK / BACK / 数字键）必须放行 —— 拦了就按不动按钮、也退不出去。
 */
class ExitConfirmTargetTest {
    @Test
    fun `左右上下都被钉在两个按钮之间`() {
        assertEquals(STAY, exitConfirmTarget(Key.DirectionLeft))
        assertEquals(STAY, exitConfirmTarget(Key.DirectionUp))
        assertEquals(EXIT, exitConfirmTarget(Key.DirectionRight))
        assertEquals(EXIT, exitConfirmTarget(Key.DirectionDown))
    }

    @Test
    fun `确认键必须放行`() {
        // 拦了 OK，两个按钮就永远是画着好看的
        assertNull(exitConfirmTarget(Key.DirectionCenter))
        assertNull(exitConfirmTarget(Key.Enter))
        assertNull(exitConfirmTarget(Key.NumPadEnter))
    }

    @Test
    fun `返回键必须放行`() {
        // 拦了 BACK，用户就退不出这个弹层 —— 只能选一个「退出」或「留在应用」
        assertNull(exitConfirmTarget(Key.Back))
        assertNull(exitConfirmTarget(Key.Escape))
    }

    @Test
    fun `别的按键一概放行`() {
        assertNull(exitConfirmTarget(Key.Menu))
        assertNull(exitConfirmTarget(Key.MediaPlayPause))
        assertNull(exitConfirmTarget(Key.Zero))
    }
}
