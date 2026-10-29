package cn.jiangcheng.lemon.reader

import android.graphics.RectF
import android.text.StaticLayout
import kotlin.math.max
import kotlin.math.min

/**
 * One source of truth for every visible text range in the reader.
 *
 * StaticLayout.getSelectionPath() deliberately fills the space between the
 * first and last line.  That is useful for platform text widgets, but it makes
 * justified novel text look like one large rectangle.  The reader instead
 * paints only the glyph span that is actually selected on each line.  Active
 * selections and saved marks both use this class so their geometry cannot
 * drift apart again.
 */
internal object TextRangeGeometry {
    data class LineBox(
        val line: Int,
        val start: Int,
        val end: Int,
        val rect: RectF
    )

    fun lineBoxes(layout: StaticLayout, start: Int, end: Int): List<LineBox> {
        if (layout.text.isEmpty()) return emptyList()
        val safeStart = start.coerceIn(0, layout.text.length)
        val safeEnd = end.coerceIn(safeStart, layout.text.length)
        if (safeEnd <= safeStart) return emptyList()

        val result = ArrayList<LineBox>()
        var firstLine = layout.getLineForOffset(safeStart.coerceAtMost(layout.text.length - 1))
        // At a soft-wrap boundary Android may give the trailing affinity of
        // the previous line. A selection that starts at that offset belongs
        // to the next visible line.
        if(firstLine<layout.lineCount-1&&safeStart==layout.getLineStart(firstLine+1))firstLine++
        val lastLine = layout.getLineForOffset((safeEnd - 1).coerceAtLeast(safeStart))
        val metrics = layout.paint.fontMetrics
        for (line in firstLine..lastLine) {
            var lineStart = max(safeStart, layout.getLineStart(line))
            var lineEnd = min(safeEnd, layout.getLineEnd(line))

            // Line endings and padding spaces are logical selection content,
            // but they have no visible glyph and must not enlarge the paint.
            while (lineEnd > lineStart && ReaderDisplayText.isPresentationWhitespace(layout.text[lineEnd - 1])) lineEnd--
            while (lineStart < lineEnd && ReaderDisplayText.isPresentationWhitespace(layout.text[lineStart])) lineStart++
            if (lineEnd <= lineStart) continue

            // getLineLeft() is the paragraph box boundary. On the first line
            // it deliberately excludes LeadingMarginSpan, so using it paints
            // the two-em visual indent as if it were selected text. The
            // horizontal position of the actual first glyph is the only
            // correct anchor for both the highlight and its selection handle.
            val startX = JustifiedTextCoordinates.horizontal(layout, line, lineStart)
            val endX = JustifiedTextCoordinates.horizontal(layout, line, lineEnd, endEdge = true)
            val baseline = layout.getLineBaseline(line).toFloat()
            result += LineBox(
                line,
                lineStart,
                lineEnd,
                RectF(min(startX, endX), baseline + metrics.ascent,
                    max(startX, endX), baseline + metrics.descent)
            )
        }
        return result
    }

    fun bounds(layout: StaticLayout, start: Int, end: Int): RectF {
        val boxes = lineBoxes(layout, start, end)
        if (boxes.isEmpty()) return RectF()
        return RectF(boxes.first().rect).also { bounds ->
            for (index in 1 until boxes.size) bounds.union(boxes[index].rect)
        }
    }
}
