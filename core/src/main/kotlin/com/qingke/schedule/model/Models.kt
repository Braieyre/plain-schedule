package com.qingke.schedule.model

import kotlinx.serialization.Serializable

/**
 * 从 PDF 内容流里抽出的一段文字及其视图坐标。
 * 坐标已经过 CTM 与页面 /Rotate 归一，原点左下、y 向上。
 *
 * [page] **从 1 起计**（第一页 = 1）。
 */
data class Span(
    val page: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val text: String,
)

/** 有固定周几 + 节次的课。 */
@Serializable
data class Course(
    val id: String,
    /** 不含尾部学时标记，如 "计算机组成原理"。 */
    val name: String,
    /** 学时标记：★讲课 ●实验 ▲上机 ■实践，无则空串。 */
    val marker: String = "",
    /** 1=周一 … 7=周日 */
    val day: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    /** 展开后的周次列表，如 [1,2,3,4,5,6,8,9]。 */
    val weeks: List<Int>,
    val room: String = "",
    val teachers: List<String> = emptyList(),
    /** 考试 / 考查，未知为 null。 */
    val exam: String? = null,
) {
    val displayName: String get() = name + marker
    fun activeIn(week: Int): Boolean = week in weeks
}

/** 无固定节次的课：实践课程 / 其他课程。展示为日期行下方的横条。 */
@Serializable
data class FlexCourse(
    val id: String,
    val name: String,
    val marker: String = "",
    val teachers: List<String> = emptyList(),
    val weeks: List<Int>,
    val room: String? = null,
    /** "实践" 或 "其他" */
    val kind: String,
) {
    fun activeIn(week: Int): Boolean = week in weeks
}

@Serializable
data class Semester(
    /** 如 "2026-2027学年第1学期" */
    val title: String,
    /** ISO-8601 日期，存储前必须已对齐到周一。 */
    val startMonday: String,
    val totalWeeks: Int,
)

/** 落盘到 filesDir/schedule.json 的完整课表。 */
@Serializable
data class ScheduleData(
    val semester: Semester,
    val courses: List<Course> = emptyList(),
    val flexCourses: List<FlexCourse> = emptyList(),
)

/** ScheduleParser 的输出。开学日期不在 PDF 里，由用户在导入时补。 */
data class ParseResult(
    val semesterTitle: String?,
    val courses: List<Course>,
    val flexCourses: List<FlexCourse>,
    val maxWeek: Int,
    val warnings: List<String> = emptyList(),
)

/**
 * PDF 无法解析。[rawText] 携带已提取到的原始文字，UI 展示出来供用户反馈，
 * 这样用别的教务系统的同学能提供可诊断的信息。
 */
class PdfUnsupportedException(
    message: String,
    val rawText: String = "",
) : Exception(message)

@Serializable
data class PeriodTime(val index: Int, val start: String, val end: String)

val DEFAULT_PERIOD_TIMES: List<PeriodTime> = listOf(
    PeriodTime(1, "08:00", "08:45"),
    PeriodTime(2, "08:55", "09:40"),
    PeriodTime(3, "10:00", "10:45"),
    PeriodTime(4, "10:55", "11:40"),
    PeriodTime(5, "13:00", "13:45"),
    PeriodTime(6, "13:55", "14:40"),
    PeriodTime(7, "14:50", "15:35"),
    PeriodTime(8, "15:45", "16:30"),
    PeriodTime(9, "16:40", "17:25"),
    PeriodTime(10, "17:35", "18:20"),
    PeriodTime(11, "18:30", "19:15"),
    PeriodTime(12, "19:25", "20:10"),
)

@Serializable
data class Settings(
    /** 每周起始日：false=周一，true=周日。仅影响表头列序，教务周次恒以周一计。 */
    val weekStartsSunday: Boolean = false,
    val showOtherWeeks: Boolean = false,
    val themeId: String = "pastel",
    /** system / light / dark */
    val darkMode: String = "system",
    val hasBackground: Boolean = false,
    /** 课程卡不透明度 0.3..1.0 */
    val cardAlpha: Float = 0.92f,
    /** 背景蒙层强度 0.0..0.8 */
    val bgDim: Float = 0.25f,
    val periodTimes: List<PeriodTime> = DEFAULT_PERIOD_TIMES,
)
