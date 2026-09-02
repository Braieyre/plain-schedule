package com.qingke.schedule.data

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qingke.schedule.WeekMath
import com.qingke.schedule.model.Course
import com.qingke.schedule.model.FlexCourse
import com.qingke.schedule.model.ParseResult
import com.qingke.schedule.model.ScheduleData
import com.qingke.schedule.model.Semester
import com.qingke.schedule.model.Settings
import java.time.LocalDate

/**
 * 全应用唯一的可观察状态。刻意不用 ViewModel/DI —— 数据量极小，
 * 一个由 Activity 持有的普通对象足够，还省掉了一整套框架的初始化开销。
 */
class AppState(private val store: Store) {

    var schedule by mutableStateOf(store.loadSchedule())
        private set

    var settings by mutableStateOf(store.loadSettings())
        private set

    var background by mutableStateOf<Bitmap?>(if (store.loadSettings().hasBackground) store.loadBackground() else null)
        private set

    val semester: Semester? get() = schedule?.semester
    val courses: List<Course> get() = schedule?.courses.orEmpty()
    val flexCourses: List<FlexCourse> get() = schedule?.flexCourses.orEmpty()
    val hasSchedule: Boolean get() = schedule != null

    /**
     * 全表去重排序后的课名单，供 `courseColors(..., names = )` 按序号取色用。
     * 排序保证同一份课表任何时候取到的颜色都一致；去重保证同一门课在不同格子同色。
     * 调用方**应在卡片循环外面取一次**存成局部 val，不要每张卡片各算一遍。
     */
    val courseNames: List<String> get() = courses.map { it.name }.distinct().sorted()

    val startMonday: LocalDate?
        get() = semester?.let { runCatching { LocalDate.parse(it.startMonday) }.getOrNull() }

    val totalWeeks: Int get() = (semester?.totalWeeks ?: 20).coerceAtLeast(1)

    /** 今天所处教学周，已钳制到 1..totalWeeks。无课表时返回 1。 */
    fun currentWeek(today: LocalDate = LocalDate.now()): Int {
        val start = startMonday ?: return 1
        return WeekMath.currentWeek(start, today).coerceIn(1, totalWeeks)
    }

    fun updateSettings(block: (Settings) -> Settings) {
        val next = block(settings)
        settings = next
        store.saveSettings(next)
    }

    fun replaceSchedule(data: ScheduleData?) {
        schedule = data
        store.saveSchedule(data)
    }

    /** 导入解析结果，覆盖现有课表。[startMonday] 由调用方保证已对齐周一。 */
    fun importResult(result: ParseResult, startMonday: LocalDate) {
        replaceSchedule(
            ScheduleData(
                semester = Semester(
                    title = result.semesterTitle ?: "我的课表",
                    startMonday = WeekMath.snapToMonday(startMonday).toString(),
                    totalWeeks = result.maxWeek.coerceAtLeast(1),
                ),
                courses = result.courses,
                flexCourses = result.flexCourses,
            )
        )
    }

    fun setStartMonday(date: LocalDate) {
        val s = semester ?: return
        replaceSchedule(schedule!!.copy(semester = s.copy(startMonday = WeekMath.snapToMonday(date).toString())))
    }

    /** 新增或按 id 覆盖一门课。无课表时先建一个空学期。 */
    fun upsertCourse(course: Course) {
        val cur = schedule ?: ScheduleData(
            semester = Semester("我的课表", WeekMath.snapToMonday(LocalDate.now()).toString(), 20)
        )
        val list = cur.courses.filterNot { it.id == course.id } + course
        val maxWeek = (list.flatMap { it.weeks } + cur.flexCourses.flatMap { it.weeks }).maxOrNull() ?: cur.semester.totalWeeks
        replaceSchedule(
            cur.copy(
                courses = list.sortedWith(compareBy({ it.day }, { it.startPeriod })),
                semester = cur.semester.copy(totalWeeks = maxOf(maxWeek, cur.semester.totalWeeks)),
            )
        )
    }

    fun deleteCourse(id: String) {
        val cur = schedule ?: return
        replaceSchedule(cur.copy(courses = cur.courses.filterNot { it.id == id }))
    }

    fun setBackground(uri: Uri, reqWidth: Int, reqHeight: Int): Boolean {
        if (!store.saveBackground(uri, reqWidth, reqHeight)) return false
        background = store.loadBackground()
        updateSettings { it.copy(hasBackground = background != null) }
        return background != null
    }

    fun clearBackground() {
        store.clearBackground()
        background = null
        updateSettings { it.copy(hasBackground = false) }
    }
}
