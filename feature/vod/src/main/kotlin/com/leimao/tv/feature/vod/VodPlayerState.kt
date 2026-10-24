package com.leimao.tv.feature.vod

import com.leimao.tv.core.player.PlaybackState

/**
 * 播放页线路列表里的一条（VOD-10）。
 *
 * 只带「选线路时要看的三样」：名字、集数、下标。地址不进 UI ——
 * 电视上没有「复制链接」这种动作，把地址摆上去只会让一行变得很脏。
 */
data class VodLineOption(
    val index: Int,
    val name: String,
    val episodeCount: Int,
) {
    /** 源没给线路名时兜底成「线路 N」：不能让用户点一个空格子 */
    val label: String get() = name.ifBlank { "线路 ${index + 1}" }
}

/**
 * 影视播放页的展示状态（VOD-05）。
 *
 * 与直播的 `PlayerUiState` 分开而不是复用：两者的「控制条上该有什么」几乎没有交集
 * （直播是换台、分组、收藏、节目单；点播是上一集、下一集、进度、选集）。
 * 合成一个状态类的结果是每个字段都要问一句「这个模式有没有意义」。
 */
data class VodPlayerUiState(
    val title: String = "",
    /** 当前这一集的名字，如「第03集」 */
    val episodeName: String = "",
    val episodeIndex: Int = 0,
    val episodeCount: Int = 0,
    val lineName: String = "",
    val lineIndex: Int = 0,
    val lineCount: Int = 0,
    /** 全部线路，供「线路」面板列出（VOD-10） */
    val lines: List<VodLineOption> = emptyList(),
    val playback: PlaybackState = PlaybackState.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 全剧播完（最后一集的最后一秒也走完了） */
    val finished: Boolean = false,
    /**
     * 正在准备。真机上「拉详情 → 拿到地址 → 起播」之间有一个肉眼可见的空档，
     * 这段时间页面必须说点什么，否则从详情页点进来会先看到两秒纯黑。
     */
    val preparing: Boolean = false,
    /** 需要直接告诉用户的一句话（详情拉不到、地址为空等） */
    val note: String? = null,
    /** 收藏了没有（VOD-07）：控制条上的「收藏 / 已收藏」据此切换 */
    val favorited: Boolean = false,
) {
    /** 有进度才能画进度条。直播或时长未知时为 false */
    val seekable: Boolean get() = durationMs > 0L

    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val hasPrevious: Boolean get() = episodeIndex > 0

    val hasNext: Boolean get() = episodeIndex + 1 < episodeCount

    /**
     * 能不能换线。
     *
     * 只有一条线路时**不显示**这个按钮，而不是显示成灰色：影视源里大量片子只有一条线路，
     * 一个永远点不动的按钮只会让人以为坏了。
     */
    val canSwitchLine: Boolean get() = lines.size > 1
}
