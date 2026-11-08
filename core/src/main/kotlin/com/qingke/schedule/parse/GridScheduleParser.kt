package com.qingke.schedule.parse

import com.qingke.schedule.model.*
import kotlin.math.abs

/** 通用文字型网格：从星期列与节次行推断单元格，字段不明确时提示核对，不猜教师和教室。 */
internal object GridScheduleParser {
    private val period = Regex("""(?:第|\()?([0-9]{1,2})\s*[-,、]\s*([0-9]{1,2})\s*节\)?|(?:第|\()?([0-9]{1,2})\s*节\)?""")
    private val rowPeriod = Regex("""^\(?第?(\d{1,2})(?:\s*[-,、]\s*(\d{1,2}))?节?\)?$""")
    private val week = Regex("""(?:第)?(\d{1,2}(?:\s*-\s*\d{1,2})?(?:\s*[,、]\s*\d{1,2}(?:\s*-\s*\d{1,2})?)*)\s*周(?:\s*\(?([单双])周?\)?)?""")
    private val field = Regex("""(?:^|[/;\s])(?:上课)?(场地|教室|地点|教师|老师|任课教师|考核方式|考核)\s*:\s*([^/;\n]+)""")

    fun parse(spans: List<Span>): ParseResult {
        val warnings = mutableListOf<String>()
        val title = spans.firstNotNullOfOrNull { Regex("""\d{4}\s*-\s*\d{4}学年第?\d学期""").find(it.text)?.value }
        val anchors = spans.mapNotNull { s -> ScheduleParser.weekday(s.text)?.let { it to s } }.distinctBy { it.first }
        fun empty() = ParseResult(title, emptyList(), emptyList(), 0)
        if (anchors.size < 2) return empty()
        val isX = anchors.maxOf { it.second.x } - anchors.minOf { it.second.x } >=
            anchors.maxOf { it.second.y } - anchors.minOf { it.second.y }
        fun date(s: Span) = if (isX) s.x else s.y
        fun row(s: Span) = if (isX) s.y else s.x
        val sorted = anchors.sortedBy { date(it.second) }
        val gaps = sorted.zipWithNext { a, b -> date(b.second) - date(a.second) }.sorted()
        val width = gaps[gaps.size / 2]
        if (width <= 1f) return empty()
        val header = anchors.map { row(it.second) }.average().toFloat()
        val left = date(sorted.first().second) - width / 2
        val rowLabels = spans.filter { date(it) < left && row(it) < header }
            .mapNotNull { s -> rowPeriod.matchEntire(s.text.trim())?.let { m ->
                val start = m.groupValues[1].toInt()
                val end = m.groupValues[2].toIntOrNull() ?: start
                if (start in 1..12 && end in start..12) Triple(row(s), start, end) else null
            } }.sortedByDescending { it.first }
        val courses = mutableListOf<Course>()
        for ((day, anchor) in anchors) {
            val column = spans.filter { row(it) < header - 1 && abs(date(it) - date(anchor)) < width / 2 }
                .sortedWith(compareByDescending<Span> { row(it) }.thenBy { date(it) })
            val groups: List<Pair<List<Span>, Pair<Int, Int>?>> = if (rowLabels.isNotEmpty()) {
                column.groupBy { s -> rowLabels.minBy { abs(it.first - row(s)) } }
                    .map { (label, items) -> items to (label.second to label.third) }
            } else {
                val chunks = mutableListOf<MutableList<Span>>()
                for (s in column) {
                    val previous = chunks.lastOrNull()?.lastOrNull()
                    if (previous == null || row(previous) - row(s) > maxOf(previous.size, s.size, 6f) * 2.2f) chunks += mutableListOf<Span>()
                    chunks.last() += s
                }
                chunks.map { it.toList() to null }
            }
            for ((items, rowRange) in groups) {
                val lines = items.groupBy { (row(it) * 2).toInt() }.values.map { line -> line.sortedBy { date(it) }.joinToString("") { it.text } }
                val text = lines.joinToString("\n")
                val weeks = weeks(text)
                if (weeks.isEmpty()) {
                    if (text.any { it.isLetter() } && !text.startsWith("备注") && !text.startsWith("说明")) {
                        warnings += "星期${"一二三四五六日"[day - 1]}有内容缺少可识别周次，已跳过，请核对"
                    }
                    continue
                }
                val pm = period.find(text)
                val range = pm?.let { if (it.groupValues[3].isNotEmpty()) it.groupValues[3].toInt().let { n -> n to n }
                    else it.groupValues[1].toInt() to it.groupValues[2].toInt() } ?: rowRange
                if (range == null || range.first !in 1..12 || range.second !in range.first..12) {
                    warnings += "星期${"一二三四五六日"[day - 1]}有课程节次无法确定，已跳过，请核对"
                    continue
                }
                // 一个单元格内存在多个时间安排时不合并成一门，避免静默错配。
                if (period.findAll(text).count() > 1 || week.findAll(text).count() > 1 && lines.count { week.containsMatchIn(it) } > 1) {
                    warnings += "星期${"一二三四五六日"[day - 1]}第 ${range.first} 节含多个安排，已跳过，请手动补充"
                    continue
                }
                val fields = field.findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim() }
                val nameLines = lines.takeWhile { line -> !week.containsMatchIn(line) && !period.containsMatchIn(line) && !field.containsMatchIn(line) }
                var name = nameLines.joinToString("").trim()
                if (name.isEmpty()) name = text.substringBefore(pm?.value ?: week.find(text)!!.value).trim()
                name = name.trim(' ', '/', '(', ')', '[', ']')
                if (name.isBlank() || field.containsMatchIn(name)) {
                    warnings += "星期${"一二三四五六日"[day - 1]}第 ${range.first} 节缺少可确定的课名，已跳过"
                    continue
                }
                val marker = name.takeLast(1).takeIf { it in listOf("★", "●", "▲", "■") }.orEmpty()
                if (marker.isNotEmpty()) name = name.dropLast(1).trim()
                val room = listOf("场地", "教室", "地点").firstNotNullOfOrNull { fields[it] }.orEmpty()
                val teachers = listOf("教师", "老师", "任课教师").firstNotNullOfOrNull { fields[it] }
                    ?.split(Regex("[,、]"))?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                courses += Course("grid-${courses.size}", name, marker, day, range.first, range.second, weeks, room, teachers,
                    fields["考核方式"] ?: fields["考核"])
            }
        }
        if (courses.isNotEmpty()) warnings.add(0, "已按通用表格格式识别，请逐项核对课程、周次和节次；未标注的教室、教师需手动补充")
        return ParseResult(title, courses, emptyList(), courses.flatMap { it.weeks }.maxOrNull() ?: 0, warnings.distinct())
    }

    private fun weeks(text: String): List<Int> {
        val result = sortedSetOf<Int>()
        for (m in week.findAll(text)) {
            val parity = m.groupValues[2]
            for (segment in m.groupValues[1].split(Regex("[,、]"))) {
                val range = segment.split('-').map { it.trim().toIntOrNull() }
                val start = range.firstOrNull() ?: continue
                val end = range.lastOrNull() ?: continue
                if (start !in 1..52 || end !in start..52) continue
                result += (start..end).filter { parity != "单" || it % 2 == 1 }.filter { parity != "双" || it % 2 == 0 }
            }
        }
        return result.toList()
    }
}
