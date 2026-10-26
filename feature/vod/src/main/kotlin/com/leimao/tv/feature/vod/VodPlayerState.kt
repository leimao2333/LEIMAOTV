package com.leimao.tv.feature.vod

import com.leimao.tv.core.player.LoadStats
import com.leimao.tv.core.player.PlaybackState
import com.leimao.tv.core.player.PlaybackTrack
import com.leimao.tv.core.player.TrackKind

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
    /**
     * 当前流里的可选轨道（PLAY-07）。内嵌字幕与多音轨都从这里来。
     *
     * 直播频道几乎总是空表 —— 空表时界面不显示这两个按钮（见 [canPickAudio] / [hasTextTracks]）。
     */
    val tracks: List<PlaybackTrack> = emptyList(),
    /**
     * 加载统计（PLAY-12）：黑屏那几秒在干什么（字节数 / 速率 / 分辨率）。
     *
     * 由 app 层从 `PlayerController.loadStats` 收集后传进来。
     */
    val loadStats: LoadStats = LoadStats.None,
) {
    /**
     * 该不该显示加载浮层。
     *
     * 三种情况：正在准备（拉详情 → 拿地址）、起播中、播放中缓冲。
     * 失败与播完不显示 —— 那两个状态有自己的面板，再叠一层转圈只会让人以为还在等。
     */
    val showLoading: Boolean
        get() =
            preparing ||
                playback is PlaybackState.Preparing ||
                (playback is PlaybackState.Playing && loadStats.buffering)

    /**
     * 浮层标题：准备 / 连接 / 缓冲是三件不同的事，别都写成「加载中」。
     *
     * 连接与缓冲的分界是**有没有收到数据**，不是播放状态 —— 弱源会在
     * READY/BUFFERING 之间抖，按状态判断标题会一秒变好几次。
     */
    val loadingTitle: String
        get() =
            when {
                preparing -> "正在准备…"
                loadStats.bytesLoaded <= 0L -> "正在连接…"
                else -> "正在缓冲…"
            }

    /** 浮层下面那行：有备用线路时先说清正在试第几条，再给加载数据 */
    val loadingDetail: String
        get() {
            val line =
                (playback as? PlaybackState.Preparing)?.let {
                    if (it.lineCount > 1) "第 ${it.lineIndex + 1}/${it.lineCount} 条线路" else null
                }
            return listOfNotNull(line, loadStats.summary()).joinToString(" · ")
        }

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

    val audioTracks: List<PlaybackTrack> get() = tracks.filter { it.kind == TrackKind.AUDIO }

    val textTracks: List<PlaybackTrack> get() = tracks.filter { it.kind == TrackKind.TEXT }

    /**
     * 只有一条音轨时不显示「音轨」按钮。
     *
     * 与 [canSwitchLine] 同一个理由：一个点进去只有一行的菜单，比没有这个按钮更让人
     * 怀疑是不是坏了。绝大多数片子就一条音轨，这个分支是常态而不是例外。
     */
    val canPickAudio: Boolean get() = audioTracks.size > 1

    val hasTextTracks: Boolean get() = textTracks.isNotEmpty()

    /** 有字幕轨但一条都没选中 = 用户关掉了字幕（面板里「关闭字幕」的选中态靠它） */
    val textDisabled: Boolean get() = textTracks.isNotEmpty() && textTracks.none { it.selected }

    /** 顶部副标题用：字幕开着就报个名字，关着就写「字幕关」 */
    val textStatus: String?
        get() =
            when {
                !hasTextTracks -> null
                textDisabled -> "字幕关"
                else -> textTracks.firstOrNull { it.selected }?.label
            }
}
