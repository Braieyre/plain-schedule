package com.qingke.schedule

import com.qingke.schedule.model.*
import com.qingke.schedule.transfer.ScheduleTransfer
import org.junit.Assert.*
import org.junit.Test

class CourseChangesTest {
    private val course = Course("regular", "网络", day = 1, startPeriod = 1, endPeriod = 2, weeks = (1..16).toList())
    private val data = ScheduleData(Semester("学期", "2026-09-07", 20), listOf(course))
    @Test fun `临时调课只移走指定周次且其余十五周仍在原位`() {
        val changed = CourseChanges.apply(data, course.id, setOf(8), course.copy(day = 3, weeks = listOf(8), room = "新教室"))
        val original = changed.courses.single { it.id == course.id }
        assertEquals((1..16).filterNot { it == 8 }, original.weeks)
        assertEquals(1, original.day)
        assertEquals(listOf(8), changed.courses.single { it.day == 3 }.weeks)
        assertEquals(2, changed.courses.map { it.id }.distinct().size)
    }
    @Test fun `从某周起调整不动之前周次并支持跨周补课`() {
        val result = CourseChanges.apply(data, course.id, (8..16).toSet(), course.copy(day = 5, weeks = listOf(18)))
        assertEquals((1..7).toList(), result.courses.single { it.id == course.id }.weeks)
        assertEquals(listOf(18), result.courses.single { it.day == 5 }.weeks)
    }
    @Test fun `停课只取消选中的那一周`() {
        val result = CourseChanges.apply(data, course.id, setOf(8), null)
        assertEquals((1..16).filterNot { it == 8 }, result.courses.single().weeks)
    }
    @Test fun `全部调整后不会留下空原课程`() {
        val result = CourseChanges.apply(data, course.id, course.weeks.toSet(), course.copy(room = "A101"))
        assertEquals(1, result.courses.size)
        assertEquals("regular", result.courses.single().id)
        assertEquals("A101", result.courses.single().room)
    }
    @Test fun `不能取消原本不存在的周次`() {
        assertThrows(IllegalArgumentException::class.java) { CourseChanges.apply(data, course.id, setOf(20), null) }
    }
    @Test fun `冲突判断会剔除原位置但保留其他重叠课`() {
        val other = course.copy(id = "other", name = "数学", day = 3)
        assertTrue(CourseChanges.conflicts(data, course.copy(weeks = listOf(8)), course, setOf(8)).isEmpty())
        assertEquals(listOf("数学"), CourseChanges.conflicts(data.copy(courses = listOf(course, other)),
            course.copy(day = 3, weeks = listOf(8)), course, setOf(8)).map { it.name })
        assertTrue(CourseChanges.conflicts(data.copy(courses = listOf(other.copy(weeks = listOf(7)))),
            course.copy(day = 3, weeks = listOf(8)), null, emptySet()).isEmpty())
    }
    @Test fun `调课与待补充安排及自定义作息一起分享后无损还原`() {
        val pending = PendingCourse("pending", "待补课程", 4, 3, 4, 2, "周次缺失")
        val changed = CourseChanges.apply(data.copy(pendingCourses = listOf(pending)), course.id, setOf(8),
            course.copy(day = 3, weeks = listOf(8)))
        val times = DEFAULT_PERIOD_TIMES.map { if (it.index == 1) it.copy(start = "08:20", end = "09:05") else it }
        val restored = ScheduleTransfer.decode(ScheduleTransfer.encode(changed, times))
        assertEquals(changed, restored.schedule)
        assertEquals(times, restored.periodTimes)
    }
}
