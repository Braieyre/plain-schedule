package com.qingke.schedule

import com.qingke.schedule.parse.ScheduleParser
import com.qingke.schedule.pdf.PdfText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Planner 收口用的端到端冒烟测试：真实 PDF -> PdfText -> ScheduleParser。
 * T2 被中断前未写测试，这里先钉住最关键的几条真值。
 */
class ScheduleParserSmokeTest {

    private fun result() = ScheduleParser.parse(
        PdfText.extract(javaClass.getResourceAsStream("/sample-schedule.pdf")!!.readBytes())
    )

    @Test
    fun 端到端解析出17门课5门实践1门其他() {
        val r = result()
        println("== 标题: ${r.semesterTitle}  maxWeek=${r.maxWeek}  warnings=${r.warnings}")
        r.courses.sortedWith(compareBy({ it.day }, { it.startPeriod })).forEach {
            println("  day=${it.day} ${it.startPeriod}-${it.endPeriod} ${it.name}[${it.marker}] " +
                "weeks=${it.weeks} room=${it.room} teachers=${it.teachers} exam=${it.exam}")
        }
        r.flexCourses.forEach { println("  FLEX[${it.kind}] ${it.name} weeks=${it.weeks} room=${it.room}") }
        assertEquals("固定节次课门数", 17, r.courses.size)
        assertEquals("实践课门数", 5, r.flexCourses.count { it.kind == "实践" })
        assertEquals("其他课门数", 1, r.flexCourses.count { it.kind == "其他" })
        assertEquals("2026-2027学年第1学期", r.semesterTitle)
        assertEquals(19, r.maxWeek)
    }

    @Test
    fun 周二五六节Linux字段全对() {
        val c = result().courses.first { it.day == 2 && it.startPeriod == 5 }
        assertEquals("Linux系统程序设计S", c.name)
        assertEquals("★", c.marker)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 8, 9), c.weeks)
        assertEquals("XX-226", c.room)
        assertEquals(listOf("吴己"), c.teachers)
        assertEquals("考查", c.exam)
    }

    @Test
    fun 跨行长课名完整还原() {
        val c = result().courses.first { it.day == 5 && it.startPeriod == 1 }
        assertEquals("习近平新时代中国特色社会主义思想概论", c.name)
    }

    @Test
    fun 第2周可见11门非本周6门对齐截图() {
        val cs = result().courses
        assertEquals("第2周可见课程数（截图01）", 11, cs.count { it.activeIn(2) })
        assertEquals("第2周非本周课程数（截图05）", 6, cs.count { !it.activeIn(2) })
    }
}
