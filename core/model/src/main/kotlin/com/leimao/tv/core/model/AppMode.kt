package com.leimao.tv.core.model

/**
 * 两个板块。切换只换内容区，不动播放状态（README「三条设计主线」第 2 条）。
 *
 * 注意：它**不决定启动落点**。启动永远是直播播放页（RULES §6.6），
 * 这里记的只是「从主页进来时用户上次站在哪个板块」（HOME-03）。
 */
enum class AppMode {
    LIVE,
    VOD,
}
