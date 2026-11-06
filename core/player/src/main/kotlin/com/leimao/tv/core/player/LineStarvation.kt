package com.leimao.tv.core.player

/**
 * 「这条线路供不上」的判据（PLAY-13，2026-11-06「隔两秒卡一次」）。
 *
 * ## 为什么不能再靠「数缓冲次数」
 *
 * 原来只有一条判据：出画之后 45 秒内进 3 次 BUFFERING 就换线。它对**完全断流**管用，
 * 对「能下、但下得比播得慢」这一类几乎无效 —— 那种线路的表现是
 * 「播两秒卡一下、再播两秒再卡」，每次缓冲都只有一两秒，45 秒里凑够 3 次要等很久，
 * 而且换线之后**又从头数**。用户看到的是画面一直在抖，App 却像没事一样。
 *
 * ## 判据本身
 *
 * 实测速率 vs 流自己声明的码率：
 *
 * ```
 * 实测 < 码率 × 余量  →  这条线路先天喂不饱这路流
 * ```
 *
 * 这不是「优化」层面的判断，而是物理下限：一路 10.7 Mbps 的 1080p，线路只给
 * 0.8 Mbps，**任何播放器、任何缓冲参数都不可能放流畅** —— 攒多少都会被抽干。
 * 实测 2026-11-06：CCTV5 那条 `gmxw.7766.org:808/hls/93` 上，一个 4.2 秒的分片
 * 有 5.45 MB（≈10.4 Mbps），而下载速率 0.82 Mbps，13 倍缺口，画面就是
 * 「动一秒、停两秒」。同台另一条 `:9901/tsfile` 线路实测 27 Mbps，同一份流一点不卡。
 *
 * ## [margin] 为什么大于 1
 *
 * 刚好等于码率的线路等于「实时余量为零」：只要有一个分片慢半拍，缓冲就见底。
 * 1.15 是留 15% 的余量 —— 低于这个数就不该指望它长时间稳住。
 *
 * 码率未知（[requiredBitsPerSecond] <= 0）时**一律返回 false**：没有依据的判断比不判断更糟，
 * 那种情况继续走「反复缓冲」那条老判据（它不需要知道码率）。
 */
internal fun isLineStarved(
    measuredBytesPerSecond: Long,
    requiredBitsPerSecond: Int,
    margin: Double = STARVED_MARGIN,
): Boolean {
    if (requiredBitsPerSecond <= 0) return false
    if (measuredBytesPerSecond <= 0L) return false
    val requiredBytesPerSecond = requiredBitsPerSecond / 8.0
    return measuredBytesPerSecond < requiredBytesPerSecond * margin
}

/** 余量系数。见 [isLineStarved] 的说明：低于 1 就等于「容忍零余量」 */
internal const val STARVED_MARGIN: Double = 1.15

/**
 * 「缓冲时间占比」判据（2026-11-06）—— 码率未知时的那一半。
 *
 * ## 为什么还需要这一条
 *
 * 实测（CCTV5，HLS 直播）`Format.bitrate` 是 **0**：HLS 的 TS 分片头里没有码率声明，
 * Media3 的解析器也就不填。这时「实测 vs 码率」的判据直接失效，
 * 而用户看到的仍然是「播一秒卡两秒」。
 *
 * 于是补一条不需要码率的：**最近 10 秒里有多少时间在缓冲**。
 * 一路供得上的流，稳态缓冲占比接近 0（偶尔抖一下也就 5%）；供不上的流是
 * 「播两秒缓冲两秒」，占比 50% 上下 —— 两者差一个数量级，不需要精确码率也能分开。
 *
 * 阈值 0.4 落在中间：低于它是「网络有点抖但能看」，高于它是「一直在等」。
 *
 * [minTicks] 是预热期：刚起播本来就在缓冲，那几秒的占比毫无意义。
 */
internal fun isRebufferHeavy(
    bufferingTicks: Int,
    totalTicks: Int,
    threshold: Double = REBUFFER_RATIO_THRESHOLD,
): Boolean {
    if (totalTicks < REBUFFER_RATIO_MIN_TICKS) return false
    if (bufferingTicks <= 0) return false
    return bufferingTicks.toDouble() / totalTicks >= threshold
}

/** 缓冲占比阈值：40% 以上算「一直在等」 */
internal const val REBUFFER_RATIO_THRESHOLD: Double = 0.4

/** 观察窗口长度（统计周期 500ms → 20 个约 10 秒）。太短会把一次抖动当成结论 */
internal const val REBUFFER_RATIO_MIN_TICKS: Int = 20

/**
 * 连续多少个统计周期判「饿」才动手。
 *
 * 统计周期是 500ms（见 `Media3PlayerController.STATS_TICK_MS`），6 个 = 3 秒。
 * 为什么不是 1 个：码率是**声明值**，容器给的常常是平均码率；HLS 的分片之间码率
 * 本来就有起伏（广告 / 运动镜头），一两秒的低于均值是正常的。3 秒还低于码率，
 * 说明不是起伏，是线路就这么快。
 */
internal const val STARVED_SWITCH_TICKS: Int = 6

/** 「供不上」判定的说明文字：日志与失败面板共用，避免两处数字对不上 */
internal fun starvedDescription(
    requiredBitsPerSecond: Int,
    measuredBytesPerSecond: Long,
): String =
    "这条线路供不上：流要 ${mbps(requiredBitsPerSecond.toLong())} Mbps，" +
        "实测 ${mbps(measuredBytesPerSecond * 8L)} Mbps"

/**
 * bps → 「10.7」这种一位小数的 Mbps。
 *
 * 不用 `String.format("%.1f")`：它按系统 Locale 出小数点，中文环境配某些盒子会得到
 * 「10,7」，用户看不出这是数字还是两个数。这里自己算整数位与小数位。
 */
private fun mbps(bitsPerSecond: Long): String {
    val tenths = (bitsPerSecond * 10L + 500_000L) / 1_000_000L
    return "${tenths / 10}.${tenths % 10}"
}
