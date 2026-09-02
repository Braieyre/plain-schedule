package com.qingke.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 开学日期 ↔ 教务周次换算。
 * 教务周次恒以周一为一周之始，与「每周起始日」显示设置无关。
 */
object WeekMath {

    /** 把任意日期回退到所在自然周的周一。 */
    fun snapToMonday(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    /** [today] 处于第几教学周（从 1 起）。开学前返回 <=0，需由调用方钳制。 */
    fun currentWeek(startMonday: LocalDate, today: LocalDate): Int =
        ChronoUnit.WEEKS.between(startMonday, snapToMonday(today)).toInt() + 1

    /** 第 [week] 周、周 [day]（1=周一…7=周日）对应的日期。 */
    fun dateOf(startMonday: LocalDate, week: Int, day: Int): LocalDate =
        startMonday.plusWeeks((week - 1).toLong()).plusDays((day - 1).toLong())

    /** 已知「今天是第 N 周」，反推开学第一周的周一。 */
    fun startMondayFromCurrentWeek(today: LocalDate, week: Int): LocalDate =
        snapToMonday(today).minusWeeks((week - 1).toLong())

    /** 把周次列表压回连续区间，如 [1,2,3,4,5,6,8,9] -> [1..6, 8..9]。 */
    fun toRanges(weeks: List<Int>): List<IntRange> {
        if (weeks.isEmpty()) return emptyList()
        val sorted = weeks.distinct().sorted()
        val out = mutableListOf<IntRange>()
        var start = sorted.first()
        var prev = start
        for (w in sorted.drop(1)) {
            if (w != prev + 1) {
                out += start..prev
                start = w
            }
            prev = w
        }
        out += start..prev
        return out
    }

    /** 详情页用：[1,2,3,4,5,6,8,9] -> "第1-6周 | 第8-9周"（截图 03 的格式）。 */
    fun formatWeekRanges(weeks: List<Int>): String =
        toRanges(weeks).joinToString(" | ") { r ->
            if (r.first == r.last) "第${r.first}周" else "第${r.first}-${r.last}周"
        }
}
