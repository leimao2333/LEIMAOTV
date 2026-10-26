package com.leimao.tv.core.data

/**
 * 拼音输入法候选（SET-11 / SEARCH-06）。
 *
 * 打 `wo` 要出「我」，打 `nihao` 要出「你好」—— 这是**输入法**，不是搜索建议。
 * 搜索建议给的是「你打的拼音像哪个台名」，而用户要的是「我在打哪个字」。
 *
 * 数据来自 `assets/pinyin_ime.txt`（`scripts/gen-pinyin-ime.py` 生成）：
 * 一行一个拼音，`拼音<TAB>候选1,候选2,…`，候选按词频降序。两份 MIT 数据合成：
 * - mozillazg/phrase-pinyin-data：词组 → 拼音（多音字读音正确，`银行` 是 yinhang）
 * - fxsjy/jieba 的 dict.txt：词频（决定候选顺序，「我」排在「窝」前面）
 *
 * 单字候选来自 `pinyin_table.txt` 的反向索引 —— 词组库里没有单字，
 * 少了这一步，输入法打不出「我」这种最常用的字。
 *
 * 匹配策略：**先精确、再前缀**。打 `wo` 精确命中「我」；打 `n` 时按前缀列出
 * `na/ne/nihao…`，让用户边打边看。前缀只取前 [MAX_PREFIX_KEYS] 个拼音，
 * 否则一个字母能把整张表扫一遍。
 */
class PinyinIme private constructor(
    private val keys: Array<String>,
    private val values: Array<List<String>>,
) {
    val size: Int get() = keys.size

    /**
     * [letters] 的候选词。
     *
     * 精确命中的候选排在前（用户打完了整个拼音），其后是前缀命中的（还没打完）。
     * 结果去重：`ni` 精确给出「你」时，前缀里 `nihao` 的「你」不该再出现一次。
     */
    fun candidates(
        letters: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<String> {
        if (letters.isEmpty() || limit <= 0) return emptyList()
        val query = letters.lowercase()
        if (query.any { it !in 'a'..'z' }) return emptyList()

        val out = LinkedHashSet<String>(limit)
        val exact = keys.binarySearch(query)
        if (exact >= 0) {
            values[exact].forEach { if (out.size < limit) out.add(it) }
        }

        // 前缀：从插入点往后扫，直到不再是这个前缀
        var i = if (exact >= 0) exact + 1 else -exact - 1
        var scanned = 0
        while (i < keys.size && out.size < limit && scanned < MAX_PREFIX_KEYS) {
            if (!keys[i].startsWith(query)) break
            values[i].forEach { if (out.size < limit) out.add(it) }
            i++
            scanned++
        }
        return out.toList()
    }

    companion object {
        /** 电视上候选行放得下这么多；再多用户也懒得按 */
        const val DEFAULT_LIMIT = 8

        /** 前缀扫描上限：一个字母能命中上千个拼音，全扫一遍纯属浪费 */
        private const val MAX_PREFIX_KEYS = 24

        /** 空表：没有词典时（测试、或资源读取失败）退化成「没有输入法候选」，搜索照旧 */
        val EMPTY = PinyinIme(emptyArray(), emptyArray())

        /** 从资源文本构建。格式不对的行直接跳过 —— 少一条候选好过整张表加载失败 */
        fun parse(raw: String): PinyinIme {
            val pairs =
                raw.lineSequence()
                    .mapNotNull { line ->
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@mapNotNull null
                        val pinyin = line.substring(0, tab).trim().lowercase()
                        val words =
                            line
                                .substring(tab + 1)
                                .split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                        if (pinyin.isEmpty() || words.isEmpty()) null else pinyin to words
                    }.toList()
                    // 按拼音排序：二分查找与「前缀连续」都依赖这个前提
                    .sortedBy { it.first }

            return PinyinIme(
                keys = Array(pairs.size) { pairs[it].first },
                values = Array(pairs.size) { pairs[it].second },
            )
        }
    }
}
