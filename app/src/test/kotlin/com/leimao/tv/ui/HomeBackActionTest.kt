package com.leimao.tv.ui

import com.leimao.tv.core.model.AppMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首页返回键分级（RULES §6.4）。
 *
 * 这几条钉的是一个**已经发生过的事故**：早先 BackHandler 无条件弹「退出磊毛TV？」，
 * 用户在设置页按返回就撞上一张只能选「留在应用」的框 —— 返回键等于失效，只能一路
 * 用遥控器找回去。回归一次就是同样的投诉，所以每条分支都要有用例。
 */
class HomeBackActionTest {
    @Test
    fun `默认栏目上按返回才问退出`() {
        assertEquals(
            HomeBackAction.ASK_EXIT,
            homeBackAction(showExitConfirm = false, section = HomeSection.LIVE, mode = AppMode.LIVE),
        )
        assertEquals(
            HomeBackAction.ASK_EXIT,
            homeBackAction(showExitConfirm = false, section = HomeSection.RECOMMEND, mode = AppMode.VOD),
        )
    }

    @Test
    fun `子栏目按返回先退回默认栏目且不弹退出`() {
        // 设置是两种模式共用的子栏目，最容易撞上「按返回弹退出」
        assertEquals(
            HomeBackAction.GO_DEFAULT_SECTION,
            homeBackAction(false, HomeSection.SETTINGS, AppMode.LIVE),
        )
        assertEquals(
            HomeBackAction.GO_DEFAULT_SECTION,
            homeBackAction(false, HomeSection.SETTINGS, AppMode.VOD),
        )
        assertEquals(
            HomeBackAction.GO_DEFAULT_SECTION,
            homeBackAction(false, HomeSection.SEARCH, AppMode.LIVE),
        )
        // 在「电影」按返回要回「推荐」，而不是回「直播」
        assertEquals(
            HomeBackAction.GO_DEFAULT_SECTION,
            homeBackAction(false, HomeSection.MOVIE, AppMode.VOD),
        )
    }

    @Test
    fun `焦点在内容区时返回先回导航栏而不是问退出`() {
        // 2026-11-02 走查：直播页焦点停在首屏大卡上，按返回直接弹「退出磊毛TV？」，
        // 而用户只是想回左边那列导航。返回键是「退一层」，这一层还没退
        assertEquals(
            HomeBackAction.FOCUS_NAV,
            homeBackAction(
                showExitConfirm = false,
                section = HomeSection.LIVE,
                mode = AppMode.LIVE,
                focusInNav = false,
            ),
        )
        assertEquals(
            HomeBackAction.FOCUS_NAV,
            homeBackAction(false, HomeSection.RECOMMEND, AppMode.VOD, focusInNav = false),
        )
        // 子栏目优先：在「设置」里按返回直接回默认栏目，不该先回导航栏再按一次
        assertEquals(
            HomeBackAction.GO_DEFAULT_SECTION,
            homeBackAction(false, HomeSection.SETTINGS, AppMode.LIVE, focusInNav = false),
        )
    }

    @Test
    fun `内容区按返回直接回到正在看的直播画面`() {
        // 用户从播放页退回首页，焦点落在首屏那条「正在直播」上；再按返回应该接着看，
        // 而不是退到左侧导航（对看电视这件事没有意义）
        assertEquals(
            HomeBackAction.RESUME_LIVE,
            homeBackAction(false, HomeSection.LIVE, AppMode.LIVE, focusInNav = false, canResumeLive = true),
        )
        // 焦点已经在导航栏上时绝不能回播放页：否则返回键永远退不出应用
        assertEquals(
            HomeBackAction.ASK_EXIT,
            homeBackAction(false, HomeSection.LIVE, AppMode.LIVE, focusInNav = true, canResumeLive = true),
        )
        // 一台都没看过（首次进入）→ 没有可回的画面，退回导航栏
        assertEquals(
            HomeBackAction.FOCUS_NAV,
            homeBackAction(false, HomeSection.LIVE, AppMode.LIVE, focusInNav = false, canResumeLive = false),
        )
        // 子栏目仍然优先：在「设置」里按返回回栏目，不把人丢进视频
        assertEquals(
            HomeBackAction.GO_DEFAULT_SECTION,
            homeBackAction(false, HomeSection.SETTINGS, AppMode.LIVE, focusInNav = false, canResumeLive = true),
        )
    }

    @Test
    fun `退出确认框开着时再按返回只关框`() {
        val all =
            listOf(
                homeBackAction(true, HomeSection.LIVE, AppMode.LIVE),
                homeBackAction(true, HomeSection.SETTINGS, AppMode.VOD),
                homeBackAction(true, HomeSection.RECOMMEND, AppMode.VOD),
            )
        all.forEach { assertEquals(HomeBackAction.CLOSE_EXIT_CONFIRM, it) }
    }

    @Test
    fun `每个模式的地板是它自己的默认栏目`() {
        assertEquals(HomeSection.LIVE, defaultSection(AppMode.LIVE))
        assertEquals(HomeSection.RECOMMEND, defaultSection(AppMode.VOD))
        // 跨模式的栏目（影视模式下的「直播」）算子栏目，不该直接问退出
        assertEquals(
            HomeBackAction.GO_DEFAULT_SECTION,
            homeBackAction(false, HomeSection.LIVE, AppMode.VOD),
        )
    }
}
