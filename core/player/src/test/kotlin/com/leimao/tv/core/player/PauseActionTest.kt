package com.leimao.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「按 OK 暂停/继续」该发哪个命令。
 *
 * 这条判定值得单独测，是因为它错的方式很不显眼：暂停之后内核仍然 READY，
 * 状态对象也仍然是 [PlaybackState.Playing]，只有 `paused` 这一位不同。
 * 用类型判断「是否在播」的话，第一次按 OK 暂停、第二次还是 pause —— 遥控器上
 * 表现就是「按了暂停就再也播不动」（2026-11-06 真实反馈）。
 */
class PauseActionTest {
    private val item = PlaybackItem.Episode(sourceId = 1L, sourceKey = "k", index = 0)

    private fun playing(paused: Boolean) =
        PlaybackState.Playing(
            url = "http://example.invalid/a.m3u8",
            item = item,
            lineIndex = 0,
            lineCount = 1,
            paused = paused,
        )

    @Test
    fun `正在播时按 OK 是暂停`() {
        assertEquals(PauseAction.Pause, resolvePauseAction(playing(paused = false)))
    }

    @Test
    fun `暂停之后再按 OK 必须继续`() {
        // 回归点：早先这里也返回 Pause，因为只看类型不看 paused
        assertEquals(PauseAction.Resume, resolvePauseAction(playing(paused = true)))
    }

    @Test
    fun `起播中按 OK 当继续`() {
        // 画面还没出来，压一个 pause 会让起播卡在半路
        assertEquals(
            PauseAction.Resume,
            resolvePauseAction(
                PlaybackState.Preparing(
                    url = "http://example.invalid/a.m3u8",
                    item = item,
                    lineIndex = 0,
                    lineCount = 1,
                ),
            ),
        )
    }

    @Test
    fun `空闲与播完不接管 OK`() {
        assertEquals(PauseAction.Ignore, resolvePauseAction(PlaybackState.Idle))
        assertEquals(
            PauseAction.Ignore,
            resolvePauseAction(
                PlaybackState.Ended(
                    url = "http://example.invalid/a.m3u8",
                    item = item,
                    lineIndex = 0,
                    lineCount = 1,
                ),
            ),
        )
    }
}
