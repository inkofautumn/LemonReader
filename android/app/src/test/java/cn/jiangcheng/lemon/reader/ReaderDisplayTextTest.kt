package cn.jiangcheng.lemon.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDisplayTextTest {
    @Test fun edgeRangesKeepSourceOffsetsAndHideBothSides() {
        val source = "\u3000\u3000正文。  \n\t第二行\u00A0\n"
        val ranges = ReaderDisplayText.edgeWhitespaceRanges(source)
        assertEquals(
            listOf(
                DisplayWhitespaceRange(0, 2),
                DisplayWhitespaceRange(5, 7),
                DisplayWhitespaceRange(8, 9),
                DisplayWhitespaceRange(12, 13)
            ),
            ranges
        )
        assertTrue(ranges.all { it.start >= 0 && it.end <= source.length && it.start < it.end })
    }

    @Test fun line4567SampleHasOnlyLeadingFullWidthSpaces() {
        val line = "\u3000\u3000着陆下地之后，更是觉得如身在旷野，宽阔无垠。"
        val ranges = ReaderDisplayText.edgeWhitespaceRanges(line)
        assertEquals(listOf(DisplayWhitespaceRange(0, 2)), ranges)
        assertTrue(line.endsWith('。'))
        assertTrue(!ReaderDisplayText.isPresentationWhitespace(line.last()))
    }
}
