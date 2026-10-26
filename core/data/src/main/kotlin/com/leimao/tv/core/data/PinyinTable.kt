package com.leimao.tv.core.data

/**
 * 汉字 → 拼音表，支持**全拼 / 简写 / 混拼**三种输入，且认**多音字**（SEARCH-06）。
 *
 * 数据来自 `assets/pinyin_table.txt`（`scripts/gen-pinyin.py` 从
 * [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data) 生成，MIT）。
 * 一行一个字，汉字后面跟它的读音：`湖hu`、`重zhong,chong`。
 *
 * | 输入 | 命中 | 口径 |
 * |---|---|---|
 * | `hunan`     | 湖南卫视 | 全拼 |
 * | `hnws`      | 湖南卫视 | 简写（每个字只取首字母） |
 * | `hunws`     | 湖南卫视 | **混拼**（前两字全拼、后两字首字母） |
 * | `chongqing` | 重庆卫视 | **多音字**（重 的第二个读音 chóng） |
 * | `cctv5`     | CCTV5 综合 | 名字里的英文数字原样参与比对 |
 *
 * ## 为什么用 DP 而不是「拼成两个字符串再 contains」
 *
 * 拼成 `hunanweishi` 只能覆盖纯全拼与纯简写；混拼（`hunws`）在任何一条拼接结果里
 * 都不连续，`contains` 永远命中不了。而混拼是常态 —— 前几个字打全、后面懒得打。
 *
 * 所以改成「逐字决策」：每个字要么贡献**某个读音**、要么贡献**首字母**，
 * 用一次状态机扫描判断查询能否被连续的一段字覆盖。单次扫描
 * O(字数 × 查询长 × 读音数)，对 20 个字、8 个字母的名字是几千次字符比较，
 * 比拼接字符串还省（不产生临时串）。多音字顺带就解决了：`重` 有 `zhong` 与 `chong`
 * 两个选项，`chongqing` 自然命中。
 *
 * ## 参考过的三个实现（只借思路，不引数据）
 *
 * - [ibaoger/PinyinIME](https://github.com/ibaoger/PinyinIME)：Android 拼音输入法的
 *   词库与候选组织方式。本项目不做候选，但「多音字必须全部保留、由上下文决定读音」
 *   这条是它的核心经验。
 * - [FRIEDparrot/zh_pinyin_decoder](https://github.com/FRIEDparrot/zh_pinyin_decoder)：
 *   把拼音串解码成汉字序列（而不是反过来拼）。这里的 DP 是它的极简版 ——
 *   只是「词图」只有一层，且方向相反（已知汉字求能否匹配拼音）。
 * - [iDvel/rime-ice](https://github.com/iDvel/rime-ice)：雾凇拼音的词库与多音字表。
 *   **它的数据没有引入**：仓库是 GPL-3.0，而本项目是 Apache-2.0（RULES §4 禁止 GPL
 *   依赖/数据进主工程）。只借用「词组优先、多音字按词消歧」的思路；
 *   真正的按词消歧要一份词库，属后续项（见 TODO）。
 *
 * ## 为什么用「有序字符数组 + 偏移表 + 大字符串」而不是 `Map<Char, List<String>>`
 *
 * 两万七千条 Entry 的哈希表在 TV 盒子上要吃掉好几兆，且每个字一个 List 就是一次额外分配；
 * 这里六张数组加起来约 500KB，查找走二分（O(log n)），匹配时直接读 blob 的字符段，
 * 不产生临时字符串。一次搜索要过上千个频道名，差别是看得见的。
 */
class PinyinTable(raw: String) {
    private val chars: CharArray

    /** 第 i 个字的读音在 [readStarts] / [readLens] 里的起始下标 */
    private val charReadStart: IntArray
    private val charReadCount: ShortArray
    private val readStarts: IntArray
    private val readLens: ShortArray
    private val blob: CharArray

    init {
        // 先收集再排序建索引：直接往数组里塞，二分查找的前提（有序）就不成立了
        val entries = ArrayList<Pair<Char, List<String>>>(32768)
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.length < 2) return@forEach
            val hanzi = trimmed[0]
            val readings =
                trimmed
                    .substring(1)
                    .split(',')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && it.all { ch -> ch in 'a'..'z' } }
            if (readings.isEmpty()) return@forEach
            entries.add(hanzi to readings)
        }
        entries.sortBy { it.first }

        chars = CharArray(entries.size) { entries[it].first }
        charReadStart = IntArray(entries.size)
        charReadCount = ShortArray(entries.size)
        val starts = ArrayList<Int>(entries.size)
        val lens = ArrayList<Short>(entries.size)
        val text = StringBuilder(entries.size * 6)

        entries.forEachIndexed { i, entry ->
            charReadStart[i] = starts.size
            charReadCount[i] = entry.second.size.toShort()
            entry.second.forEach { reading ->
                starts.add(text.length)
                lens.add(reading.length.toShort())
                text.append(reading)
            }
        }
        readStarts = starts.toIntArray()
        readLens = lens.toShortArray()
        blob = text.toString().toCharArray()
    }

    val size: Int get() = chars.size

    /** 一个字的首选读音（表里第一个）；非汉字返回 null */
    fun syllableOf(ch: Char): String? {
        val idx = chars.binarySearch(ch)
        if (idx < 0) return null
        val at = charReadStart[idx]
        return String(blob, readStarts[at], readLens[at].toInt())
    }

    /** 全拼（取每个字的**首选**读音）：`湖南卫视` → `hunanweishi`；英文数字原样保留 */
    fun fullPinyinOf(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length * 2)
        for (ch in text) {
            val idx = chars.binarySearch(ch)
            when {
                idx >= 0 -> {
                    val at = charReadStart[idx]
                    sb.append(blob, readStarts[at], readLens[at].toInt())
                }

                ch.isLetterOrDigit() && ch.code < 128 -> sb.append(ch.lowercaseChar())
                else -> Unit // 标点、空格、emoji：丢掉
            }
        }
        return sb.toString()
    }

    /** 简写：`湖南卫视` → `hnws`（每个字首选读音的首字母） */
    fun initialsOf(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val idx = chars.binarySearch(ch)
            when {
                idx >= 0 -> sb.append(blob[readStarts[charReadStart[idx]]])
                ch.isLetterOrDigit() && ch.code < 128 -> sb.append(ch.lowercaseChar())
                else -> Unit
            }
        }
        return sb.toString()
    }

    /**
     * [text] 的拼音能否覆盖 [normalizedQuery]（全拼 / 简写 / 混拼，多音字全认）。
     *
     * 空查询一律 false —— 否则整个频道表都会被当成命中。
     * 非 ASCII 查询直接 false：中文走名字匹配那条路，不该在这里再扫一遍。
     */
    fun matches(
        text: String,
        normalizedQuery: String,
    ): Boolean {
        if (normalizedQuery.isEmpty()) return false
        if (normalizedQuery.any { it.code >= 128 }) return false
        if (text.isEmpty()) return false

        val m = normalizedQuery.length
        // cur[p] = 「以当前字结尾的某一段」能否覆盖 query 的前 p 个字母
        var cur = BooleanArray(m + 1).also { it[0] = true }
        var next = BooleanArray(m + 1)

        for (ch in text) {
            val idx = chars.binarySearch(ch)
            val isAscii = ch.isLetterOrDigit() && ch.code < 128
            next.fill(false)
            // 任何一个位置都能重新开始：`contains` 语义要求「从中间某个字起」也算命中
            next[0] = true

            if (idx < 0 && !isAscii) {
                // 标点 / 空格 / emoji：**零宽** —— 不消耗查询字符，也不打断匹配。
                // 少了这一步，`CCTV-1 中央` 打 `cctv1` 就会因为中间那个 `-` 断掉
                for (p in 0..m) if (cur[p]) next[p] = true
            } else {
                for (p in 0..m) {
                    if (!cur[p]) continue
                    if (p == m) return true

                    if (idx >= 0) {
                        // 选项一：某个读音（多音字在这里全部参与）
                        val from = charReadStart[idx]
                        val count = charReadCount[idx].toInt()
                        for (r in from until from + count) {
                            val len = readLens[r].toInt()
                            if (p + len <= m && regionMatches(blob, readStarts[r], normalizedQuery, p, len)) {
                                next[p + len] = true
                            }
                        }
                        // 选项二：首字母
                        if (blob[readStarts[from]] == normalizedQuery[p]) next[p + 1] = true
                    } else {
                        // 英文 / 数字：逐字符比对，`cctv5` 这类名字靠它命中
                        if (ch.lowercaseChar() == normalizedQuery[p]) next[p + 1] = true
                    }
                }
                if (next[m]) return true
            }

            val swap = cur
            cur = next
            next = swap
        }
        return cur[m]
    }

    /** 比较 blob 的一段与 query 的一段，不产生临时字符串 */
    private fun regionMatches(
        blob: CharArray,
        blobStart: Int,
        query: String,
        queryStart: Int,
        len: Int,
    ): Boolean {
        for (i in 0 until len) {
            if (blob[blobStart + i] != query[queryStart + i]) return false
        }
        return true
    }

    companion object {
        /** 空表：没有词典时（测试、或资源读取失败）退化成「永不命中」，按名搜索照旧 */
        val EMPTY = PinyinTable("")
    }
}
