package top.yukonga.mishka.util

/**
 * 预处理后的搜索词：去首尾空白并按空白拆分为多词（AND 语义）。
 */
class SearchQuery private constructor(
    val tokens: List<String>,
    val normalizedPhrase: String,
) {
    val isEmpty: Boolean get() = tokens.isEmpty()

    companion object {
        val EMPTY = SearchQuery(emptyList(), "")

        fun parse(raw: String): SearchQuery {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return EMPTY
            val tokens = ArrayList<String>(4)
            var start = -1
            for (i in trimmed.indices) {
                if (trimmed[i].isWhitespace()) {
                    if (start >= 0) {
                        tokens.add(trimmed.substring(start, i))
                        start = -1
                    }
                } else if (start < 0) {
                    start = i
                }
            }
            if (start >= 0) tokens.add(trimmed.substring(start))
            if (tokens.isEmpty()) return EMPTY
            val phrase = if (tokens.size == 1) tokens[0] else tokens.joinToString(" ")
            return SearchQuery(tokens, phrase)
        }
    }
}

/**
 * 通用搜索相关度匹配与排序引擎。
 *
 * 匹配层级（由高到低）：
 * 0. 完整匹配（Exact）
 * 1. 前缀匹配（Prefix）
 * 2. 词/段边界前缀（Boundary：空白/标点、驼峰、字母与数字、ASCII 与非 ASCII 边界）
 * 3. 任意子串（Contains）
 * 4. 边界首字母缩写（Acronym，仅长度 ≥ 2 且字段显式启用时生效）
 *
 * 多词按 AND 语义全部命中；同层级内依次按连续短语命中、字段优先级、命中位置、
 * 命中字段长度（更紧凑优先）以及调用方提供的稳定兜底比较器排序。
 */
object SearchMatcher {

    private const val TIER_EXACT = 0
    private const val TIER_PREFIX = 1
    private const val TIER_BOUNDARY = 2
    private const val TIER_CONTAINS = 3
    private const val TIER_ACRONYM = 4
    private const val TIER_NONE = 5

    private const val NO_MATCH = Long.MAX_VALUE

    class CandidateScope internal constructor(
        private val tokens: List<String>,
        private val phrase: String,
    ) {
        private val singleToken = tokens.size == 1
        private val tokenScores = LongArray(tokens.size)
        private var singleBest = NO_MATCH
        private var bestPhraseTier = TIER_NONE
        private var nextFieldIndex = 0

        internal fun reset() {
            nextFieldIndex = 0
            if (singleToken) {
                singleBest = NO_MATCH
            } else {
                tokenScores.fill(NO_MATCH)
                bestPhraseTier = TIER_NONE
            }
        }

        /** 按当前字段优先级评估单个文本，之后优先级递增。 */
        fun field(value: String, allowAcronym: Boolean = false) {
            val priority = nextFieldIndex++
            if (value.isEmpty()) return
            evaluateValue(value, priority, allowAcronym)
        }

        /** 按同一字段优先级评估两个等价别名（如日志级别 "warning" 与展示标签 "warn"）。 */
        fun field(primary: String, alias: String, allowAcronym: Boolean = false) {
            val priority = nextFieldIndex++
            if (primary.isNotEmpty()) evaluateValue(primary, priority, allowAcronym)
            if (alias.isNotEmpty() && !alias.equals(primary, ignoreCase = true)) {
                evaluateValue(alias, priority, allowAcronym)
            }
        }

        /** 按同一字段优先级评估一组文本（如代理链路列表），取其中最佳命中。 */
        fun fields(values: List<String>, allowAcronym: Boolean = false) {
            val priority = nextFieldIndex++
            for (i in values.indices) {
                val v = values[i]
                if (v.isNotEmpty()) evaluateValue(v, priority, allowAcronym)
            }
        }

        private fun evaluateValue(value: String, priority: Int, allowAcronym: Boolean) {
            if (singleToken) {
                val score = matchField(value, tokens[0], priority, allowAcronym)
                if (score < singleBest) singleBest = score
                return
            }

            for (i in tokens.indices) {
                val score = matchField(value, tokens[i], priority, allowAcronym)
                if (score < tokenScores[i]) tokenScores[i] = score
            }
            if (bestPhraseTier > TIER_EXACT) {
                val phraseScore = matchField(value, phrase, priority, allowAcronym = false)
                if (phraseScore != NO_MATCH) {
                    val tier = unpackTier(phraseScore)
                    if (tier < bestPhraseTier) bestPhraseTier = tier
                }
            }
        }

        internal fun computeScore(): Long {
            if (singleToken) {
                if (singleBest == NO_MATCH) return NO_MATCH
                val tier = unpackTier(singleBest)
                val field = unpackField(singleBest)
                val index = unpackIndex(singleBest)
                val length = unpackLength(singleBest)
                return packFinalScore(
                    worstTier = tier,
                    phraseTier = tier,
                    bestTier = tier,
                    tierSum = tier,
                    fieldSum = field,
                    indexSum = index,
                    lengthSum = length,
                )
            }

            var worstTier = 0
            var bestTier = TIER_NONE
            var tierSum = 0
            var fieldSum = 0
            var indexSum = 0
            var lengthSum = 0
            for (i in tokenScores.indices) {
                val score = tokenScores[i]
                if (score == NO_MATCH) return NO_MATCH
                val tier = unpackTier(score)
                if (tier > worstTier) worstTier = tier
                if (tier < bestTier) bestTier = tier
                tierSum += tier
                fieldSum += unpackField(score)
                indexSum += unpackIndex(score)
                lengthSum += unpackLength(score)
            }
            return packFinalScore(
                worstTier = worstTier,
                phraseTier = bestPhraseTier,
                bestTier = bestTier,
                tierSum = tierSum,
                fieldSum = fieldSum,
                indexSum = indexSum,
                lengthSum = lengthSum,
            )
        }
    }

    private class RankedItem<T>(
        val item: T,
        val score: Long,
    )

    /**
     * 过滤并按相关度从高到低排序；同分项由 [tieBreaker] 决定（为空时保持原列表稳定顺序）。
     */
    fun <T> rank(
        items: List<T>,
        query: SearchQuery,
        tieBreaker: Comparator<T>? = null,
        provideFields: CandidateScope.(T) -> Unit,
    ): List<T> {
        if (query.isEmpty || items.isEmpty()) return items
        val scope = CandidateScope(query.tokens, query.normalizedPhrase)
        val matched = ArrayList<RankedItem<T>>()
        for (i in items.indices) {
            val item = items[i]
            scope.reset()
            scope.provideFields(item)
            val score = scope.computeScore()
            if (score != NO_MATCH) {
                matched.add(RankedItem(item, score))
            }
        }
        if (matched.size <= 1) {
            return if (matched.isEmpty()) emptyList() else listOf(matched[0].item)
        }
        if (tieBreaker != null) {
            matched.sortWith { a, b ->
                val cmp = a.score.compareTo(b.score)
                if (cmp != 0) cmp else tieBreaker.compare(a.item, b.item)
            }
        } else {
            matched.sortWith { a, b -> a.score.compareTo(b.score) }
        }
        return ArrayList<T>(matched.size).apply {
            for (i in matched.indices) add(matched[i].item)
        }
    }

    /** 仅过滤不排序（用于只需匹配集合的场景，匹配语义与 [rank] 完全一致）。 */
    fun <T> filter(
        items: List<T>,
        query: SearchQuery,
        provideFields: CandidateScope.(T) -> Unit,
    ): List<T> {
        if (query.isEmpty || items.isEmpty()) return items
        val scope = CandidateScope(query.tokens, query.normalizedPhrase)
        val matched = ArrayList<T>()
        for (i in items.indices) {
            val item = items[i]
            scope.reset()
            scope.provideFields(item)
            if (scope.computeScore() != NO_MATCH) {
                matched.add(item)
            }
        }
        return matched
    }

    private fun matchField(
        text: String,
        token: String,
        fieldIndex: Int,
        allowAcronym: Boolean,
    ): Long {
        if (text.length < token.length) return NO_MATCH

        if (text.length == token.length && text.equals(token, ignoreCase = true)) {
            return packFieldScore(TIER_EXACT, fieldIndex, 0, text.length)
        }

        if (text.startsWith(token, ignoreCase = true)) {
            return packFieldScore(TIER_PREFIX, fieldIndex, 0, text.length)
        }

        // 扫描所有出现位置，避免如 "Cloud Updater" 搜 "u" 时被前方的中段匹配掩盖后面的词首边界匹配
        var firstSubstringIndex = -1
        var pos = text.indexOf(token, startIndex = 1, ignoreCase = true)
        while (pos >= 0) {
            if (isWordBoundary(text, pos)) {
                return packFieldScore(TIER_BOUNDARY, fieldIndex, pos, text.length)
            }
            if (firstSubstringIndex < 0) firstSubstringIndex = pos
            pos = text.indexOf(token, startIndex = pos + 1, ignoreCase = true)
        }
        if (firstSubstringIndex >= 0) {
            return packFieldScore(TIER_CONTAINS, fieldIndex, firstSubstringIndex, text.length)
        }

        if (allowAcronym && token.length >= 2) {
            val acronymStart = matchBoundaryInitials(text, token)
            if (acronymStart >= 0) {
                return packFieldScore(TIER_ACRONYM, fieldIndex, acronymStart, text.length)
            }
        }

        return NO_MATCH
    }

    private fun isWordBoundary(text: String, index: Int): Boolean {
        if (index <= 0) return true
        val prev = text[index - 1]
        val curr = text[index]
        if (!prev.isLetterOrDigit()) return true
        if (prev.isLowerCase() && curr.isUpperCase()) return true
        if (prev.isUpperCase() && curr.isUpperCase() &&
            index + 1 < text.length && text[index + 1].isLowerCase()
        ) {
            return true
        }
        if ((prev.isLetter() && curr.isDigit()) || (prev.isDigit() && curr.isLetter())) return true
        if ((prev.code < 128) != (curr.code < 128)) return true
        return false
    }

    /** 仅在词/段/驼峰边界首字符上顺序匹配，避免无约束子序列带来的噪音结果。 */
    private fun matchBoundaryInitials(text: String, token: String): Int {
        var ti = 0
        var firstPos = -1
        for (i in text.indices) {
            val ch = text[i]
            if (!ch.isLetterOrDigit()) continue
            if (i != 0 && !isWordBoundary(text, i)) continue
            if (ch.equals(token[ti], ignoreCase = true)) {
                if (ti == 0) firstPos = i
                ti++
                if (ti == token.length) return firstPos
            }
        }
        return -1
    }

    // 单字段分值打包：tier(3) | field(8) | index(16) | length(16) -> 非负 Long
    private fun packFieldScore(tier: Int, fieldIndex: Int, matchIndex: Int, length: Int): Long {
        val f = fieldIndex.coerceIn(0, 0xFF).toLong()
        val idx = matchIndex.coerceIn(0, 0xFFFF).toLong()
        val len = length.coerceIn(0, 0xFFFF).toLong()
        return (tier.toLong() shl 40) or (f shl 32) or (idx shl 16) or len
    }

    private fun unpackTier(packed: Long): Int = ((packed ushr 40) and 0x7L).toInt()
    private fun unpackField(packed: Long): Int = ((packed ushr 32) and 0xFFL).toInt()
    private fun unpackIndex(packed: Long): Int = ((packed ushr 16) and 0xFFFFL).toInt()
    private fun unpackLength(packed: Long): Int = (packed and 0xFFFFL).toInt()

    // 最终排序分值打包（低值更优）：
    // worstTier(3) | phraseTier(3) | bestTier(3) | tierSum(6) | fieldSum(8) | indexSum(16) | lengthSum(16)
    private fun packFinalScore(
        worstTier: Int,
        phraseTier: Int,
        bestTier: Int,
        tierSum: Int,
        fieldSum: Int,
        indexSum: Int,
        lengthSum: Int,
    ): Long {
        return (worstTier.coerceIn(0, 7).toLong() shl 57) or
            (phraseTier.coerceIn(0, 7).toLong() shl 54) or
            (bestTier.coerceIn(0, 7).toLong() shl 51) or
            (tierSum.coerceIn(0, 0x3F).toLong() shl 45) or
            (fieldSum.coerceIn(0, 0xFF).toLong() shl 37) or
            (indexSum.coerceIn(0, 0xFFFF).toLong() shl 21) or
            (lengthSum.coerceIn(0, 0xFFFF).toLong() shl 5)
    }
}
