package com.leimao.tv.core.data

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 候选分类的条数口径（2026-11-04，「翻两下就没了」的根因）。
 *
 * 抓首屏和往下翻页对「一个类型要几个分类」的诉求是**相反**的：
 *
 * - 首屏只值得试 2 个分类（多试就是多打请求，且第一页内容各分类大同小异）；
 * - 翻页却是**拿分类当环用的** —— 环里有几个分类，就能翻几页新内容。
 *
 * 原来两者共用同一个常量 2，于是类型栏目的环只有两格：第 3 页绕回第一个分类的
 * 第 2 页，而采集站普遍忽略 `pg` 参数，拿回来的还是第 1 页那批 —— 去重后一条不剩，
 * 界面直接停在「没有更多了」。这就是用户说的「随便翻两下就直接没新东西了」。
 */
class VodCandidatesTest {
    private fun movies(count: Int): List<VodCategory> =
        (1..count).map { VodCategory(typeId = "m$it", name = "电影子类$it", type = ContentType.MOVIE) }

    @Test
    fun `抓首屏默认只用两个分类`() {
        val picked = pickVodCandidates(movies(20))

        assertEquals(2, picked.getValue(ContentType.MOVIE).size)
    }

    @Test
    fun `翻页口径能拿到足够多的分类来撑起分类环`() {
        val picked = pickVodCandidates(movies(20), limitPerType = 12)

        assertEquals(12, picked.getValue(ContentType.MOVIE).size)
    }

    @Test
    fun `源里分类不够时按实际数量返回，不补空位`() {
        val picked = pickVodCandidates(movies(3), limitPerType = 12)

        assertEquals(3, picked.getValue(ContentType.MOVIE).size)
    }

    @Test
    fun `分类顺序就是源返回的顺序`() {
        // 顺序即优先级：第一页要用的就是第一个分类，和刷新器抓的那批对得上
        val picked = pickVodCandidates(movies(5), limitPerType = 12)

        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), picked.getValue(ContentType.MOVIE).map { it.typeId })
    }

    @Test
    fun `映射不出类型的分类不进候选`() {
        val categories =
            listOf(
                VodCategory(typeId = "x", name = "福利", type = null),
                VodCategory(typeId = "m1", name = "动作片", type = ContentType.MOVIE),
            )

        val picked = pickVodCandidates(categories, limitPerType = 12)

        assertEquals(listOf("m1"), picked.getValue(ContentType.MOVIE).map { it.typeId })
    }
}
