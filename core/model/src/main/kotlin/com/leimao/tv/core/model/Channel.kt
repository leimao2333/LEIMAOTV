package com.leimao.tv.core.model

/**
 * 播放器与列表用的频道。刻意只带「这一屏要用的字段」，
 * 实体（含 sourceId / 请求头配置）留在 :core:database，别让 UI 层拿到用不上的东西。
 */
data class Channel(
    val id: Long,
    val name: String,
    val groupName: String,
    val logoUrl: String?,
    val streamUrl: String,
    val number: Int,
)
