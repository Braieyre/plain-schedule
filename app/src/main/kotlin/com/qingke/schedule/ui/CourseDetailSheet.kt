package com.qingke.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qingke.schedule.WeekMath
import com.qingke.schedule.model.Course
import com.qingke.schedule.model.PeriodTime

/** 周几显示名，与 Course.day（1=周一…7=周日）一一对应。 */
private val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * T4 实现：点击课程弹出的详情卡（参考截图 03）。
 * 用 Dialog 承载半透明遮罩 + 居中圆角卡片：点遮罩或按返回键都会触发 onDismissRequest，
 * 也就是 Dialog 的默认行为（dismissOnClickOutside / dismissOnBackPress 都默认为 true）。
 */
@Composable
fun CourseDetailSheet(
    course: Course,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val state = LocalAppState.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            // 色调抬升必须归零：tonalElevation 会在 surface 之上再叠一层色调，
            // 使卡片实际底色 ≠ colorScheme.surface，头部渐变收到 surface 时就会切出一条接缝。
            // 层次改由阴影表现，这样渐变终点和内容区底色严格相等，过渡无缝。
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
            ) {
                // 彩色头部区：课名+副标题（周几/节次/时间）
                val palette = paletteOf(state.settings.themeId)
                val (bgColor, textColor) = courseColors(course.name, palette, names = state.courseNames)
                // 头部不用纯色块——纯色块的下边缘和白色内容区会切出一条硬边。
                // 改成竖向渐变：顶部是课程色（略往文字色提一点，让颜色立得住），
                // 到底部正好收成 surface，于是和下面的内容区无缝化开，看不出接缝。
                val surface = MaterialTheme.colorScheme.surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(lerp(bgColor, textColor, 0.16f), bgColor, surface)
                            )
                        )
                        .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
                ) {
                    Column {
                        Text(
                            text = course.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                        )
                        val scheduleLine = scheduleLineOf(course, state.settings.periodTimes)
                        if (scheduleLine != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = scheduleLine,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor.copy(alpha = 0.75f),
                            )
                        }
                    }
                }

                // 内容区：属性信息
                Column(
                    modifier = Modifier
                        .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 8.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 周次
                    val weekRanges = WeekMath.formatWeekRanges(course.weeks).replace(" | ", " · ")
                    if (weekRanges.isNotBlank()) {
                        DetailField("周次", weekRanges)
                    }

                    // 教室和老师：都非空时并排，否则独占一行
                    if (course.room.isNotBlank() || course.teachers.isNotEmpty()) {
                        if (course.room.isNotBlank() && course.teachers.isNotEmpty()) {
                            // 都非空，并排显示
                            Row(modifier = Modifier.fillMaxWidth()) {
                                DetailField(
                                    "教室",
                                    course.room,
                                    modifier = Modifier.weight(1f),
                                )
                                DetailField(
                                    "老师",
                                    course.teachers.joinToString("、"),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else if (course.room.isNotBlank()) {
                            // 只有教室
                            DetailField("教室", course.room)
                        } else {
                            // 只有老师
                            DetailField("老师", course.teachers.joinToString("、"))
                        }
                    }

                    // 考核方式
                    val exam = course.exam
                    if (!exam.isNullOrBlank()) {
                        DetailField("考核方式", exam)
                    }
                }

                // 底部操作区
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                    FilledTonalButton(onClick = onEdit) {
                        Text("编辑")
                    }
                }
            }
        }
    }
}

/**
 * 属性字段：两级层次（label + value）。
 * label 用 11sp / onSurfaceVariant，value 用 bodyLarge / onSurface。
 * label 和 value 之间间距 4dp。
 */
@Composable
private fun DetailField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 拼「周二 · 第5-6节 · 13:00-14:40」这一行。
 * settings.periodTimes 里查不到 startPeriod/endPeriod 对应的时刻时，只省略时间段，
 * 周几与节次照常显示，绝不会因为查不到时间就崩掉。
 */
private fun scheduleLineOf(course: Course, periodTimes: List<PeriodTime>): String? {
    val dayLabel = WEEKDAY_NAMES.getOrNull(course.day - 1)
    val periodLabel = if (course.startPeriod == course.endPeriod) {
        "第${course.startPeriod}节"
    } else {
        "第${course.startPeriod}-${course.endPeriod}节"
    }
    val startTime = periodTimes.firstOrNull { it.index == course.startPeriod }
    val endTime = periodTimes.firstOrNull { it.index == course.endPeriod }
    val timeLabel = if (startTime != null && endTime != null) "${startTime.start}-${endTime.end}" else null

    val parts = listOfNotNull(dayLabel, periodLabel, timeLabel)
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}
