package com.qingke.schedule

import com.qingke.schedule.model.Course
import com.qingke.schedule.model.ScheduleData
import java.util.UUID

/** 将指定周次从原安排中拆出；调课不会改变未选中的周次，也不会覆盖同名加课。 */
object CourseChanges {
    fun apply(data: ScheduleData, id: String, sourceWeeks: Set<Int>, replacement: Course?): ScheduleData {
        val original = data.courses.single { it.id == id }
        require(sourceWeeks.isNotEmpty() && original.weeks.containsAll(sourceWeeks)) { "请选择原课程实际开课的周次" }
        val remaining = original.weeks.filterNot { it in sourceWeeks }
        val list = data.courses.filterNot { it.id == id }.toMutableList()
        if (remaining.isNotEmpty()) list += original.copy(weeks = remaining)
        replacement?.let {
            require(it.weeks.isNotEmpty() && it.weeks.all { w -> w in 1..52 })
            require(it.day in 1..7 && it.startPeriod in 1..12 && it.endPeriod in it.startPeriod..12)
            list += it.copy(id = if (remaining.isEmpty()) id else UUID.randomUUID().toString())
        }
        return data.copy(courses = list.sortedWith(compareBy({ it.day }, { it.startPeriod })),
            semester = data.semester.copy(totalWeeks = maxOf(data.semester.totalWeeks, list.flatMap { it.weeks }.maxOrNull() ?: 1)))
    }

    fun conflicts(data: ScheduleData?, course: Course, original: Course?, sourceWeeks: Set<Int>): List<Course> =
        data?.courses.orEmpty().map { if (it.id == original?.id) it.copy(weeks = it.weeks.filterNot(sourceWeeks::contains)) else it }
            .filter { it.day == course.day && it.startPeriod <= course.endPeriod && it.endPeriod >= course.startPeriod && it.weeks.any(course.weeks::contains) }
}
