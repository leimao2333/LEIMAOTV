package com.leimao.tv.core.model

/**
 * 播放器与列表用的频道。刻意只带「这一屏要用的字段」，
 * 实体（含 sourceId / 请求头配置）留在 :core:database，别让 UI 层拿到用不上的东西。
 *
 * 回看能力（LIVE-24）**刻意不在这里**：它是起播时才要判断的东西，跟着
 * `PlaybackConfig` 一起取回（同一张表、同一次查询），不必让列表里的每个频道都背上它。
 */
data class Channel(
    val id: Long,
    val name: String,
    val groupName: String,
    val logoUrl: String?,
    val streamUrl: String,
    val number: Int,
)
