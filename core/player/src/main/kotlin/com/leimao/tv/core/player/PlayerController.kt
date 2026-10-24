package com.leimao.tv.core.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * 播的是什么。
 *
 * 内核**不解读**这个类型，只原样回传 —— 它的唯一用途是让状态自己说明「这次播的是哪一个」，
 * 上层据此决定播完之后该干什么（换台 / 下一集）。做成 sealed 而不是一个 `Long`，
 * 是因为影视的一条片子由「源 + 片 + 集」三样共同确定，压进一个数字就得靠约定编码，
 * 而约定编码出错时不会有任何提示。
 */
sealed interface PlaybackItem {
    /** 直播频道 */
    data class Channel(val channelId: Long) : PlaybackItem

    /** 影视剧集。[index] 是**源里的原始下标**，不是倒序后的位置（VOD-04 的约定） */
    data class Episode(
        val sourceId: Long,
        val sourceKey: String,
        val index: Int,
    ) : PlaybackItem
}

/**
 * 直播还是点播（VOD-05）。
 *
 * 这个区分只影响一件事，但它是致命的：**播放结束怎么处理**。
 * 直播流「播完」意味着断流，该重连该换线；点播「播完」是正常结局，
 * 当失败处理只会让用户看到同一集的最后几秒反复重播。
 */
enum class PlaybackKind {
    LIVE,
    VOD,
}

/**
 * 一次起播请求。
 *
 * 带上**全部线路**而不是单个地址：播放失败时「换下一条线路」是内核自己的职责
 * （PLAY-02 的降级链），让 UI 去拼下一轮的地址，等于把降级逻辑拆散在两层。
 */
data class PlaybackRequest(
    val item: PlaybackItem,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** 至少一条；第一条通常等于 [url] */
    val lines: List<String> = listOf(url),
    val kind: PlaybackKind = PlaybackKind.LIVE,
    /**
     * 从第几毫秒开始播（VOD-06 的「继续观看」）。
     *
     * 交给内核而不是「起播后再 seek」：seek 在还没 prepare 完时会被忽略或落到 0，
     * 用户看到的是从头开始，而他明明点的是「继续」。Media3 的
     * `setMediaSource(source, startPositionMs)` 是唯一在建流时就带位置的入口。
     */
    val startPositionMs: Long = 0L,
) {
    val normalizedLines: List<String> get() = if (lines.isEmpty()) listOf(url) else lines
}

/**
 * 可切换的轨道类别（PLAY-07）。
 *
 * 只有音轨与字幕两类：视频轨在电视盒子上「切换」没有实际意义（同一路流里几乎不会有
 * 两条码率/分辨率不同的视频轨），做出来就是一个永远只有一个选项的菜单。
 */
enum class TrackKind {
    AUDIO,
    TEXT,
}

/**
 * 当前流里的一条可选轨道（PLAY-07）。
 *
 * 内核无关：Media3 的 `Tracks.Group` / `Format` 不出现在这里，换内核（D-02 的 VLC）
 * 时只要能把自家轨道映射成这三个字段，界面一行都不用改。
 *
 * [id] 由内核生成、只在本轮播放内有效 —— UI 拿它**原样回传**选择，不要自己解析。
 * 这么设计是因为选择轨道要能定位到「第几个 track group 的第几条」，而这个映射
 * 只有内核有；让 UI 去猜，等于把 Media3 的内部编号泄漏到界面层。
 */
data class PlaybackTrack(
    val id: String,
    val kind: TrackKind,
    /** 直接显示给用户的一行字，如「中文 · AAC」「English」 */
    val label: String,
    val selected: Boolean,
)

/** 播放状态机。UI 只读它，不直接碰内核（架构设计 §6）。 */
sealed interface PlaybackState {
    /**
     * 线路位置提到接口上，四个状态实现里本来就有这两个字段。
     *
     * 不这么做的话，每个读线路号的界面都要写一遍四分支 `when`，而每加一个状态
     * （比如 VOD-05 的 [Ended]）就得回头把所有 `when` 补一遍 —— 编译器会拦住，但
     * 拦的是「所有用到的地方」，代价是把「加一个状态」变成一次跨模块改动。
     */
    val lineIndex: Int
    val lineCount: Int

    data object Idle : PlaybackState {
        override val lineIndex: Int get() = 0
        override val lineCount: Int get() = 0
    }

    data class Preparing(
        val url: String,
        val item: PlaybackItem,
        override val lineIndex: Int,
        override val lineCount: Int,
    ) : PlaybackState

    data class Playing(
        val url: String,
        val item: PlaybackItem,
        override val lineIndex: Int,
        override val lineCount: Int,
    ) : PlaybackState

    /**
     * 点播播完了（VOD-05）。
     *
     * 单独立一个状态而不是复用 [Failed]：播完是**正常结局**，页面上该说的是
     * 「已播完，接下来看什么」，而失败要提供重试与换线。混在一起的话，每一集播完
     * 用户都会看到一块红色错误框。
     */
    data class Ended(
        val url: String,
        val item: PlaybackItem,
        override val lineIndex: Int,
        override val lineCount: Int,
    ) : PlaybackState

    /**
     * 失败必须带出口信息：降级链走完仍失败时，页面要给「换线路 / 换台 / 去源管理」三个出口
     * （RULES §6.6 实现约束 3：不允许静默退回主页）。
     */
    data class Failed(
        val url: String,
        val item: PlaybackItem,
        val reason: String,
        override val lineIndex: Int,
        override val lineCount: Int,
    ) : PlaybackState {
        val canSwitchLine: Boolean get() = lineCount > 1
    }
}

/**
 * 播放内核的唯一接口（架构设计 §6）。
 *
 * 默认实现是 Media3（PLAY-01）；换内核（比如以后接 VLC）只换实现，UI 与 ViewModel 一行都不改
 * —— 这是本项目最重要的解耦点之一。
 */
interface PlayerController {
    val state: StateFlow<PlaybackState>

    /**
     * 当前流里的音轨 / 字幕轨（PLAY-07）。
     *
     * 空表有两种意思：这条流没有可选轨道（直播大多数如此），或者还没解析到 manifest。
     * UI 不该在空表时显示「音轨」按钮 —— 一个点进去空空的菜单比没有按钮更糟。
     */
    val tracks: StateFlow<List<PlaybackTrack>>

    /** 选中某一条轨道。[trackId] 来自 [tracks]，原样回传即可 */
    fun selectTrack(trackId: String)

    /**
     * 关掉某一类轨道（目前只用于字幕）。
     *
     * 与「选另一条字幕」分开：关字幕是**没有目标的动作**，走 [selectTrack] 就得
     * 造一个假的「空轨道」，而那个假 id 一旦进了 [tracks] 就会出现在菜单里。
     */
    fun disableTracks(kind: TrackKind)

    fun play(request: PlaybackRequest)

    /** 同一条线路重试（错误恢复入口，PLAY-08） */
    fun retry()

    /** 切换到相邻线路；[delta] 为 +1 / -1。返回是否切换成功（只有一条线路时返回 false） */
    fun switchLine(delta: Int): Boolean

    /**
     * 暂停 / 继续（点播用）。
     *
     * 直播页没有这个按钮，但内核必须支持：影视播放用户去倒杯水回来，不该错过剧情。
     * 暂停的是本地渲染与缓冲，不影响上游。
     */
    fun pause()

    fun resume()

    /**
     * 跳到某个位置（点播用）。
     *
     * 放在接口上而不是让 UI 直接摸 Media3 的 `Player.seekTo`：一旦 UI 拿到内核类型，
     * 「换内核不改 UI」这条就破了。直播内核可以忽略这个调用。
     */
    fun seekTo(positionMs: Long)

    /** 当前播放位置（毫秒）。还没起播时为 0 —— UI 用它画进度，不要自己猜。 */
    fun positionMs(): Long

    /** 总时长（毫秒）。直播或时长未知时为 0，UI 据此隐藏进度条。 */
    fun durationMs(): Long

    fun stop()

    fun release()
}

/**
 * 「这块内核能把播放画面挂到 View 上」的标记接口。
 *
 * 为什么单独一个接口：`PlayerController` 要保持内核无关，不能出现 Media3 的 `Player` 类型；
 * 而 PlayerView 偏偏需要它。用标记接口隔离，UI 侧只在自己支持的情况下拿画面。
 */
interface VideoSurfaceHost {
    val videoPlayer: Player
}
