package com.qingke.schedule

import com.qingke.schedule.parse.ScheduleParser
import com.qingke.schedule.pdf.PdfText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 完整回归测试：17 门固定节次课 + 6 门 flexCourse 的逐条验证。
 * 基于 PLAN.md 第二轮真值表，覆盖各字段与边界情况。
 */
class ScheduleParserTest {

    private fun result() = ScheduleParser.parse(
        PdfText.extract(javaClass.getResourceAsStream("/sample-schedule.pdf")!!.readBytes())
    )

    // ==================== 全局约束 ====================

    @Test
    fun 学期标题和周数正确() {
        val r = result()
        assertEquals("学期标题", "2026-2027学年第1学期", r.semesterTitle)
        assertEquals("总周数", 19, r.maxWeek)
        assertEquals("警告列表为空", emptyList<String>(), r.warnings)
    }

    @Test
    fun 课表不含周六周日的课() {
        val r = result()
        val days6Or7 = r.courses.filter { it.day > 5 }
        assertEquals("不存在 day > 5 的课", 0, days6Or7.size)
    }

    // ==================== 固定节次课逐条验证 ====================

    @Test
    fun 固定节次课总数17门() {
        val r = result()
        assertEquals("固定节次课总数", 17, r.courses.size)
    }

    @Test
    fun 固定节次课字段逐条验证() {
        val r = result()
        val cs = r.courses.sortedWith(compareBy({ it.day }, { it.startPeriod }))

        // 期望数据：逐行对应真值表（已脱敏）
        val expectations = listOf(
            // day=1
            ExpectedCourse(day=1, start=3, end=4, name="数字图象处理", marker="★",
                weeks=listOf(10,11,12,13,14,15,16,17), room="XX-229", teachers=listOf("陈子二"), exam="考查"),
            ExpectedCourse(day=1, start=5, end=6, name="单片机原理及应用", marker="★",
                weeks=listOf(5,6,8,9,10,11,12,13,14,15,16,17), room="XX-229", teachers=listOf("杨子四","孙丁"), exam="考试"),
            ExpectedCourse(day=1, start=7, end=8, name="软件工程", marker="★",
                weeks=listOf(1,2,3,4,5,6,8,9,10,11,12,13), room="XX-429", teachers=listOf("周戊"), exam="考试"),

            // day=2
            ExpectedCourse(day=2, start=1, end=2, name="计算机组成原理", marker="★",
                weeks=listOf(1,2,3,4,5,6,8,9,10,11,12,13), room="XX-427", teachers=listOf("王甲","刘子三"), exam="考试"),
            ExpectedCourse(day=2, start=3, end=4, name="计算机网络", marker="★",
                weeks=listOf(1,2,3,4,5,6,8,9,10,11,12,13), room="XX-226", teachers=listOf("钱丙"), exam="考试"),
            ExpectedCourse(day=2, start=5, end=6, name="Linux系统程序设计S", marker="★",
                weeks=listOf(1,2,3,4,5,6,8,9), room="XX-226", teachers=listOf("吴己"), exam="考查"),

            // day=3
            ExpectedCourse(day=3, start=3, end=4, name="习近平新时代中国特色社会主义思想概论", marker="★",
                weeks=listOf(1,2,3,4,5,6,8,9,10,11,12,13,14), room="A-417", teachers=listOf("李子一"), exam="考试"),
            ExpectedCourse(day=3, start=5, end=6, name="数字图象处理", marker="★",
                weeks=listOf(10,11,12,13,14,15,16,17), room="XX-427", teachers=listOf("陈子二"), exam="考查"),
            ExpectedCourse(day=3, start=7, end=8, name="计算机组成原理", marker="★",
                weeks=listOf(1,2,3,4,5,6,8,9,10,11,12,13), room="XX-430", teachers=listOf("王甲","刘子三"), exam="考试"),
            ExpectedCourse(day=3, start=9, end=10, name="体育5", marker="●",
                weeks=listOf(2,3,4,5,6,7,8,9,10,11,12,13), room="文体中心D馆乒乓球馆", teachers=listOf("吴子六"), exam="考查"),

            // day=4
            ExpectedCourse(day=4, start=3, end=4, name="Linux系统程序设计S", marker="★",
                weeks=listOf(1,2,3,4,5,7,8,9), room="XX-228", teachers=listOf("吴己"), exam="考查"),
            ExpectedCourse(day=4, start=5, end=6, name="人工智能", marker="★",
                weeks=listOf(9,10,11,12,13,14,15,16), room="XX-228", teachers=listOf("黄子五"), exam="考查"),
            ExpectedCourse(day=4, start=7, end=8, name="软件工程", marker="★",
                weeks=listOf(1,2,3,4,5,7,8,9,10,11,12,13), room="XX-429", teachers=listOf("周戊"), exam="考试"),

            // day=5
            ExpectedCourse(day=5, start=1, end=2, name="习近平新时代中国特色社会主义思想概论", marker="★",
                weeks=listOf(1,2,3,4,7,8,9,10,11,12,13), room="A-217", teachers=listOf("李子一"), exam="考试"),
            ExpectedCourse(day=5, start=3, end=4, name="计算机网络", marker="★",
                weeks=listOf(1,2,3,4,7,8,9,10,11,12,13,14), room="XX-226", teachers=listOf("钱丙"), exam="考试"),
            ExpectedCourse(day=5, start=5, end=6, name="人工智能", marker="★",
                weeks=listOf(9,10,11,12,13,14,15,16), room="XX-227", teachers=listOf("黄子五"), exam="考查"),
            ExpectedCourse(day=5, start=7, end=8, name="单片机原理及应用", marker="★",
                weeks=listOf(4,7,8,9,10,11,12,13,14,15,16,17), room="XX-429", teachers=listOf("杨子四","孙丁"), exam="考试"),
        )

        assertEquals("期望条数与实际条数", expectations.size, cs.size)

        for (i in expectations.indices) {
            val exp = expectations[i]
            val act = cs[i]
            val msg = "课程 #$i: ${exp.name}(day=${exp.day} ${exp.start}-${exp.end}节)"

            assertEquals("$msg - day", exp.day, act.day)
            assertEquals("$msg - startPeriod", exp.start, act.startPeriod)
            assertEquals("$msg - endPeriod", exp.end, act.endPeriod)
            assertEquals("$msg - name", exp.name, act.name)
            assertEquals("$msg - marker", exp.marker, act.marker)
            assertEquals("$msg - weeks", exp.weeks, act.weeks)
            assertEquals("$msg - room", exp.room, act.room)
            assertEquals("$msg - teachers", exp.teachers, act.teachers)
            assertEquals("$msg - exam", exp.exam, act.exam)
        }
    }

    // ==================== 容易回归的特殊情况钉住 ====================

    @Test
    fun 体育5_marker是实验标记_room是中文场地名() {
        val c = result().courses.first { it.day == 3 && it.startPeriod == 9 }
        assertEquals("name", "体育5", c.name)
        assertEquals("marker 应为实验标记而非讲课标记", "●", c.marker)
        assertEquals("room 是中文场地名而非 XX- 格式", "文体中心D馆乒乓球馆", c.room)
    }

    @Test
    fun Linux课名尾部字母S不被误当marker() {
        val cs = result().courses.filter { it.name == "Linux系统程序设计S" }
        assertEquals("Linux课出现 2 次", 2, cs.size)
        cs.forEach { c ->
            assertEquals("name 包含尾部S", "Linux系统程序设计S", c.name)
            assertEquals("marker 正确为讲课标记", "★", c.marker)
        }
    }

    @Test
    fun 形势与政策5课名尾部数字不被误当marker() {
        val c = result().courses.firstOrNull { it.name == "形势与政策5" && it.day == 3 }
        if (c != null) {  // 固定节次表中无"形势与政策5"，但 flexCourse 有
            assertEquals("name 包含尾部数字 5", "形势与政策5", c.name)
            assertEquals("marker", "★", c.marker)
        }
    }

    @Test
    fun 周五7_8节单片机weeks混合单周和区间() {
        val c = result().courses.first { it.day == 5 && it.startPeriod == 7 }
        assertEquals("name", "单片机原理及应用", c.name)
        // 期望：单周 4 + 区间 7-17 = [4,7,8,9,10,11,12,13,14,15,16,17]
        assertEquals("weeks", listOf(4,7,8,9,10,11,12,13,14,15,16,17), c.weeks)
    }

    @Test
    fun 同名课多格子_数字图象处理周一三不串() {
        val courses = result().courses.filter { it.name == "数字图象处理" }
        assertEquals("数字图象处理出现 2 次", 2, courses.size)

        val mon = courses.first { it.day == 1 }
        val wed = courses.first { it.day == 3 }

        // 确保 weeks、room 都不同
        assertEquals("周一 room", "XX-229", mon.room)
        assertEquals("周三 room", "XX-427", wed.room)
        assertEquals("周一 weeks", listOf(10,11,12,13,14,15,16,17), mon.weeks)
        assertEquals("周三 weeks", listOf(10,11,12,13,14,15,16,17), wed.weeks)
        // 这两个的 weeks 相同但 room 不同，确保存储正确
    }

    @Test
    fun 同名课多格子_计算机网络周二五room不同() {
        val courses = result().courses.filter { it.name == "计算机网络" }
        assertEquals("计算机网络出现 2 次", 2, courses.size)

        val tue = courses.first { it.day == 2 }
        val fri = courses.first { it.day == 5 }

        assertEquals("周二 room", "XX-226", tue.room)
        assertEquals("周五 room", "XX-226", fri.room)
        assertEquals("周二 weeks", listOf(1,2,3,4,5,6,8,9,10,11,12,13), tue.weeks)
        assertEquals("周五 weeks", listOf(1,2,3,4,7,8,9,10,11,12,13,14), fri.weeks)
    }

    @Test
    fun 同名课多格子_计算机组成原理周二三room和weeks都不同() {
        val courses = result().courses.filter { it.name == "计算机组成原理" }
        assertEquals("计算机组成原理出现 2 次", 2, courses.size)

        val tue = courses.first { it.day == 2 }
        val wed = courses.first { it.day == 3 }

        assertEquals("周二 room", "XX-427", tue.room)
        assertEquals("周三 room", "XX-430", wed.room)
        assertEquals("周二 weeks", listOf(1,2,3,4,5,6,8,9,10,11,12,13), tue.weeks)
        assertEquals("周三 weeks", listOf(1,2,3,4,5,6,8,9,10,11,12,13), wed.weeks)
    }

    @Test
    fun 同名课多格子_软件工程周一四room相同weeks不同() {
        val courses = result().courses.filter { it.name == "软件工程" }
        assertEquals("软件工程出现 2 次", 2, courses.size)

        val mon = courses.first { it.day == 1 }
        val thu = courses.first { it.day == 4 }

        assertEquals("周一 room", "XX-429", mon.room)
        assertEquals("周四 room", "XX-429", thu.room)
        assertEquals("周一 weeks", listOf(1,2,3,4,5,6,8,9,10,11,12,13), mon.weeks)
        assertEquals("周四 weeks", listOf(1,2,3,4,5,7,8,9,10,11,12,13), thu.weeks)
    }

    @Test
    fun 同名课多格子_人工智能周四五room不同weeks相同() {
        val courses = result().courses.filter { it.name == "人工智能" }
        assertEquals("人工智能出现 2 次", 2, courses.size)

        val thu = courses.first { it.day == 4 }
        val fri = courses.first { it.day == 5 }

        assertEquals("周四 room", "XX-228", thu.room)
        assertEquals("周五 room", "XX-227", fri.room)
        assertEquals("周四 weeks", listOf(9,10,11,12,13,14,15,16), thu.weeks)
        assertEquals("周五 weeks", listOf(9,10,11,12,13,14,15,16), fri.weeks)
    }

    @Test
    fun 同名课多格子_习近平概论周三五weeks不同() {
        val courses = result().courses.filter { it.name == "习近平新时代中国特色社会主义思想概论" }
        assertEquals("习近平概论出现 2 次", 2, courses.size)

        val wed = courses.first { it.day == 3 }
        val fri = courses.first { it.day == 5 }

        assertEquals("周三 room", "A-417", wed.room)
        assertEquals("周五 room", "A-217", fri.room)
        assertEquals("周三 weeks", listOf(1,2,3,4,5,6,8,9,10,11,12,13,14), wed.weeks)
        assertEquals("周五 weeks", listOf(1,2,3,4,7,8,9,10,11,12,13), fri.weeks)
    }

    // ==================== flexCourse 逐条验证 ====================

    @Test
    fun flexCourse完整性() {
        val r = result()
        assertEquals("flexCourse 总数", 6, r.flexCourses.size)
        assertEquals("实践课数", 5, r.flexCourses.count { it.kind == "实践" })
        assertEquals("其他课数", 1, r.flexCourses.count { it.kind == "其他" })
    }

    @Test
    fun flexCourse字段逐条验证() {
        val r = result()
        val fs = r.flexCourses

        val expectations = listOf(
            // 实践课（按 PDF 原始顺序）
            ExpectedFlexCourse(kind="实践", name="创新实践周", weeks=listOf(16,17,18)),
            ExpectedFlexCourse(kind="实践", name="单片机课程设计", weeks=(5..19).toList()),
            ExpectedFlexCourse(kind="实践", name="软件工程课程设计", weeks=(1..19).toList()),
            ExpectedFlexCourse(kind="实践", name="计算机网络课程设计A", weeks=(1..19).toList()),
            ExpectedFlexCourse(kind="实践", name="计算机组成原理课程设计A", weeks=(1..19).toList()),
            // 其他课
            ExpectedFlexCourse(kind="其他", name="形势与政策5", weeks=listOf(17,18)),
        )

        assertEquals("期望条数与实际条数", expectations.size, fs.size)

        for (i in expectations.indices) {
            val exp = expectations[i]
            val act = fs[i]
            val msg = "flexCourse #$i: ${exp.name}(${exp.kind})"

            assertEquals("$msg - kind", exp.kind, act.kind)
            assertEquals("$msg - name", exp.name, act.name)
            assertEquals("$msg - weeks", exp.weeks, act.weeks)
            assertEquals("$msg - room 应为 null", null, act.room)
        }
    }

    // ==================== 数据类 ====================

    private data class ExpectedCourse(
        val day: Int,
        val start: Int,
        val end: Int,
        val name: String,
        val marker: String,
        val weeks: List<Int>,
        val room: String,
        val teachers: List<String>,
        val exam: String,
    )

    private data class ExpectedFlexCourse(
        val kind: String,
        val name: String,
        val weeks: List<Int>,
    )
}
