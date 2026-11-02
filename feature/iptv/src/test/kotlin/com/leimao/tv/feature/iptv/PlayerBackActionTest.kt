package com.leimao.tv.feature.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放页返回键收叠顺序（PLAY-04 / RULES §6.5）。
 *
 * 这里最怕的不是「收错层」，而是「一次全收掉」：用户按了一次返回，节目单、回看、
 * 抽屉同时消失，屏幕直接黑着回首页 —— 用户只会说「返回键把我的台换了」。
 */
class PlayerBackActionTest {
    private fun action(
        track: Boolean = false,
        catchup: Boolean = false,
        epg: Boolean = false,
        drawer: Boolean = false,
        controls: Boolean = false,
    ) = playerBackAction(
        trackPanelOpen = track,
        catchupOpen = catchup,
        epgOpen = epg,
        drawerOpen = drawer,
        controlsVisible = controls,
    )

    @Test
    fun `什么都没开时离开播放页`() {
        assertEquals(PlayerBackAction.LEAVE, action())
    }

    @Test
    fun `控制条还亮着时第一次返回只熄控制条`() {
        assertEquals(PlayerBackAction.HIDE_CONTROLS, action(controls = true))
    }

    @Test
    fun `浮层按层级倒着收`() {
        assertEquals(
            PlayerBackAction.CLOSE_TRACK_PANEL,
            action(track = true, catchup = true, epg = true, drawer = true, controls = true),
        )
        assertEquals(
            PlayerBackAction.CLOSE_CATCHUP,
            action(catchup = true, epg = true, drawer = true, controls = true),
        )
        assertEquals(
            PlayerBackAction.CLOSE_EPG,
            action(epg = true, drawer = true, controls = true),
        )
        assertEquals(
            PlayerBackAction.CLOSE_DRAWER,
            action(drawer = true, controls = true),
        )
    }

    @Test
    fun `逐层收完才离开`() {
        // 全开状态连按返回，实际走过的序列必须是「一层一层收，最后才离开」
        val opened = mutableSetOf("track", "catchup", "epg", "drawer", "controls")
        val seq = mutableListOf<PlayerBackAction>()
        while (true) {
            val next =
                playerBackAction(
                    trackPanelOpen = "track" in opened,
                    catchupOpen = "catchup" in opened,
                    epgOpen = "epg" in opened,
                    drawerOpen = "drawer" in opened,
                    controlsVisible = "controls" in opened,
                )
            seq += next
            when (next) {
                PlayerBackAction.CLOSE_TRACK_PANEL -> opened -= "track"
                PlayerBackAction.CLOSE_CATCHUP -> opened -= "catchup"
                PlayerBackAction.CLOSE_EPG -> opened -= "epg"
                PlayerBackAction.CLOSE_DRAWER -> opened -= "drawer"
                PlayerBackAction.HIDE_CONTROLS -> opened -= "controls"
                PlayerBackAction.LEAVE -> break
            }
        }
        assertEquals(
            listOf(
                PlayerBackAction.CLOSE_TRACK_PANEL,
                PlayerBackAction.CLOSE_CATCHUP,
                PlayerBackAction.CLOSE_EPG,
                PlayerBackAction.CLOSE_DRAWER,
                PlayerBackAction.HIDE_CONTROLS,
                PlayerBackAction.LEAVE,
            ),
            seq,
        )
    }

    @Test
    fun `只开回看时返回先收回看`() {
        assertEquals(PlayerBackAction.CLOSE_CATCHUP, action(catchup = true))
    }
}
