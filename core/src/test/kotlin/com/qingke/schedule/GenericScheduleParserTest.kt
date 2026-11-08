package com.qingke.schedule

import com.qingke.schedule.model.*
import com.qingke.schedule.parse.ScheduleParser
import com.qingke.schedule.pdf.PdfText
import org.junit.Assert.*
import org.junit.Test

class GenericScheduleParserTest {
    private fun span(x: Float, y: Float, text: String, page: Int = 1) = Span(page, x, y, 10f, text)
    private fun headers(page: Int = 1) = (1..5).map { span(100f * it, 700f, "周${"一二三四五"[it - 1]}", page) }
    private fun fixture(page: Int = 1) = headers(page) + listOf(
        span(20f, 650f, "1-2节", page), span(20f, 550f, "3-4节", page),
        span(80f, 675f, "高等数学", page), span(80f, 660f, "１～１６周（单）", page),
        span(80f, 645f, "教室：A101", page), span(80f, 630f, "任课教师：张老师", page),
        span(180f, 575f, "大学英语", page), span(180f, 560f, "2-16周(双)", page),
        span(180f, 545f, "地点:B202", page), span(180f, 530f, "老师:李老师", page),
    )
    @Test fun `不同网格格式PDF文字提取到课表端到端通过`() {
        val content = fixture().joinToString("\n") { span ->
            val hex = span.text.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02x".format(it.toInt() and 255) }
            "BT /F1 10 Tf 1 0 0 1 ${span.x} ${span.y} Tm <$hex> Tj ET"
        }
        val pdf = ("%PDF-1.4\n1 0 obj <</Type/Catalog/Pages 2 0 R>> endobj\n" +
            "2 0 obj <</Type/Pages/Count 1/Kids[3 0 R]>> endobj\n" +
            "3 0 obj <</Type/Page/Parent 2 0 R/MediaBox[0 0 600 800]/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>> endobj\n" +
            "4 0 obj <</Type/Font/Subtype/Type0/Encoding/UniGB-UCS2-H>> endobj\n" +
            "5 0 obj <</Length ${content.length}>>\nstream\n$content\nendstream\nendobj").toByteArray(Charsets.US_ASCII)
        val result = ScheduleParser.parse(PdfText.extract(pdf))
        assertEquals(listOf("高等数学", "大学英语"), result.courses.sortedBy { it.day }.map { it.name })
        assertEquals(listOf("A101", "B202"), result.courses.sortedBy { it.day }.map { it.room })
    }
    @Test fun `只有五天表头且节次在行标题也能识别`() {
        val result = ScheduleParser.parse(fixture())
        assertEquals(2, result.courses.size)
        val math = result.courses.first { it.day == 1 }
        assertEquals("高等数学", math.name)
        assertEquals(1, math.startPeriod); assertEquals(2, math.endPeriod)
        assertEquals((1..16 step 2).toList(), math.weeks)
        assertEquals("A101", math.room); assertEquals(listOf("张老师"), math.teachers)
        val english = result.courses.first { it.day == 2 }
        assertEquals(3, english.startPeriod); assertEquals((2..16 step 2).toList(), english.weeks)
        assertTrue(result.warnings.isNotEmpty())
    }
    @Test fun `多页独立分列重复页去重不同周次保留`() {
        val page2 = fixture(2).map { if (it.text == "１～１６周（单）") it.copy(text = "17-20周") else it }
        val result = ScheduleParser.parse(fixture() + fixture(3) + page2)
        assertEquals(3, result.courses.size)
        assertEquals(20, result.maxWeek)
        assertEquals(3, result.courses.map { it.id }.distinct().size)
    }
    @Test fun `单元格显式节次与空格表头`() {
        val result = ScheduleParser.parse(listOf(span(100f, 700f, "星 期 一"), span(200f, 700f, "星期二"),
            span(80f, 650f, "线性代数"), span(80f, 635f, "第3-4节"), span(80f, 620f, "1-8周"), span(80f, 605f, "教师:王老师/场地:C303")))
        assertEquals("线性代数", result.courses.single().name)
        assertEquals(3, result.courses.single().startPeriod)
    }
    @Test fun `缺周次不猜测缺失页会提示`() {
        val result = ScheduleParser.parse(fixture() + headers(2) + span(80f, 650f, "没有周次的课程", 2))
        assertEquals(2, result.courses.size)
        assertTrue(result.warnings.any { "第 2 页" in it })
    }
    @Test fun `不识别的文件返回原始文字便于诊断`() {
        val error = assertThrows(PdfUnsupportedException::class.java) { ScheduleParser.parse(listOf(span(20f, 600f, "陌生格式"))) }
        assertTrue(error.rawText.contains("陌生格式"))
    }
    @Test fun `超出周次限制不进行巨大范围展开`() {
        assertThrows(PdfUnsupportedException::class.java) { ScheduleParser.parse(headers() + listOf(span(80f, 650f, "测试"), span(80f, 635f, "(1-2节)"), span(80f, 620f, "1-99周"))) }
    }
}
