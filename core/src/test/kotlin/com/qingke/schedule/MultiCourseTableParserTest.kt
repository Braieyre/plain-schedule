package com.qingke.schedule

import com.qingke.schedule.model.*
import com.qingke.schedule.parse.ScheduleParser
import org.junit.Assert.*
import org.junit.Test

/** 只使用人工构造的匿名片段；用户原始 PDF 不进入公共测试资源。 */
class MultiCourseTableParserTest {
    private val bounds = SpanBounds(80f, 100f, 180f, 690f)
    private fun span(text: String, y: Float, page: Int = 1, clip: SpanBounds? = bounds) =
        Span(page, 85f, y, 10f, text, clip = clip)
    private fun headers(page: Int = 1) = listOf(
        Span(page, 115f, 700f, 10f, "星期一", 30f), Span(page, 215f, 700f, 10f, "星期二", 30f))
    private fun course(name: String, weeks: String, period: String, y: Float, page: Int = 1, clip: SpanBounds? = bounds) = listOf(
        span(name + '\u0001', y, page, clip),
        span("教师甲;$weeks;$period\u0001", y - 15, page, clip),
        span("1号教学楼(A101)\u0001", y - 30, page, clip),
    )

    @Test fun `同格多门和不重复表头的续页逐条保留`() {
        val result = ScheduleParser.parse(headers() + course("课程甲", "1,3-5周", "1-2节", 650f) +
            course("课程乙", "6-8周", "1-2节", 580f) + course("课程丙", "10-12周", "3-4节", 650f, 2))
        assertEquals(listOf("课程甲", "课程乙", "课程丙"), result.courses.map { it.name })
        assertEquals(listOf(1, 3, 4, 5), result.courses[0].weeks)
        assertEquals(3, result.courses[2].startPeriod)
        assertTrue(result.courses.all { it.day == 1 })
        assertTrue(result.warnings.any { "沿用前页" in it })
    }

    @Test fun `逐字表头与英文词间空格从坐标恢复`() {
        val head = "星期一".mapIndexed { i, c -> Span(1, 115f + i * 10, 700f, 10f, "$c", 10f) } +
            "星期二".mapIndexed { i, c -> Span(1, 215f + i * 10, 700f, 10f, "$c", 10f) }
        val chars = "Data".mapIndexed { i, c -> Span(1, 85f + i * 5, 650f, 10f, "$c", 5f, bounds) } +
            "Network".mapIndexed { i, c -> Span(1, 109f + i * 5, 650f, 10f, "$c", 5f, bounds) } +
            Span(1, 144f, 650f, 10f, "\u0001", clip = bounds)
        val result = ScheduleParser.parse(head + chars + course("unused", "1-8周", "1-2节", 650f).drop(1))
        assertEquals("Data Network", result.courses.single().name)
    }

    @Test fun `相邻裁剪单元格即使缺换行也不会把课名吞进教室`() {
        val upper = SpanBounds(80f, 400f, 180f, 690f)
        val lower = SpanBounds(80f, 100f, 180f, 400f)
        val data = headers() + listOf(span("课程甲\u0001", 450f, clip = upper),
            span("教师甲;1-8周;1-2节\u0001", 430f, clip = upper), span("1号教学楼(A101)", 405f, clip = upper)) +
            course("课程乙", "9-16周", "3-4节", 399f, clip = lower)
        val result = ScheduleParser.parse(data)
        assertEquals(listOf("课程甲", "课程乙"), result.courses.map { it.name })
        assertEquals("1号教学楼(A101)", result.courses[0].room)
    }

    @Test fun `损坏的中间安排不能污染下一门课`() {
        val data = headers() + course("正常甲", "1-8周", "1-2节", 650f) +
            course("损坏课", "5周", "78节", 580f) + course("正常乙", "9-16周", "3-4节", 500f)
        val result = ScheduleParser.parse(data)
        assertEquals(listOf("正常甲", "正常乙"), result.courses.map { it.name })
        assertTrue(result.warnings.any { "不完整" in it })
    }

    @Test fun `尾部课名没有周次节次时不猜测`() {
        val result = ScheduleParser.parse(headers() + course("完整课", "1-8周", "1-2节", 650f) + span("被截断课程\u0001", 500f))
        assertEquals(1, result.courses.size)
        assertTrue(result.warnings.any { "被截断课程" in it })
        assertEquals("被截断课程", result.pendingCourses.single().name)
        assertEquals(1, result.pendingCourses.single().day)
    }

    @Test fun `异常字符明确标记且不影响其余字段`() {
        val data = headers() + course("课程\u0000双语\u0000", "1-8周", "1-2节", 650f)
        val result = ScheduleParser.parse(data)
        assertEquals("课程�双语�", result.courses.single().name)
        assertEquals(listOf("教师甲"), result.courses.single().teachers)
        assertTrue(result.warnings.any { "无法解码" in it })
    }

    @Test fun `超出周次范围和反向节次均跳过`() {
        val result = ScheduleParser.parse(headers() + course("有效课", "1-8周", "1-2节", 650f) +
            course("错误周次", "1-99周", "3-4节", 580f) + course("错误节次", "1-8周", "4-3节", 500f))
        assertEquals(listOf("有效课"), result.courses.map { it.name })
        assertTrue(result.warnings.any { "无效" in it })
    }

    @Test fun `节次缺字但同一表格行有明确小节范围时可以补全`() {
        val labelClip = bounds.copy(left = 30f, right = 80f)
        val label = Span(1, 35f, 400f, 10f, "03\u000004小节\u0001", clip = labelClip)
        val result = ScheduleParser.parse(headers() + label + course("缺字课", "1-8周", "节", 650f) +
            course("缺连字符课", "9-16周", "34节", 580f))
        assertEquals(2, result.courses.size)
        assertTrue(result.courses.all { it.startPeriod == 3 && it.endPeriod == 4 })
        assertEquals(2, result.warnings.count { "补全" in it })
    }

    @Test fun `单节课程的节次不会被当作缺失`() {
        val result = ScheduleParser.parse(headers() + course("单节课", "1-8周", "5节", 650f))
        assertEquals(5, result.courses.single().startPeriod)
        assertEquals(5, result.courses.single().endPeriod)
    }

    @Test fun `重复页面去重同时保留不同周次安排`() {
        val data = headers() + course("课程甲", "1-8周", "1-2节", 650f)
        val result = ScheduleParser.parse(data + data.map { it.copy(page = 2) } + course("课程甲", "9-16周", "1-2节", 650f, 3))
        assertEquals(2, result.courses.size)
        assertEquals(2, result.courses.map { it.id }.distinct().size)
    }
}
