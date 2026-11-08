package com.qingke.schedule

import com.qingke.schedule.model.*
import com.qingke.schedule.transfer.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScheduleTransferTest {
    private val schedule = ScheduleData(Semester("手动修改后的学期", "2026-09-07", 22),
        listOf(Course("edited-id", "新增课程", "★", 7, 11, 12, listOf(1, 3, 5, 22), "新教室", listOf("教师甲", "教师乙"), "考查")),
        listOf(FlexCourse("flex", "实践周", weeks = listOf(20, 21), kind = "实践")))
    private val times = DEFAULT_PERIOD_TIMES.map { if (it.index == 1) it.copy(start = "08:15", end = "09:00") else it }

    @Test fun `当前数据与作息导出再导入所有字段无损`() {
        val restored = ScheduleTransfer.decode(ScheduleTransfer.encode(schedule, times))
        assertEquals(schedule, restored.schedule)
        assertEquals(times, restored.periodTimes)
    }
    @Test fun `旧版裸JSON也能导入且不覆盖作息`() {
        val old = Json.encodeToString(ScheduleData.serializer(), schedule)
        assertEquals(schedule, ScheduleTransfer.decode("\uFEFF$old").schedule)
        assertNull(ScheduleTransfer.decode(old).periodTimes)
    }
    @Test fun `空课表可以备份还原`() {
        val empty = schedule.copy(courses = emptyList(), flexCourses = emptyList())
        assertEquals(empty, ScheduleTransfer.decode(ScheduleTransfer.encode(empty, times)).schedule)
    }
    @Test fun `新字段兼容但未知版本拒绝`() {
        val json = ScheduleTransfer.encode(schedule, times)
        assertEquals(schedule, ScheduleTransfer.decode(json.replace("\"version\": 1", "\"extra\": true, \"version\": 1")).schedule)
        assertThrows(IllegalArgumentException::class.java) { ScheduleTransfer.decode(json.replace("\"version\": 1", "\"version\": 99")) }
    }
    @Test fun `不接受外来格式或缺失格式标志`() {
        val json = ScheduleTransfer.encode(schedule, times)
        assertThrows(IllegalArgumentException::class.java) { ScheduleTransfer.decode(json.replace("plain-schedule", "other-app")) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleTransfer.decode(json.replace("\"format\": \"plain-schedule\",", "")) }
    }
    @Test fun `无效日期星期节次周次和重复ID均拒绝`() {
        val invalid = listOf(
            schedule.copy(semester = schedule.semester.copy(startMonday = "2026-09-08")),
            schedule.copy(semester = schedule.semester.copy(startMonday = "bad-date")),
            schedule.copy(semester = schedule.semester.copy(totalWeeks = 53)),
            schedule.copy(courses = listOf(schedule.courses[0].copy(day = 0))),
            schedule.copy(courses = listOf(schedule.courses[0].copy(endPeriod = 13))),
            schedule.copy(courses = listOf(schedule.courses[0].copy(endPeriod = 10))),
            schedule.copy(courses = listOf(schedule.courses[0].copy(weeks = listOf(23)))),
            schedule.copy(courses = listOf(schedule.courses[0].copy(weeks = emptyList()))),
            schedule.copy(courses = schedule.courses + schedule.courses),
            schedule.copy(flexCourses = listOf(schedule.flexCourses[0].copy(id = "edited-id"))),
        )
        invalid.forEach { data -> assertThrows(IllegalArgumentException::class.java) { ScheduleTransfer.validate(ScheduleArchive(schedule = data)) } }
    }
    @Test fun `损坏JSON和缺失必要信息均有可读错误`() {
        for (input in listOf("{", "[]", "")) {
            val error = assertThrows(IllegalArgumentException::class.java) { ScheduleTransfer.decode(input) }
            assertTrue(error.message!!.contains("有效的 JSON"))
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            ScheduleTransfer.decode("""{"format":"plain-schedule","version":1}""")
        }
        assertTrue(error.message!!.contains("缺少必要信息"))
    }
    @Test fun `无效和重复作息不能导入`() {
        for (invalid in listOf(times.drop(1), times + times[0], times.map { it.copy(start = "99:00") }, times.map { it.copy(end = it.start) })) {
            assertThrows(IllegalArgumentException::class.java) { ScheduleTransfer.validate(ScheduleArchive(schedule = schedule, periodTimes = invalid)) }
        }
    }
}
