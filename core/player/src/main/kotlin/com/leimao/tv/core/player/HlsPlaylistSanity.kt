package com.leimao.tv.core.player

/**
 * 「这份 HLS 播放列表能不能交给 libVLC」的判定（2026-11-06，实测崩溃）。
 *
 * ## 为什么需要它
 *
 * libVLC 的 adaptive demux 在 `SegmentList::updateWith()` 里对分片时长做了
 * **断言**：`assertion "duration" failed` → `abort()` → 整个进程被 SIGABRT 干掉。
 * 也就是说，只要喂给它一份「`#EXTINF:` 后面没有正数时长」的 playlist，就不是
 * 「这条线路播不了」，而是**应用闪退**。这个错误在 Kotlin 侧完全接不住 ——
 * 它是 native 层的 abort，不是异常。
 *
 * 实测（2026-11-06，聚合源）：Media3 把一条线路判失败 → 降级到 VLC → VLC 解析
 * 那份残缺 playlist → 进程直接被信号打死，用户看到的是「换台换着换着 App 没了」。
 *
 * ## 判据
 *
 * 只认一件事：**每个 `#EXTINF:` 后面必须是正数时长**。
 *
 * - 缺 `#EXTINF:` 也缺 `#EXT-X-STREAM-INF`（既不是媒体列表也不是主列表）→ 不判死，
 *   交给内核自己报错：那种情况 VLC 只会「打不开」，不会 abort。
 * - 主列表（`#EXT-X-STREAM-INF`）本身没有 `#EXTINF`，是合法的，直接放行。
 * - `#EXT-X-TARGETDURATION` 缺失不单独判死：它不是 abort 的触发点，
 *   而有些国产源确实不给，判死会误伤一批能播的线路。
 */
internal fun isPlaylistRejectedByVlc(text: String): Boolean {
    if (!text.contains("#EXTM3U")) return false
    // 主列表：只列变体流，没有分片时长，VLC 会再去拉子列表，这里没有可判的东西
    if (text.contains("#EXT-X-STREAM-INF")) return false

    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (!line.startsWith("#EXTINF:")) continue
        if (extinfDurationSeconds(line.substringAfter("#EXTINF:")) <= 0.0) return true
    }
    return false
}

/**
 * `#EXTINF:4.000,` / `#EXTINF: 4 ,title` → 4.0。
 *
 * 逗号后面是标题，必须切掉；解析不出来一律当 0（**判死**）——
 * 一份写着 `#EXTINF:,` 的 playlist 在 VLC 那边就是 abort。
 */
private fun extinfDurationSeconds(rest: String): Double {
    val value = rest.substringBefore(',').trim()
    if (value.isEmpty()) return 0.0
    return value.toDoubleOrNull() ?: 0.0
}
