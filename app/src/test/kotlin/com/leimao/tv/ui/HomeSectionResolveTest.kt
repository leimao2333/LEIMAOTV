package com.leimaotv.ui

import com.leimao.tv.core.model.AppMode
import com.leimao.tv.ui.HomeSection
import com.leimao.tv.ui.resolveHomeSection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主页落点解析（HOME-03）。
 *
 * 钉的是一次**入口消失**的回归：把「用户主动点击」与「存档记忆」压成一条
 * `it != SETTINGS` 的过滤之后，导航栏里的「设置」变成了一颗死按钮 ——
 * 点了之后 `section` 回落成默认栏目，焦点收起、屏幕不动，用户以为遥控器坏了。
 */
class HomeSectionResolveTest {
    @Test
    fun `主动点设置就要进设置`() {
        assertEquals(
            HomeSection.SETTINGS,
            resolveHomeSection(picked = HomeSection.SETTINGS.name, saved = null, mode = AppMode.VOD),
        )
    }

    @Test
    fun `主动点的任何栏目都优先于存档`() {
        assertEquals(
            HomeSection.ANIME,
            resolveHomeSection(HomeSection.ANIME.name, HomeSection.MOVIE.name, AppMode.VOD),
        )
    }

    @Test
    fun `存档里的设置不算数 冷启动不该停在开关页`() {
        assertEquals(
            HomeSection.LIVE,
            resolveHomeSection(picked = null, saved = HomeSection.SETTINGS.name, mode = AppMode.LIVE),
        )
        assertEquals(
            HomeSection.RECOMMEND,
            resolveHomeSection(picked = null, saved = HomeSection.SETTINGS.name, mode = AppMode.VOD),
        )
    }

    @Test
    fun `存档里的普通栏目照常恢复`() {
        assertEquals(
            HomeSection.SERIES,
            resolveHomeSection(picked = null, saved = HomeSection.SERIES.name, mode = AppMode.VOD),
        )
    }

    @Test
    fun `什么都没记就落到该模式的默认栏目`() {
        assertEquals(HomeSection.LIVE, resolveHomeSection(null, null, AppMode.LIVE))
        assertEquals(HomeSection.RECOMMEND, resolveHomeSection(null, null, AppMode.VOD))
    }

    @Test
    fun `存档里是认不出的名字时不崩 落默认栏目`() {
        assertEquals(
            HomeSection.RECOMMEND,
            resolveHomeSection(picked = null, saved = "SOMETHING_ELSE", mode = AppMode.VOD),
        )
        assertEquals(
            HomeSection.RECOMMEND,
            resolveHomeSection(picked = "ALSO_UNKNOWN", saved = null, mode = AppMode.VOD),
        )
    }

    @Test
    fun `主动点搜索也能进 搜索不是一次性入口`() {
        assertEquals(
            HomeSection.SEARCH,
            resolveHomeSection(HomeSection.SEARCH.name, null, AppMode.VOD),
        )
    }
}
