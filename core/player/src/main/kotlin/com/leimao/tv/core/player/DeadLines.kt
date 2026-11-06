package com.leimao.tv.core.player

/**
 * 本进程里「一个字节都没回」的线路（2026-11-06）。
 *
 * ## 解决什么问题
 *
 * 用户的原话：「加载方面无响应的源就不用费时间了，**每次加载半天无响应的还是无响应**」。
 *
 * 聚合源里同一个台挂着十几条线路，其中一半是死链 —— 连接能建立、响应头永远不来。
 * 播放链路已经会在首字节超时后换下一条，但**这份记忆只活在一次起播里**：
 * 换台再切回来、或者换到另一个台又撞上同一个中转站，还是要一条一条重新等一遍。
 *
 * ## 为什么放在进程级而不是落库
 *
 * 落库（`channel_line_quality`）要经过「播放链路 → UI → 仓库」一圈回调，为了一个
 * 会话级的去重把三层都改一遍不划算；而「这条地址现在不回包」本来就是**时效性**信息，
 * 服务端半夜恢复了还记着反而是错的。进程级 = 活到 App 退出，正合适。
 *
 * ## 只是「降级」不是「拉黑」
 *
 * 跳过只发生在**还有别的候选**的时候（见 `Media3PlayerController.firstLiveLineIndex`）：
 * 全都判死过就照原顺序一条条再试。所以这里记错一次（服务器当时确实在抽风）也不会
 * 让一个台彻底不可用。
 */
internal object DeadLines {
    /** 上限 256：一个台的线路再多也不会到百条，超了按最早记的淘汰 */
    private const val MAX = 256

    private val urls = LinkedHashSet<String>()

    @Synchronized
    fun mark(url: String) {
        if (url.isEmpty()) return
        urls.add(url)
        while (urls.size > MAX) {
            val oldest = urls.first()
            urls.remove(oldest)
        }
    }

    @Synchronized
    fun isDead(url: String): Boolean = url in urls

    /** 测试用：把记忆清干净，避免用例之间互相污染 */
    @Synchronized
    fun clear() {
        urls.clear()
    }
}

/**
 * 从 [from]（含）起第一条「这次还没被判死」的线路下标。
 *
 * 全都判死过（或剩下的全是判死过的）时**退回 [from] 原样返回**：宁可多等一次，
 * 也不能因为一份会话级的记忆让一个台彻底播不了。
 *
 * [isDead] 可注入，好让「跳过逻辑」本身能被单测钉住（不必真的发网络请求）。
 */
internal fun firstLiveLineIndex(
    lines: List<String>,
    from: Int,
    isDead: (String) -> Boolean = DeadLines::isDead,
): Int {
    if (lines.isEmpty()) return 0
    val start = from.coerceIn(0, lines.lastIndex)
    for (i in start until lines.size) {
        if (!isDead(lines[i])) return i
    }
    return start
}
