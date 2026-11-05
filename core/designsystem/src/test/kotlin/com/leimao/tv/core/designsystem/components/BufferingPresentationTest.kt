package com.leimao.tv.core.designsystem.components

import com.leimao.tv.core.designsystem.components.BufferingPresentation.Full
import com.leimao.tv.core.designsystem.components.BufferingPresentation.None
import com.leimao.tv.core.designsystem.components.BufferingPresentation.Pill
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「缓冲时该给什么提示」的判定（PLAY-12，2026-11-05 用户反馈）。
 *
 * 背景：原来只要「内核在 buffering」就弹正中那张 0xCC 的大卡片，于是**播放中途**
 * 网速抖一下，画面正中被一张黑卡盖住 —— 用户截图里的原话是「还有这种情况出现」。
 * 盖住的那几百毫秒里，既看不见剧情，也看不见刚才演到哪。
 *
 * 现在按「画面出过没有」分流：没出过（起播 / 换台 / 换集 / 换线）给完整浮层，
 * 出过之后再卡只给一行小字。这组用例把分流规则钉住，免得下次又退回去。
 */
class BufferingPresentationTest {
    @Test
    fun `没在加载时什么都不显示`() {
        assertEquals(None, resolveBufferingPresentation(showLoading = false, preparing = false, playedOnce = false))
        // 画面早出过、现在也没卡：同样什么都不显示
        assertEquals(None, resolveBufferingPresentation(showLoading = false, preparing = false, playedOnce = true))
    }

    @Test
    fun `起播时给完整浮层`() {
        // 一次都没播起来、内核还在准备：用户看的是黑屏，需要「在等什么、下了多少」
        assertEquals(Full, resolveBufferingPresentation(showLoading = true, preparing = true, playedOnce = false))
        // 已经进入 buffering 但还没出过画面（首帧前的那一次缓冲）
        assertEquals(Full, resolveBufferingPresentation(showLoading = true, preparing = false, playedOnce = false))
    }

    @Test
    fun `播放中途卡顿只给角标`() {
        // 这是本次修复的核心：出过画面之后再卡，不能再盖一张大卡片
        assertEquals(Pill, resolveBufferingPresentation(showLoading = true, preparing = false, playedOnce = true))
    }

    @Test
    fun `换台换集回到准备态时退回完整浮层`() {
        // playedOnce 是按会话记的，重试同一集时它可能还留着 true；
        // 只要内核回到准备态，就是一次新的起播 —— 该给信息，而不是「好像只是卡了一下」
        assertEquals(Full, resolveBufferingPresentation(showLoading = true, preparing = true, playedOnce = true))
    }
}
