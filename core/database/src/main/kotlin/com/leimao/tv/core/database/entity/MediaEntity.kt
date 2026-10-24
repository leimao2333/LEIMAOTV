package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 影视条目（VOD-03 建表，架构设计 §5 早已规划）。
 *
 * 为什么要落库而不是每次进首页现拉：
 * 1. **首页要立刻有东西看**。冷启动或切到影视板块时不能白屏等 6 个请求，
 *    库里有什么先渲染什么，网络回来再补；
 * 2. **`type` 必须落库**（架构设计 §5 的硬要求）。搜索白名单过滤依赖它 ——
 *    若只在解析时临时判断，缓存命中或换源后就没有这个字段可用了；
 * 3. 年份 / 地区筛选项要能「本类型里有哪些年份 / 地区」，
 *    这只有把条目摊平成表才查得出来。
 *
 * 为什么不存详情（剧集 / 线路）：详情又大又会变，且只在详情页用一次。
 * 落库等于给每个源存一份随时过期的全量副本，收益远小于成本 —— 进详情页现拉。
 */
@Entity(
    tableName = "media",
    indices = [
        // 跨源 vod_id 会撞，只有 (源, 源内 id) 才唯一；也是「同一部片刷新后不重复」的保证
        Index(value = ["sourceId", "sourceKey"], unique = true),
        // 类型分栏 + 「最近更新」排序，一次查询同时用上两列
        Index(value = ["type", "updatedAt"]),
        // 「每源一行」
        Index(value = ["sourceId", "updatedAt"]),
        // 全库「最近更新」（不分类型）
        Index(value = ["updatedAt"]),
    ],
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 来源 `source.id`；源被删时由调用方连带清理 */
    val sourceId: Long,
    /** 源内的 `vod_id`。刷新时靠它与 [sourceId] 一起定位同一条 */
    val sourceKey: String,
    /** [com.leimao.tv.core.model.ContentType] 的 name。存字符串而不存序号：
     * 以后往枚举中间插类型时，序号会整体错位，字符串不会 */
    val type: String,
    val title: String,
    val poster: String? = null,
    val year: Int? = null,
    val area: String? = null,
    /** 源里的更新备注：「更新至20集」「全40集」，首页卡片上要用 */
    val remarks: String? = null,
    /** 源侧更新时间（毫秒）。源没给时间时回落到 [fetchedAt] —— 排序不能因为缺字段就乱 */
    val updatedAt: Long,
    /** 本机入库时间。用于判断「这份缓存多久没刷了」，与内容新旧无关 */
    val fetchedAt: Long,
    /**
     * 题材标签（VOD-08），半角逗号分隔的一串（「动作,科幻」）。
     *
     * 为什么不是一张 `media_tag` 关联表：标签只用来**筛**，不承担分类（分类是 `type`），
     * 建关联表会让每次刷新多出几倍的写入与一次级联清理，而收益只是「能按标签反查全库」——
     * 这件事现在的数据量（几千行）用一次 LIKE 就够了。标签里不会有逗号：
     * 解析时就是按逗号拆开的。
     */
    val tags: String? = null,
)
