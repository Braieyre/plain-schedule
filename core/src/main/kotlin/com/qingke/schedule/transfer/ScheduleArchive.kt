package com.qingke.schedule.transfer

import com.qingke.schedule.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** 分享只携带教学数据和作息，不携带背景图片、主题或个人设置。 */
@Serializable
data class ScheduleArchive(
    val format: String = "plain-schedule",
    val version: Int = 1,
    val schedule: ScheduleData,
    val periodTimes: List<PeriodTime>? = null,
)

object ScheduleTransfer {
    const val MAX_FILE_BYTES = 20 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encode(schedule: ScheduleData, periodTimes: List<PeriodTime>): String {
        val archive = ScheduleArchive(schedule = schedule, periodTimes = periodTimes)
        validate(archive)
        return json.encodeToString(ScheduleArchive.serializer(), archive)
    }

    fun decode(text: String): ScheduleArchive {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "课表文件超过 20 MB" }
        val clean = text.removePrefix("\uFEFF").trim()
        val root = try { json.parseToJsonElement(clean).jsonObject }
        catch (e: IllegalArgumentException) { throw IllegalArgumentException("课表文件不是有效的 JSON，请重新导出后再试", e) }
        // 也兼容旧版应用私有目录里的 schedule.json。
        val archive = try { if ("semester" in root && "schedule" !in root) {
            ScheduleArchive(schedule = json.decodeFromString<ScheduleData>(clean))
        } else {
            require("format" in root && "version" in root) { "不是普通课程表导出的课表文件" }
            json.decodeFromString<ScheduleArchive>(clean)
        }
        } catch (e: SerializationException) {
            throw IllegalArgumentException("JSON 课表文件缺少必要信息或字段格式不正确，请重新导出后再试", e)
        }
        validate(archive)
        return archive
    }

    fun validate(archive: ScheduleArchive) {
        require(archive.format == "plain-schedule") { "不是普通课程表导出的课表文件" }
        require(archive.version == 1) { "课表文件版本过新，请更新应用后再导入" }
        val data = archive.schedule
        val semester = data.semester
        require(semester.totalWeeks in 1..52) { "学期周数需在 1～52 周之间" }
        require(semester.title.isNotBlank() && semester.title.length <= 200) { "学期名称无效" }
        val date = runCatching { LocalDate.parse(semester.startMonday) }.getOrNull()
        require(date != null && date.dayOfWeek == DayOfWeek.MONDAY) { "开学日期必须是有效的周一" }
        require(data.courses.size + data.flexCourses.size <= 2000) { "课程数量过多" }
        val ids = data.courses.map { it.id } + data.flexCourses.map { it.id } + data.pendingCourses.map { it.id }
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "课程编号为空或重复" }
        fun checkCourse(name: String, weeks: List<Int>) {
            require(name.isNotBlank() && name.length <= 500) { "课程名称无效" }
            require(weeks.isNotEmpty() && weeks.all { it in 1..semester.totalWeeks }) { "课程周次超出学期范围或为空" }
            require(weeks.distinct().size == weeks.size) { "课程周次重复" }
        }
        data.courses.forEach {
            checkCourse(it.name, it.weeks)
            require(it.day in 1..7 && it.startPeriod in 1..12 && it.endPeriod in it.startPeriod..12) {
                "「${it.name}」的星期或节次无效（支持第 1～12 节）"
            }
        }
        data.flexCourses.forEach { checkCourse(it.name, it.weeks) }
        require(data.pendingCourses.size <= 2000) { "待补充安排数量过多" }
        data.pendingCourses.forEach {
            require(it.name.isNotBlank() && it.name.length <= 500 && it.day in 1..7) { "待补充安排的名称或星期无效" }
            require((it.startPeriod == null && it.endPeriod == null) ||
                (it.startPeriod != null && it.endPeriod != null && it.startPeriod in 1..12 && it.endPeriod in it.startPeriod..12)) { "待补充安排的节次无效" }
        }
        archive.periodTimes?.let { times ->
            require(times.map { it.index }.sorted() == (1..12).toList()) { "上课时间必须包含第 1～12 节且不能重复" }
            times.forEach {
                val start = runCatching { LocalTime.parse(it.start) }.getOrNull()
                val end = runCatching { LocalTime.parse(it.end) }.getOrNull()
                require(start != null && end != null && end > start) { "第 ${it.index} 节上课时间无效" }
            }
        }
    }
}
