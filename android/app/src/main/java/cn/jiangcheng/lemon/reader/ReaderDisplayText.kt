package cn.jiangcheng.lemon.reader

/**
 * Whitespace that belongs to the source file but should not consume visible
 * width in the reader.  The ranges use UTF-16 offsets and keep the source
 * text untouched so persisted notes and searches remain addressable.
 */
internal data class DisplayWhitespaceRange(val start: Int, val end: Int)

internal object ReaderDisplayText {
    fun isPresentationWhitespace(ch: Char): Boolean =
        ch.isWhitespace() || Character.isSpaceChar(ch) || ch in "\uFEFF\u200B\u2060"

    /**
     * Returns leading and trailing whitespace ranges for every source line.
     * Newline characters themselves are deliberately excluded from the ranges.
     */
    fun edgeWhitespaceRanges(source: String): List<DisplayWhitespaceRange> {
        if (source.isEmpty()) return emptyList()
        val result = ArrayList<DisplayWhitespaceRange>()
        var lineStart = 0
        while (lineStart <= source.length) {
            val newline = source.indexOf('\n', lineStart)
            val lineEnd = if (newline >= 0) newline else source.length
            if (lineEnd > lineStart) {
                var left = lineStart
                while (left < lineEnd && isPresentationWhitespace(source[left])) left++
                var right = lineEnd
                while (right > left && isPresentationWhitespace(source[right - 1])) right--
                if (left == right) {
                    result += DisplayWhitespaceRange(lineStart, lineEnd)
                } else {
                    if (left > lineStart) result += DisplayWhitespaceRange(lineStart, left)
                    if (right < lineEnd) result += DisplayWhitespaceRange(right, lineEnd)
                }
            }
            if (newline < 0) break
            lineStart = newline + 1
        }
        return result
    }
}
