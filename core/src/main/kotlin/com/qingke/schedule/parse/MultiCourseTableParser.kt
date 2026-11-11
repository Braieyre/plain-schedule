package com.qingke.schedule.parse

import com.qingke.schedule.model.Course
import com.qingke.schedule.model.ParseResult
import com.qingke.schedule.model.PendingCourse
import com.qingke.schedule.model.Span
import com.qingke.schedule.model.SpanBounds
import kotlin.math.abs

/** 同格多课格式：课名、教师;周次;节次、教室。保留 PDF 显式换行，允许续页不重复星期。 */
internal object MultiCourseTableParser {
    private val metadata = Regex("""^([^;\n]+);\s*([\d,、\s-]+周(?:\([单双]周?\))?);\s*([^;]*节.*)$""")
    private val period = Regex("""^(\d{1,2})(?:\s*-\s*(\d{1,2}))?节(.*)$""")
    private data class RowHint(val clip: SpanBounds, val start: Int, val end: Int)
    private data class Header(val day: Int, val span: Span) {
        val center get() = span.x + span.width / 2
    }

    fun parse(spans: List<Span>): ParseResult? {
        var previousHeaders = emptyList<Header>()
        val courses = mutableListOf<Course>()
        val pending = mutableListOf<PendingCourse>()
        val warnings = mutableListOf<String>()
        var title: String? = null
        var matched = false
        var undecodedCount = 0
        for ((page, raw) in spans.groupBy { it.page }.toSortedMap()) {
            val preliminary = visualLines(raw).flatMap { splitAtGaps(it) }
            val ownHeaders = preliminary.mapNotNull { s -> ScheduleParser.weekday(s.text)?.let { Header(it, s) } }
                .distinctBy { it.day }
            val headers = if (ownHeaders.size >= 2) ownHeaders.sortedBy { it.center } else previousHeaders
            if (headers.size < 2) {
                warnings += "第 $page 页缺少可定位的星期表头，未导入该页内容，请核对"
                continue
            }
            val headerRow = if (ownHeaders.size >= 2) ownHeaders.map { it.span.y }.average().toFloat() else Float.POSITIVE_INFINITY
            val gaps = headers.zipWithNext { a, b -> b.center - a.center }.sorted()
            val width = gaps[gaps.size / 2]
            if (width < 1 || ownHeaders.any { abs(it.span.y - headerRow) > 2 }) {
                warnings += "第 $page 页的星期列位置不明确，未导入该页内容，请核对"
                continue
            }
            if (ownHeaders.size >= 2) previousHeaders = headers
            title = title ?: preliminary.firstNotNullOfOrNull { s ->
                Regex("""(?:学年学期[^\d]*)?(\d{4})-(\d{4})-([12])""").find(s.text)?.let {
                    "${it.groupValues[1]}-${it.groupValues[2]}学年第${it.groupValues[3]}学期"
                }
            }
            val labelRight = headers.first().center - width / 2
            val rowHints = raw.filter { it.x < labelRight - 1 }.groupBy { it.clip }.mapNotNull { (clip, labels) ->
                if (clip == null) return@mapNotNull null
                val match = logicalLines(labels).firstNotNullOfOrNull { Regex("""^0?(\d{1,2})\D+0?(\d{1,2})小节$""").matchEntire(it) }
                    ?: return@mapNotNull null
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()
                if (start in 1..12 && end in start..12) RowHint(clip, start, end) else null
            }
            for ((index, header) in headers.withIndex()) {
                val left = if (index == 0) header.center - width / 2 else (headers[index - 1].center + header.center) / 2
                val right = if (index == headers.lastIndex) header.center + width / 2 else (headers[index + 1].center + header.center) / 2
                val column = raw.filter { it.y < headerRow - 1 && it.x >= left - 0.5f && it.x < right - 0.5f }
                for (cell in column.groupBy { it.clip }.values) {
                    val cellClip = cell.firstOrNull()?.clip
                    val rowHint = rowHints.singleOrNull { cellClip != null && abs(it.clip.bottom - cellClip.bottom) < 1.5f && abs(it.clip.top - cellClip.top) < 1.5f }
                    val logical = logicalLines(cell)
                    val candidates = logical.withIndex().filter { it.value.count { c -> c == ';' } >= 2 }
                    if (candidates.none { metadata.matches(it.value) }) {
                        if (logical.any { it.isNotBlank() }) warnings += "第 $page 页星期${dayName(header.day)}有未能识别的内容，请核对原文件"
                        continue
                    }
                    matched = true
                    if (ownHeaders.size < 2) warnings += "第 $page 页未重复星期表头，已沿用前页列位置，请核对星期归属"
                    var cursor = 0
                    for ((line, rawMetadata) in candidates) {
                        val match = metadata.matchEntire(rawMetadata)
                        val ctx = "第 $page 页星期${dayName(header.day)}"
                        if (match == null) {
                            warnings += "$ctx 有一条安排的周次或节次不完整，未导入：${rawMetadata.take(100)}"
                            cursor = line + 1 + if (logical.getOrNull(line + 1)?.let(::looksLikeRoom) == true) 1 else 0
                            continue
                        }
                        val prefix = logical.subList(cursor.coerceAtMost(line), line).filter { it.isNotBlank() }
                        if (prefix.isEmpty() || prefix.any { looksLikeRoom(it) || ';' in it }) {
                            warnings += "$ctx 有一条安排的课名分界不明确，已跳过"
                            cursor = line + 1 + if (logical.getOrNull(line + 1)?.let(::looksLikeRoom) == true) 1 else 0
                            continue
                        }
                        val name = prefix.joinToString(" ").trim()
                        val weeks = parseWeeks(match.groupValues[2])
                        val rawPeriod = match.groupValues[3].trim()
                        val pm = period.matchEntire(rawPeriod)
                        val explicitStart = pm?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val explicitEnd = pm?.groupValues?.get(2)?.toIntOrNull() ?: explicitStart
                        val recover = rowHint != null && (rawPeriod == "节" ||
                            rawPeriod == "${rowHint.start}${rowHint.end}节" && explicitStart > 12)
                        val start = if (recover) rowHint!!.start else explicitStart
                        val end = if (recover) rowHint!!.end else explicitEnd
                        if (weeks.isEmpty() || start !in 1..12 || end !in start..12) {
                            warnings += "$ctx「$name」周次或节次无效或不完整，已跳过"
                            cursor = line + 1 + if (logical.getOrNull(line + 1)?.let(::looksLikeRoom) == true) 1 else 0
                            continue
                        }
                        if (recover) warnings += "$ctx「$name」的节次文字不完整，已按同一表格行的 ${start}-${end} 小节补全，请核对"
                        if (!recover && pm != null && rowHint != null && (start != rowHint.start || end != rowHint.end)) {
                            warnings += "$ctx「$name」课程文字与左侧行标题节次不一致，暂按课程文字导入，请核对"
                        }
                        val next = logical.getOrNull(line + 1).orEmpty()
                        // 房间有明确建筑/场馆/实验室字样时才消费，避免把下一门课吞成教室。
                        val room = next.takeIf { looksLikeRoom(it) }.orEmpty()
                        cursor = line + 1 + if (room.isNotEmpty()) 1 else 0
                        val lastY = cell.minOfOrNull { it.y } ?: Float.POSITIVE_INFINITY
                        val clippedEnd = room.isNotEmpty() && line + 1 == logical.lastIndex && cellClip != null &&
                            lastY < cellClip.bottom + (cell.maxOfOrNull { it.size } ?: 0f) &&
                            cell.none { abs(it.y - lastY) < 0.5f && '\u0001' in it.text }
                        val incompleteRoom = room.isEmpty() || room.count { it == '(' } != room.count { it == ')' } || clippedEnd
                        if (incompleteRoom) warnings += "$ctx「$name」缺少完整教室信息，请核对 PDF 是否在页末被截断"
                        val teachers = match.groupValues[1].split(Regex("[,、]")).map { it.trim() }.filter { it.isNotEmpty() }
                        val suffix = pm?.groupValues?.get(3)?.trim().orEmpty()
                        courses += Course("multi-${courses.size}", name + suffix, day = header.day,
                            startPeriod = start, endPeriod = end, weeks = weeks, room = room, teachers = teachers)
                        if ('�' in name || '�' in room || teachers.any { '�' in it }) {
                            undecodedCount++
                        }
                    }
                    val tail = logical.drop(cursor).filter { it.isNotBlank() }
                    if (tail.isNotEmpty()) {
                        warnings += "第 $page 页星期${dayName(header.day)}末尾仍有不完整安排，未导入：${tail.joinToString(" ").take(100)}"
                        val name = tail.first()
                        if (!looksLikeRoom(name) && ';' !in name && name.any { it.isLetter() }) {
                            pending += PendingCourse("pending-${pending.size}", name, header.day,
                                rowHint?.start, rowHint?.end, page, tail.joinToString("\n"))
                        }
                    }
                }
            }
        }
        if (!matched || courses.isEmpty()) return null
        if (undecodedCount > 0) warnings.add(0, "$undecodedCount 条安排的名称、教师或教室含原 PDF 无法解码的字符，已用 � 标出，请对照原件修正")
        val unique = courses.distinctBy { listOf(it.name, it.day, it.startPeriod, it.endPeriod, it.weeks, it.room, it.teachers) }
            .mapIndexed { index, c -> c.copy(id = "import-$index") }
        return ParseResult(title, unique, emptyList(), unique.flatMap { it.weeks }.maxOrNull() ?: 1,
            (listOf("已识别同格多门课程；原 PDF 页末可能截断内容，请结合下方提示逐项核对") + warnings).distinct(), pending.distinctBy { listOf(it.name, it.day, it.startPeriod, it.endPeriod) })
    }

    private fun dayName(day: Int) = "一二三四五六日"[day - 1]
    private fun looksLikeRoom(text: String) = Regex("""教学楼|号楼|图书馆|实验室|体育场|体育馆|教室|^[A-Za-z]-?\d{3,}""").containsMatchIn(text)

    private fun parseWeeks(raw: String): List<Int> {
        val odd = raw.contains("单")
        val even = raw.contains("双")
        val spec = raw.substringBefore('周')
        val weeks = sortedSetOf<Int>()
        for (segment in spec.split(Regex("[,、]"))) {
            val parts = segment.trim().split('-').map { it.trim().toIntOrNull() }
            val start = parts.firstOrNull() ?: return emptyList()
            val end = parts.lastOrNull() ?: return emptyList()
            if (start !in 1..52 || end !in start..52) return emptyList()
            weeks += (start..end).filter { !odd || it % 2 == 1 }.filter { !even || it % 2 == 0 }
        }
        return weeks.toList()
    }

    /** 从同一列的物理行还原逻辑行；控制码 1 在部分浏览器 PDF 中是显式换行。 */
    private fun logicalLines(spans: List<Span>): List<String> {
        val text = StringBuilder()
        val hasExplicitBreaks = spans.any { '\u0001' in it.text || '\n' in it.text }
        for (row in visualLines(spans)) {
            val line = joinRow(row)
            if (!hasExplicitBreaks && text.isNotEmpty()) text.append('\n')
            if (text.isNotEmpty() && asciiLetter(text.last()) && line.firstOrNull()?.let(::asciiLetter) == true) text.append(' ')
            text.append(line.replace('\u0001', '\n').replace('\u0000', '�'))
        }
        return text.toString().split('\n').map { ScheduleParser.normalizeText(it).trim() }.filter { it.isNotEmpty() }
    }

    private fun visualLines(spans: List<Span>): List<List<Span>> {
        val rows = mutableListOf<MutableList<Span>>()
        for (s in spans.sortedByDescending { it.y }) {
            if (rows.isEmpty() || abs(rows.last().first().y - s.y) > 0.5f) rows += mutableListOf<Span>()
            rows.last() += s
        }
        return rows.map { it.sortedBy(Span::x) }
    }

    private fun splitAtGaps(row: List<Span>): List<Span> {
        val groups = mutableListOf<MutableList<Span>>()
        for (span in row) {
            val last = groups.lastOrNull()?.lastOrNull()
            if (last == null || span.x - (last.x + last.width) > maxOf(last.size, span.size) * 1.5f) groups += mutableListOf<Span>()
            groups.last() += span
        }
        return groups.map { group -> group.first().copy(text = ScheduleParser.normalizeText(joinRow(group)),
            width = group.last().x + group.last().width - group.first().x) }
    }

    private fun joinRow(row: List<Span>): String = buildString {
        var previous: Span? = null
        for (span in row) {
            val last = previous
            if (last != null && last.width > 0 && span.x - last.x - last.width > 1f &&
                last.text.lastOrNull()?.let(::asciiLetter) == true && span.text.firstOrNull()?.let(::asciiLetter) == true) append(' ')
            append(span.text)
            previous = span
        }
    }
    private fun asciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
}
