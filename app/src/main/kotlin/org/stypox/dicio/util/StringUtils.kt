package org.stypox.dicio.util

import org.dicio.skill.standard.util.nfkdNormalizeWord
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max

object StringUtils {
    private val WORD_DELIMITERS_PATTERN = Pattern.compile("[^\\p{L}\\d]")
    const val DEFAULT_SEPARATOR = " • "

    fun joinNonBlank(vararg strings: String?, separator: CharSequence = DEFAULT_SEPARATOR): String {
        return strings.filter { !it.isNullOrBlank() }.joinToString(separator)
    }

    private fun cleanStringForDistance(s: String): String {
        return WORD_DELIMITERS_PATTERN.matcher(
            nfkdNormalizeWord(s.lowercase(Locale.getDefault()))
        ).replaceAll("")
    }

    private fun levenshteinDistanceMemory(a: String, b: String): Array<IntArray> {
        val memory = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) { memory[i][0] = i }
        for (j in 0..b.length) { memory[0][j] = j }
        for (i in a.indices) {
            for (j in b.indices) {
                val substitutionCost = if (a[i].lowercaseChar() == b[j].lowercaseChar()) 0 else 1
                memory[i + 1][j + 1] = minOf(
                    memory[i][j + 1] + 1,
                    memory[i + 1][j] + 1,
                    memory[i][j] + substitutionCost,
                )
            }
        }
        return memory
    }

    private fun pathInLevenshteinMemory(
        a: String, b: String, memory: Array<IntArray>,
    ): List<LevenshteinMemoryPos> {
        val positions: MutableList<LevenshteinMemoryPos> = ArrayList()
        var i = a.length - 1
        var j = b.length - 1
        while (i >= 0 && j >= 0) {
            val iOld = i; val jOld = j; var match = false
            if (memory[i + 1][j + 1] == memory[i][j + 1] + 1) {
                --i
            } else if (memory[i + 1][j + 1] == memory[i + 1][j] + 1) {
                --j
            } else {
                match = memory[i + 1][j + 1] == memory[i][j]
                --i; --j
            }
            positions.add(LevenshteinMemoryPos(iOld, jOld, match))
        }
        return positions
    }

    fun levenshteinDistance(aNotCleaned: String, bNotCleaned: String): Int {
        val a = cleanStringForDistance(aNotCleaned)
        val b = cleanStringForDistance(bNotCleaned)
        return levenshteinDistanceMemory(a, b)[a.length][b.length]
    }

    private fun stringDistanceStats(a: String, b: String): StringDistanceStats {
        val memory = levenshteinDistanceMemory(a, b)
        var matchingCharCount = 0
        var subsequentChars = 0
        var maxSubsequentChars = 0
        for (pos in pathInLevenshteinMemory(a, b, memory)) {
            if (pos.match) {
                ++matchingCharCount; ++subsequentChars
                maxSubsequentChars = max(maxSubsequentChars, subsequentChars)
            } else {
                subsequentChars = max(0, subsequentChars - 1)
            }
        }
        return StringDistanceStats(memory[a.length][b.length], maxSubsequentChars, matchingCharCount)
    }

    fun customStringDistance(aNotCleaned: String, bNotCleaned: String): Int {
        val a = cleanStringForDistance(aNotCleaned)
        val b = cleanStringForDistance(bNotCleaned)
        return customStringDistanceCleaned(a, b)
    }

    fun customStringDistanceCleaned(aCleaned: String, bCleaned: String): Int {
        val stats = stringDistanceStats(aCleaned, bCleaned)
        return stats.levenshteinDistance - stats.maxSubsequentChars - stats.matchingCharCount
    }

    fun contactStringDistance(aNotCleaned: String, bNotCleaned: String): Int {
        val a = cleanStringForDistance(aNotCleaned)
        val b = cleanStringForDistance(bNotCleaned)
        val stats = stringDistanceStats(a, b)
        return -stats.maxSubsequentChars - stats.matchingCharCount
    }

    private class LevenshteinMemoryPos(val i: Int, val j: Int, val match: Boolean)
    private class StringDistanceStats(
        val levenshteinDistance: Int,
        val maxSubsequentChars: Int,
        val matchingCharCount: Int,
    )
}

fun String.lowercaseCapitalized(locale: Locale): String {
    return lowercase(locale).replaceFirstChar { it.titlecase(locale) }
}
