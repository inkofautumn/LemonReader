package cn.jiangcheng.lemon.reader

import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Caret coordinates for inter-character justified text.
 *
 * Android 15 draws a justified TextLine after distributing extra letter
 * spacing, but Layout.getPrimaryHorizontal() and getOffsetForHorizontal() use
 * an un-justified TextLine.  Keeping those APIs for selection therefore puts
 * a dragged handle inside a glyph and makes the visual right edge
 * unreachable.  This class builds the same visible boundary sequence once
 * per line and uses it for painting, hit testing and handle placement.
 */
internal object JustifiedTextCoordinates {
    private data class LineMap(val offsets: IntArray, val x: FloatArray)
    private val cache = WeakHashMap<StaticLayout, MutableMap<Int, LineMap?>>()

    private fun map(layout: StaticLayout, line: Int): LineMap? {
        if (Build.VERSION.SDK_INT < 35 ||
            layout.justificationMode != Layout.JUSTIFICATION_MODE_INTER_CHARACTER ||
            layout.getParagraphDirection(line) != Layout.DIR_LEFT_TO_RIGHT
        ) return null
        val lineEnd = layout.getLineEnd(line)
        // Matches Layout.isJustificationRequired(). Paragraph-final lines are
        // drawn at their natural width and need no correction.
        if (lineEnd >= layout.text.length || lineEnd <= 0 || layout.text[lineEnd - 1] == '\n') return null
        synchronized(cache) {
            val lines = cache.getOrPut(layout) { mutableMapOf() }
            if (line in lines) return lines[line]
            val value = buildMap(layout, line)
            lines[line] = value
            return value
        }
    }

    private fun buildMap(layout: StaticLayout, line: Int): LineMap? {
        val text = layout.text
        var visibleStart = layout.getLineStart(line)
        var visibleEnd = layout.getLineVisibleEnd(line)
        while (visibleStart < visibleEnd && ReaderDisplayText.isPresentationWhitespace(text[visibleStart])) visibleStart++
        while (visibleEnd > visibleStart && ReaderDisplayText.isPresentationWhitespace(text[visibleEnd - 1])) visibleEnd--
        if (visibleEnd <= visibleStart) return null

        val boundaries = ArrayList<Int>()
        boundaries += visibleStart
        var at = visibleStart
        while (at < visibleEnd) {
            var next = layout.paint.getTextRunCursor(
                text, visibleStart, visibleEnd, false, at, Paint.CURSOR_AFTER
            )
            if (next <= at || next > visibleEnd) {
                next = Character.offsetByCodePoints(text, at, 1).coerceAtMost(visibleEnd)
            }
            at = next
            boundaries += at
        }
        if (boundaries.size < 2) return null

        val origin = layout.getPrimaryHorizontal(visibleStart)
        val naturalWidth = layout.paint.getRunAdvance(
            text, visibleStart, visibleEnd, visibleStart, visibleEnd, false, visibleEnd
        )
        val targetEnd = layout.getLineRight(line)
        val extra = targetEnd - origin - naturalWidth
        if (extra <= 0.01f) return null

        val unitCount = layout.getLineLetterSpacingUnitCount(line, false).coerceAtLeast(2)
        val gapCount = (unitCount - 1).toFloat()
        val boundaryGaps = (boundaries.size - 1).toFloat()
        val positions = FloatArray(boundaries.size)
        boundaries.forEachIndexed { index, offset ->
            val natural = if (offset == visibleStart) 0f else layout.paint.getRunAdvance(
                text, visibleStart, visibleEnd, visibleStart, visibleEnd, false, offset
            )
            // Cursor boundaries and letter-spacing units are identical for
            // ordinary CJK novel text. Scaling also keeps the final boundary
            // exact for the occasional shaping cluster or emoji sequence.
            val gapsBefore = index * gapCount / boundaryGaps
            positions[index] = origin + natural + extra * gapsBefore / gapCount
        }
        positions[positions.lastIndex] = targetEnd
        return LineMap(boundaries.toIntArray(), positions)
    }

    fun horizontal(layout: StaticLayout, line: Int, offset: Int, endEdge: Boolean = false): Float {
        val lineMap = map(layout, line)
        if (lineMap != null) {
            val exact = lineMap.offsets.binarySearch(offset)
            if (exact >= 0) return lineMap.x[exact]
            val insertion = -exact - 1
            val index = when {
                insertion <= 0 -> 0
                insertion >= lineMap.offsets.size -> lineMap.offsets.lastIndex
                endEdge -> insertion
                else -> insertion - 1
            }
            return lineMap.x[index]
        }
        return if (endEdge && offset == layout.getLineEnd(line)) layout.getLineRight(line)
        else layout.getPrimaryHorizontal(offset)
    }

    fun offsetForHorizontal(layout: StaticLayout, line: Int, horizontal: Float): Int {
        val lineMap = map(layout, line) ?: return layout.getOffsetForHorizontal(line, horizontal)
        if (horizontal <= lineMap.x.first()) return lineMap.offsets.first()
        if (horizontal >= lineMap.x.last()) return lineMap.offsets.last()
        var low = 0
        var high = lineMap.x.lastIndex
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (lineMap.x[mid] < horizontal) low = mid else high = mid
        }
        return if (abs(horizontal - lineMap.x[low]) <= abs(lineMap.x[high] - horizontal))
            lineMap.offsets[low] else lineMap.offsets[high]
    }

    /** Exposed to the Android regression test without exposing cache internals. */
    fun boundaries(layout: StaticLayout, line: Int): List<Pair<Int, Float>> {
        val lineMap = map(layout, line) ?: return emptyList()
        return lineMap.offsets.indices.map { lineMap.offsets[it] to lineMap.x[it] }
    }
}
