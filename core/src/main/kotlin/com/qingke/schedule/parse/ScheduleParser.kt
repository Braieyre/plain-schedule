package com.qingke.schedule.parse

import com.qingke.schedule.model.Course
import com.qingke.schedule.model.FlexCourse
import com.qingke.schedule.model.ParseResult
import com.qingke.schedule.model.PdfUnsupportedException
import com.qingke.schedule.model.Span
import kotlin.math.abs

/**
 * 把 [Span] 列表(PDF 内容流抽出的文字碎片,坐标已归一)解析成结构化课表。
 *
 * 不依赖任何"几何切格子"的判断,只靠"锚点定位 + 拼接 + 正则切分":
 * 1. 用七个"星期X"表头 span 定位七列锚点;轴向自适应——比较七锚点在 x/y 上的方差,
 *    方差大的那个是"日期轴",另一个是"行轴"。这样无论内容流坐标是否被 /Rotate 影响都成立。
 * 2. 列带宽取相邻锚点间距中位数,其余 span 按日期轴坐标落入对应列带;
 *    行轴坐标不早于表头那一行的(即页眉/页脚/大标题)一律不参与分列,
 *    这一步顺带把表头"星期X"自身、页面大标题(可能水平方向恰好落在某个列带内)都排除掉了。
 * 3. 列内 span 按行轴坐标降序排序后**首尾直接拼接**成一个大字符串,不插入任何分隔符——
 *    课名/详情跨行折断由此天然被"拼接"消解,不需要单独处理换行。
 * 4. 用 "(N-M节)" 正则在拼接串里找课程锚点做正向单遍扫描:每个锚点之后依次按
 *    "/场地:" "/教师:" "/考核方式:" 三个标签切出周次串/教室/教师;"考核方式"取值固定
 *    两个汉字(考试/考查等)而不是"取到下一个锚点前的全部文本"——因为它后面没有分隔符,
 *    不这样精确定界就会把下一门课的课名吞进当前课程的考核方式里。
 */
object ScheduleParser {

    private val WEEKDAYS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    private val SEMESTER_REGEX = Regex("""\d{4}-\d{4}学年第?\d学期""")
    private val PERIOD_ANCHOR_REGEX = Regex("""\((\d+)(?:-(\d+))?节\)""")
    private val MARKERS = charArrayOf('★', '●', '▲', '■')
    private val FLEX_START_REGEX = Regex("^(实践课程|其他课程)[:：]")
    private val FLEX_ENTRY_REGEX =
        Regex("""^(.+?)([★●▲■])([^(（]*)[(（]共\d+周[)）]/([\d\-,，]+周)(?:/(.*))?$""")
    private val WEEK_SEGMENT_REGEX = Regex("""(\d+)(?:-(\d+))?周""")
    private val ENTRY_SPLIT_REGEX = Regex("[;；]")
    private val COMMA_SPLIT_REGEX = Regex("[,，]")

    fun parse(spans: List<Span>): ParseResult {
        if (spans.isEmpty()) {
            throw PdfUnsupportedException("无法识别这份课表 PDF", rawText = "")
        }

        val warnings = mutableListOf<String>()

        // ---- 学期标题:全量 span 里找,不受列带/行轴限制 ----
        val semesterTitle = spans.asSequence()
            .mapNotNull { SEMESTER_REGEX.find(it.text)?.value }
            .firstOrNull()

        // ---- 定位七个"星期X"锚点 ----
        val anchorIndex = HashMap<Int, Int>() // day(1..7) -> spans 下标,重复出现取第一个
        for (i in spans.indices) {
            val day = WEEKDAYS.indexOf(spans[i].text) + 1
            if (day in 1..7 && day !in anchorIndex) anchorIndex[day] = i
        }

        val assigned = BooleanArray(spans.size)
        val courses = mutableListOf<Course>()

        // 轴向默认取原始 (x,y);七个锚点齐全时按方差重新判定,并用于列带切分。
        // 锚点不全(七个"星期X"没找齐)就跳过固定节次课程解析,但仍尝试页脚 flex 课程——
        // 尽量部分可用而不是直接整体报错。
        var dateAxis: (Span) -> Float = Span::x
        var rowAxis: (Span) -> Float = Span::y

        if (anchorIndex.size == 7) {
            val anchors = (1..7).map { spans[anchorIndex.getValue(it)] }
            val dateIsX = variance(anchors.map { it.x }) >= variance(anchors.map { it.y })
            dateAxis = if (dateIsX) Span::x else Span::y
            rowAxis = if (dateIsX) Span::y else Span::x

            for (idx in anchorIndex.values) assigned[idx] = true

            val headerRowAxis = anchors.map { rowAxis(it) }.average().toFloat()
            val anchorDate = (1..7).associateWith { day -> dateAxis(spans[anchorIndex.getValue(day)]) }
            val sortedVals = anchorDate.values.sorted()
            val bandWidth = median(sortedVals.zipWithNext { a, b -> b - a })
            val half = bandWidth / 2f

            val buckets = (1..7).associateWith { mutableListOf<Int>() }
            for (i in spans.indices) {
                if (assigned[i]) continue
                val s = spans[i]
                // 行轴不早于表头那一行的,是页面大标题/页眉/页脚一类的内容,不进入任何列带。
                if (rowAxis(s) >= headerRowAxis) continue
                val da = dateAxis(s)
                for (day in 1..7) {
                    val center = anchorDate.getValue(day)
                    if (da >= center - half && da < center + half) {
                        buckets.getValue(day).add(i)
                        assigned[i] = true
                        break
                    }
                }
            }

            for (day in 1..7) {
                val ordered = buckets.getValue(day).sortedByDescending { rowAxis(spans[it]) }
                val text = ordered.joinToString("") { spans[it].text }
                courses += parseColumn(day, text, warnings)
            }
        } else {
            warnings += "未能定位到完整的七个星期表头(星期一~星期日),固定节次课程解析已跳过"
        }

        // ---- 页脚 flex 课程:实践课程 / 其他课程,从"所有列带之外"的 span 里找 ----
        val leftover = spans.indices.filterNot { assigned[it] }
        val flexCourses = parseFlexCourses(spans, leftover, dateAxis, rowAxis, warnings)

        val maxWeek = (courses.flatMap { it.weeks } + flexCourses.flatMap { it.weeks }).maxOrNull() ?: 0

        if (courses.isEmpty()) {
            throw PdfUnsupportedException(
                "无法识别这份课表 PDF",
                rawText = spans.joinToString("") { it.text },
            )
        }

        return ParseResult(
            semesterTitle = semesterTitle,
            courses = courses,
            flexCourses = flexCourses,
            maxWeek = maxWeek,
            warnings = warnings,
        )
    }

    // -----------------------------------------------------------------
    // 固定节次课程:单列拼接串 -> 课程列表(正向单遍扫描)
    // -----------------------------------------------------------------

    private fun parseColumn(day: Int, text: String, warnings: MutableList<String>): List<Course> {
        val matches = PERIOD_ANCHOR_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()

        val result = mutableListOf<Course>()
        // cursor:上一门课程详情实际解析完毕的位置,同时也是"本门课程名"的起始位置。
        // 第一门课程用字符串开头兜底,这正是折行课名(如"习近平新时代中国特色"+
        // "社会主义思想概论★")天然被正确还原的原因——它前面没有别的课程详情可拼接进来。
        var cursor = 0

        for ((mi, m) in matches.withIndex()) {
            val rawName = text.substring(cursor, m.range.first).trim()
            val (name, marker) = stripMarker(rawName)
            val startPeriod = m.groupValues[1].toInt()
            val endPeriod = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
            val ctx = "周${day}第${startPeriod}-${endPeriod}节「$name」"
            // 下一门课的锚点起始位置,作为本门课详情搜索的硬上限——
            // 即便某个标签缺失导致向后找,也不会越界吞到下一门课头上。
            val limit = if (mi + 1 < matches.size) matches[mi + 1].range.first else text.length

            var pos = m.range.last + 1 // 紧跟在 ")" 之后

            val roomLabel = findLabel(text, "场地", pos, limit)
            val weeksRaw: String
            if (roomLabel != null) {
                weeksRaw = text.substring(pos, roomLabel.start)
                pos = roomLabel.end
            } else {
                weeksRaw = text.substring(pos, limit)
                pos = limit
                warnings += "$ctx 缺少「/场地:」标签"
            }

            val teacherLabel = findLabel(text, "教师", pos, limit)
            val room: String
            if (teacherLabel != null) {
                room = text.substring(pos, teacherLabel.start).trim()
                pos = teacherLabel.end
            } else {
                room = text.substring(pos, limit).trim()
                pos = limit
                if (roomLabel != null) warnings += "$ctx 缺少「/教师:」标签"
            }

            val examLabel = findLabel(text, "考核方式", pos, limit)
            val teachersRaw: String
            val exam: String?
            if (examLabel != null) {
                teachersRaw = text.substring(pos, examLabel.start).trim()
                // 考核方式取值固定两个汉字,不靠"到下一个标签/结尾"来定界。
                val examStart = examLabel.end.coerceAtMost(limit)
                val examEnd = (examStart + 2).coerceIn(examStart, limit)
                exam = text.substring(examStart, examEnd).ifBlank { null }
                cursor = examEnd
                if (exam == null) warnings += "$ctx 考核方式取值为空"
            } else {
                teachersRaw = text.substring(pos, limit).trim()
                exam = null
                cursor = limit
                if (teacherLabel != null) warnings += "$ctx 缺少「/考核方式:」标签"
            }

            if (name.isEmpty()) {
                warnings += "$ctx 课程名称为空,已跳过该条目"
                continue
            }

            val weeks = parseWeeks(weeksRaw, ctx, warnings)
            val teachers = splitTeachers(teachersRaw)

            result += Course(
                id = "${day}-${startPeriod}-${name}",
                name = name,
                marker = marker,
                day = day,
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                weeks = weeks,
                room = room,
                teachers = teachers,
                exam = exam,
            )
        }
        return result
    }

    /** "/场地:"、"/教师:"、"/考核方式:" 这类标签定位,冒号兼容半角/全角,越过 [limit] 视为未找到。 */
    private data class LabelSpan(val start: Int, val end: Int)

    private fun findLabel(text: String, label: String, from: Int, limit: Int): LabelSpan? {
        if (from >= limit) return null
        val plain = "/$label:"
        val full = "/$label："
        val iPlain = text.indexOf(plain, from)
        val iFull = text.indexOf(full, from)
        val candidates = buildList {
            if (iPlain in from until limit) add(iPlain to plain.length)
            if (iFull in from until limit) add(iFull to full.length)
        }
        val best = candidates.minByOrNull { it.first } ?: return null
        return LabelSpan(best.first, best.first + best.second)
    }

    // -----------------------------------------------------------------
    // 页脚 flex 课程:实践课程 / 其他课程
    // -----------------------------------------------------------------

    private fun parseFlexCourses(
        spans: List<Span>,
        leftover: List<Int>,
        dateAxis: (Span) -> Float,
        rowAxis: (Span) -> Float,
        warnings: MutableList<String>,
    ): List<FlexCourse> {
        val consumed = HashSet<Int>()
        val result = mutableListOf<FlexCourse>()

        for (i in leftover) {
            if (i in consumed) continue
            val startSpan = spans[i]
            val startMatch = FLEX_START_REGEX.find(startSpan.text) ?: continue
            val kind = if (startMatch.groupValues[1] == "实践课程") "实践" else "其他"

            // 同 x 起点(日期轴坐标相近)、按行轴降序排列的相邻 span 视为该段的续行。
            val originDa = dateAxis(startSpan)
            val group = leftover
                .filter { abs(dateAxis(spans[it]) - originDa) < 1f }
                .sortedByDescending { rowAxis(spans[it]) }
            val startPos = group.indexOf(i)

            val sb = StringBuilder()
            for (p in startPos until group.size) {
                val idx = group[p]
                sb.append(spans[idx].text)
                consumed += idx
                val trimmed = spans[idx].text.trimEnd()
                val paragraphClosed = trimmed.endsWith(";") || trimmed.endsWith("；")
                if (paragraphClosed) break
                val nextIsNewStart = p + 1 < group.size &&
                    FLEX_START_REGEX.containsMatchIn(spans[group[p + 1]].text)
                if (nextIsNewStart) break
            }

            val body = sb.toString().removePrefix(startMatch.value)
            val entries = body.split(ENTRY_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
            for (entry in entries) {
                val em = FLEX_ENTRY_REGEX.find(entry)
                if (em == null) {
                    warnings += "无法解析${kind}课程条目: $entry"
                    continue
                }
                val name = em.groupValues[1].trim()
                val marker = em.groupValues[2]
                val teachersRaw = em.groupValues[3]
                val weeksRaw = em.groupValues[4]
                val roomRaw = em.groupValues[5].trim()
                val ctx = "${kind}课程「$name」"

                result += FlexCourse(
                    id = "flex-${kind}-${name}",
                    name = name,
                    marker = marker,
                    teachers = splitTeachers(teachersRaw),
                    weeks = parseWeeks(weeksRaw, ctx, warnings),
                    room = roomRaw.takeIf { it.isNotEmpty() && it != "无" },
                    kind = kind,
                )
            }
        }
        return result
    }

    // -----------------------------------------------------------------
    // 小工具
    // -----------------------------------------------------------------

    private fun variance(values: List<Float>): Double {
        val mean = values.map { it.toDouble() }.average()
        return values.sumOf { v -> val d = v - mean; d * d }
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    /** 课名尾部的学时标记(★●▲■)剥离出来,name 不再含标记。 */
    private fun stripMarker(raw: String): Pair<String, String> {
        if (raw.isEmpty()) return raw to ""
        val last = raw.last()
        return if (last in MARKERS) raw.dropLast(1).trim() to last.toString() else raw to ""
    }

    private fun splitTeachers(raw: String): List<String> =
        raw.split(COMMA_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 周次展开:"1-6周,8-13周" -> [1..6,8..13]。
     * 防御性支持 "(单)"/"(双)"/"单周"/"双周" 修饰——出现在某一段里就把该段展开结果
     * 过滤成只留奇数/偶数周。
     */
    private fun parseWeeks(raw: String, ctx: String, warnings: MutableList<String>): List<Int> {
        if (raw.isBlank()) {
            warnings += "$ctx 周次为空"
            return emptyList()
        }
        val result = sortedSetOf<Int>()
        val segments = raw.split(COMMA_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            warnings += "$ctx 周次为空"
            return emptyList()
        }
        for (seg in segments) {
            val oddOnly = seg.contains("单")
            val evenOnly = seg.contains("双")
            val m = WEEK_SEGMENT_REGEX.find(seg)
            if (m == null) {
                warnings += "$ctx 无法解析周次片段「$seg」"
                continue
            }
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
            if (start > end) {
                warnings += "$ctx 周次范围颠倒「$seg」"
                continue
            }
            for (w in start..end) {
                if (oddOnly && w % 2 == 0) continue
                if (evenOnly && w % 2 != 0) continue
                result += w
            }
        }
        if (result.isEmpty()) {
            warnings += "$ctx 周次解析结果为空: $raw"
        }
        return result.toList()
    }
}
