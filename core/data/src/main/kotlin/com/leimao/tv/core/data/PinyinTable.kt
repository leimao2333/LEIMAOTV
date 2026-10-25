package com.leimao.tv.core.data

/**
 * 汉字 → 拼音表，同时支持**全拼**与**简写**两种输入（SEARCH-06）。
 *
 * 数据来自 `assets/pinyin_table.txt`（`scripts/gen-pinyin.py` 从
 * [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data) 生成，MIT），
 * 一行一个字：`湖hu`、`南nan`。首字母由拼音首字符现取，所以一份表够用两种口径：
 *
 * | 输入 | 命中 | 口径 |
 * |---|---|---|
 * | `hunan`   | 湖南卫视 | 全拼（26 键键盘的主要用法） |
 * | `hnws`    | 湖南卫视 | 简写（首字母） |
 * | `cctv5`   | CCTV5 综合 | 名字里的英文数字原样参与比对 |
 *
 * ## 为什么用「有序字符数组 + 偏移表 + 大字符串」而不是 `Map<Char, String>`
 *
 * 两万七千条 Entry 的哈希表在 TV 盒子上要吃掉好几兆；这里四张数组加起来约 400KB，
 * 查找走二分（O(log n)），拼名字时直接往 StringBuilder 里搬字符段，不产生临时字符串。
 * 一次搜索要过上千个频道名，差别是看得见的。
 */
class PinyinTable(raw: String) {
    private val chars: CharArray
    private val starts: IntArray
    private val lens: ShortArray
    private val blob: CharArray

    init {
        val entries = ArrayList<Triple<Char, Int, Int>>()
        val text = StringBuilder()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.length < 2) return@forEach
            val hanzi = trimmed[0]
            val syllable = trimmed.substring(1).lowercase()
            if (syllable.isEmpty() || syllable.any { it !in 'a'..'z' }) return@forEach
            entries.add(Triple(hanzi, text.length, syllable.length))
            text.append(syllable)
        }
        entries.sortBy { it.first }
        chars = CharArray(entries.size) { entries[it].first }
        starts = IntArray(entries.size) { entries[it].second }
        lens = ShortArray(entries.size) { entries[it].third.toShort() }
        blob = text.toString().toCharArray()
    }

    val size: Int get() = chars.size

    /** 一个字的无声调拼音；表里没有（非汉字）返回 null */
    fun syllableOf(ch: Char): String? {
        val idx = chars.binarySearch(ch)
        if (idx < 0) return null
        return String(blob, starts[idx], lens[idx].toInt())
    }

    /** 全拼：`湖南卫视` → `hunanweishi`；英文数字原样保留 */
    fun fullPinyinOf(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length * 2)
        for (ch in text) {
            val idx = chars.binarySearch(ch)
            when {
                idx >= 0 -> sb.append(blob, starts[idx], lens[idx].toInt())
                ch.isLetterOrDigit() && ch.code < 128 -> sb.append(ch.lowercaseChar())
                else -> Unit // 标点、空格、emoji：丢掉
            }
        }
        return sb.toString()
    }

    /** 简写：`湖南卫视` → `hnws`（全拼的首字母） */
    fun initialsOf(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val idx = chars.binarySearch(ch)
            when {
                idx >= 0 -> sb.append(blob[starts[idx]])
                ch.isLetterOrDigit() && ch.code < 128 -> sb.append(ch.lowercaseChar())
                else -> Unit
            }
        }
        return sb.toString()
    }

    /**
     * [text] 的拼音（全拼或简写）是否包含 [normalizedQuery]。
     *
     * 全拼与简写都判一次：用户可能打 `hunan`，也可能只打 `hnws`。
     * 空查询一律 false —— 否则整个频道表都会被当成命中。
     */
    fun matches(
        text: String,
        normalizedQuery: String,
    ): Boolean {
        if (normalizedQuery.isEmpty()) return false
        if (!normalizedQuery.all { it.code < 128 }) return false
        return initialsOf(text).contains(normalizedQuery) || fullPinyinOf(text).contains(normalizedQuery)
    }

    companion object {
        /** 空表：没有词典时（测试、或资源读取失败）退化成「永不命中」，按名搜索照旧 */
        val EMPTY = PinyinTable("")
    }
}
