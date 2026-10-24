package com.leimao.tv.core.model.cache

import java.util.Locale
import kotlin.math.roundToInt

private const val BYTES_PER_UNIT = 1024.0
private val SIZE_UNITS = listOf("KB", "MB", "GB", "TB")

/**
 * 缓存占用（SET-05）。
 *
 * 两项分开而不是只给一个总数：用户看到「32.1 MB」时想知道的下一件事是「这算多吗」，
 * 而「图片缓存 31.8 MB / 其他 0.3 MB」直接回答了这个问题 —— 台标缓存本来就会有几十兆，
 * 临时文件涨到几十兆才是异常。
 */
data class CacheUsage(
    val imageBytes: Long = 0L,
    val otherBytes: Long = 0L,
) {
    val totalBytes: Long get() = imageBytes + otherBytes

    val isEmpty: Boolean get() = totalBytes <= 0L
}

/**
 * 缓存大小的展示口径。
 *
 * 三条规则都是有理由的：
 * - **不足 1KB 直接给字节数**：这时候「0.5 KB」反而让人怀疑是不是没算准。
 * - **KB 以上留一位小数**：「1.2 MB」和「1 MB」是两回事，差了 20%。
 * - **到 100 以上取整**：「128 MB」比「128.4 MB」好扫，那 0.4 也没有信息量。
 */
fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < BYTES_PER_UNIT) return "$bytes B"

    var value = bytes / BYTES_PER_UNIT
    var unitIndex = 0
    while (value >= BYTES_PER_UNIT && unitIndex < SIZE_UNITS.lastIndex) {
        value /= BYTES_PER_UNIT
        unitIndex++
    }

    if (value < 100.0) {
        return String.format(Locale.US, "%.1f", value) + " " + SIZE_UNITS[unitIndex]
    }

    val rounded = value.roundToInt()
    // 999.6 KB 取整会写成「1000 KB」—— 与其让人在脑子里换算，不如直接进位写成「1.0 MB」
    if (rounded >= 1000 && unitIndex < SIZE_UNITS.lastIndex) {
        return String.format(Locale.US, "%.1f", value / BYTES_PER_UNIT) + " " + SIZE_UNITS[unitIndex + 1]
    }
    return "$rounded ${SIZE_UNITS[unitIndex]}"
}
