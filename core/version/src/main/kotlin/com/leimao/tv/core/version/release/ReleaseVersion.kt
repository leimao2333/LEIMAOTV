package com.leimao.tv.core.version.release

import com.leimao.tv.core.version.AppVersionNumber

/**
 * 远端发布版本与本地版本的大小比较（SET-07）。
 *
 * 为什么不直接用 [AppVersionNumber]：它带着**我们自己的发版纪律**（大/小版本 0~9、修订 0~99、
 * 必须四段），这些约束对「别人的 tag」不成立也没必要成立 ——
 * 万一哪天手滑打了个 `v0.2.10.0`，构造函数会直接抛异常，更新器就成了「一查就崩」。
 * 比较只需要**数值顺序**，所以这里单独写一份宽松的解析：只认数字段，不认纪律。
 *
 * 宽松不等于瞎猜：段数 1~4 允许（缺的段补 0，`v1.0.0` 与 `v1.0.0.0` 视为同一版），
 * 出现非数字（`0.2.9.7-beta`、`latest`）一律判为「读不懂」并返回 null ——
 * 宁可说「看不懂对方的版本号」，也不要拿一个猜出来的大小关系去劝用户升级。
 */
object ReleaseVersion {
    /** 允许的最多段数：本项目的四段式；多于四段说明对方根本不是这个项目的版本号 */
    private const val MAX_SEGMENTS = 4

    /**
     * @return 负数 / 0 / 正数，语义同 [Comparable.compareTo]；任一边读不懂时返回 null
     */
    fun compare(
        left: String,
        right: String,
    ): Int? {
        val a = parse(left) ?: return null
        val b = parse(right) ?: return null
        for (index in 0 until MAX_SEGMENTS) {
            val diff = a[index].compareTo(b[index])
            if (diff != 0) return diff
        }
        return 0
    }

    /** [remote] 是否比 [current] 新。读不懂时给 null，交给调用方去说「看不懂」而不是「已是最新」 */
    fun isNewer(
        remote: String,
        current: String,
    ): Boolean? = compare(remote, current)?.let { it > 0 }

    /**
     * 解析成定长四段的整数数组，缺的段补 0。
     *
     * `v` 前缀可有可无：GitHub 的 tag 习惯带，Release 正文里引用版本号时常不带，两种都得认。
     */
    fun parse(version: String): IntArray? {
        val body = version.trim().removePrefix("v").removePrefix("V")
        if (body.isEmpty()) return null
        val parts = body.split('.')
        if (parts.size !in 1..MAX_SEGMENTS) return null
        val segments = IntArray(MAX_SEGMENTS)
        parts.forEachIndexed { index, part ->
            // 只接受纯十进制：`+1`、`0x10`、` 1` 这类写法在版本号里都算错，别让 toIntOrNull 放过去
            if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
            segments[index] = part.toIntOrNull() ?: return null
        }
        return segments
    }
}
