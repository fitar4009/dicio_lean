package org.stypox.dicio.util

/**
 * Converts a string of spoken number words (Hebrew or English) into a dialable string
 * of digits and special characters.
 *
 * **Why this exists:**
 * Most on-device STT models output numbers as words rather than digits.
 * For example, saying "אפס חמש שתיים שבע" or "zero five two seven" produces those words,
 * not "0527". This parser bridges that gap so the app can dial numbers spoken as words.
 *
 * **Parsing rules (applied token by token, split on whitespace):**
 * | Token form                  | Result                     |
 * |-----------------------------|----------------------------|
 * | Pure digit sequence         | Appended as-is ("052"→"052")|
 * | Literal `*`                 | Appended as `*`            |
 * | Hebrew digit word           | Mapped to its digit/`*`    |
 * | English digit word          | Mapped to its digit/`*`    |
 * | Anything else               | `null` returned (not a number) |
 *
 * If **every** token maps to a digit or `*`, the joined dial string is returned.
 * If **any** token is unrecognised the function returns `null`, signalling that the
 * input is a contact name rather than a phone number.
 *
 * **Asterisk support:**
 * "כוכבית" (Hebrew) and "asterisk" / "star" (English) both map to `*`,
 * enabling dialling of service codes such as `*6666` or `*3`.
 */
object NumberWordParser {

    private val HEBREW = mapOf(
        // zero
        "אפס"    to "0",
        // one — masculine & feminine
        "אחת"    to "1",  "אחד"     to "1",
        // two — all inflections
        "שתיים"  to "2",  "שניים"   to "2",
        "שתי"    to "2",  "שני"     to "2",
        // three
        "שלוש"   to "3",  "שלושה"   to "3",
        // four
        "ארבע"   to "4",  "ארבעה"   to "4",
        // five
        "חמש"    to "5",  "חמישה"   to "5",
        // six
        "שש"     to "6",  "שישה"    to "6",
        // seven
        "שבע"    to "7",  "שבעה"    to "7",
        // eight (only one form in common use)
        "שמונה"  to "8",
        // nine
        "תשע"    to "9",  "תשעה"    to "9",
        // asterisk
        "כוכבית" to "*",
    )

    private val ENGLISH = mapOf(
        "zero"     to "0",
        "one"      to "1",
        "two"      to "2",
        "three"    to "3",
        "four"     to "4",
        "five"     to "5",
        "six"      to "6",
        "seven"    to "7",
        "eight"    to "8",
        "nine"     to "9",
        "asterisk" to "*",
        "star"     to "*",
    )

    /**
     * Attempts to interpret [spoken] as a phone/dial string composed of number words.
     *
     * @param spoken Raw string captured from the "who" slot (after stripping the "ל" prefix).
     * @return A dial string (digits and `*` only) when every token resolves to a digit/`*`;
     *         `null` when at least one token is not a recognised number word (i.e. the
     *         input looks like a contact name, not a phone number).
     */
    fun tryParseAsDialString(spoken: String): String? {
        val tokens = spoken.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        val sb = StringBuilder(tokens.size * 2)
        for (token in tokens) {
            val segment = when {
                // raw digit sequence (STT may produce "052" as one token)
                token.all { it.isDigit() }                        -> token
                // literal asterisk
                token == "*"                                       -> "*"
                // Hebrew number word
                HEBREW.containsKey(token)                         -> HEBREW[token]!!
                // English number word (case-insensitive)
                ENGLISH.containsKey(token.lowercase())            -> ENGLISH[token.lowercase()]!!
                // unrecognised → not a phone number
                else                                               -> return null
            }
            sb.append(segment)
        }

        return sb.toString().ifEmpty { null }
    }
}
