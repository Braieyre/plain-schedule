package com.qingke.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qingke.schedule.model.Course

/** 周几芯片标签，与 Course.day（1=周一…7=周日）一一对应。 */
private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 学时标记单选项，"无" 对应 Course.marker 的空串。 */
private val MARKER_OPTIONS = listOf("★", "●", "▲", "■", "无")

/**
 * T4 实现：手动新增 / 编辑 / 删除课程。existing 为 null 表示新增。
 * 全屏表单：顶栏固定，中间内容整体可滚动，避开系统栏与输入法。
 */
@Composable
fun CourseEditScreen(existing: Course?, onDone: () -> Unit) {
    val state = LocalAppState.current
    val totalWeeks = state.totalWeeks

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var day by remember(existing) { mutableStateOf(existing?.day?.coerceIn(1, 7) ?: 1) }
    var startPeriod by remember(existing) { mutableStateOf(existing?.startPeriod?.coerceIn(1, 12) ?: 1) }
    var endPeriod by remember(existing) {
        // 用 existing 的起止节次算初值；万一存量数据脏了（end < start），直接钳成合法值，
        // 后面 PeriodStepper 的 range 会持续维护 1 <= start <= end <= 12 这条不变式。
        val s = existing?.startPeriod?.coerceIn(1, 12) ?: 1
        val e = (existing?.endPeriod ?: s).coerceIn(1, 12)
        mutableStateOf(maxOf(e, s))
    }
    var weeks by remember(existing) { mutableStateOf(existing?.weeks?.toSet() ?: emptySet()) }
    var room by remember(existing) { mutableStateOf(existing?.room ?: "") }
    var teachersText by remember(existing) { mutableStateOf(existing?.teachers?.joinToString("、") ?: "") }
    var examText by remember(existing) { mutableStateOf(existing?.exam ?: "") }
    var marker by remember(existing) { mutableStateOf(existing?.marker?.takeIf { it.isNotEmpty() } ?: "无") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val canSave = name.isNotBlank() && weeks.isNotEmpty()

    fun save() {
        if (!canSave) return
        val trimmedName = name.trim()
        val course = Course(
            // 新增用确定性 id，与 T2 解析器保持一致；编辑沿用原 id，upsertCourse 才会覆盖而不是新增一条。
            id = existing?.id ?: "$day-$startPeriod-$trimmedName",
            name = trimmedName,
            marker = if (marker == "无") "" else marker,
            day = day,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            weeks = weeks.sorted(),
            room = room.trim(),
            teachers = splitTeachers(teachersText),
            exam = examText.trim().ifBlank { null },
        )
        state.upsertCourse(course)
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        // 顶栏：返回 / 标题 / （编辑态）删除 + 保存。都用纯文字按钮，行为最直白。
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterStart)) {
                Text("返回")
            }
            Text(
                text = if (existing == null) "添加课程" else "编辑课程",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center),
            )
            Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                if (existing != null) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = { save() }, enabled = canSave) {
                    Text("保存")
                }
            }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            FieldLabel("课名")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("必填") },
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))
            FieldLabel("周几")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WEEKDAY_LABELS.forEachIndexed { index, label ->
                    val d = index + 1
                    FilterChip(selected = day == d, onClick = { day = d }, label = { Text(label) })
                }
            }

            Spacer(Modifier.height(24.dp))
            FieldLabel("起止节次（第 $startPeriod-$endPeriod 节）")
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeriodStepper(
                    value = startPeriod,
                    range = 1..endPeriod,
                    onChange = { startPeriod = it },
                )
                Text(
                    "至",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PeriodStepper(
                    value = endPeriod,
                    range = startPeriod..12,
                    onChange = { endPeriod = it },
                )
            }

            Spacer(Modifier.height(24.dp))
            FieldLabel("周次（共 $totalWeeks 周，已选 ${weeks.size} 周）")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickWeekButton("全选") { weeks = (1..totalWeeks).toSet() }
                QuickWeekButton("全不选") { weeks = emptySet() }
                QuickWeekButton("单周") { weeks = (1..totalWeeks).filter { it % 2 == 1 }.toSet() }
                QuickWeekButton("双周") { weeks = (1..totalWeeks).filter { it % 2 == 0 }.toSet() }
            }
            Spacer(Modifier.height(10.dp))
            WeekGrid(
                totalWeeks = totalWeeks,
                selected = weeks,
                onToggle = { w -> weeks = if (w in weeks) weeks - w else weeks + w },
            )

            Spacer(Modifier.height(24.dp))
            FieldLabel("教室")
            OutlinedTextField(
                value = room,
                onValueChange = { room = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("可留空") },
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))
            FieldLabel("老师")
            OutlinedTextField(
                value = teachersText,
                onValueChange = { teachersText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("多个用 , 或 、 分隔，可留空") },
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))
            FieldLabel("考核方式")
            OutlinedTextField(
                value = examText,
                onValueChange = { examText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("如：考试 / 考查，可留空") },
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))
            FieldLabel("学时标记")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MARKER_OPTIONS.forEach { option ->
                    FilterChip(selected = marker == option, onClick = { marker = option }, label = { Text(option) })
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除课程") },
            text = { Text("确定删除「${existing.displayName}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    state.deleteCourse(existing.id)
                    onDone()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun QuickWeekButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

/** 起/止节次的数字步进器，range 由调用方保证互相钳制到 1..12 内的合法区间。 */
@Composable
private fun PeriodStepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepSquare(text = "－", enabled = value - 1 >= range.first) { onChange(value - 1) }
        Text(
            text = "第${value}节",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 8.dp),
        )
        StepSquare(text = "＋", enabled = value + 1 <= range.last) { onChange(value + 1) }
    }
}

@Composable
private fun StepSquare(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * 1..totalWeeks 的可点选周次网格，每行最多 7 格。用普通 Column/Row 手写而不是
 * LazyVerticalGrid —— 后者嵌在外层 verticalScroll 里会因为拿不到有限高度而崩，
 * 手写循环则完全没有这个问题，totalWeeks 很小（比如 1）时也只是渲染一行一格。
 */
@Composable
private fun WeekGrid(totalWeeks: Int, selected: Set<Int>, onToggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var rowStart = 1
        while (rowStart <= totalWeeks) {
            val rowEnd = minOf(rowStart + 6, totalWeeks)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (w in rowStart..rowEnd) {
                    WeekCell(week = w, selected = w in selected, onClick = { onToggle(w) })
                }
            }
            rowStart += 7
        }
    }
}

@Composable
private fun WeekCell(week: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$week",
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 老师输入框按半角逗号 / 全角逗号 / 顿号拆分，去首尾空白、丢弃空项。 */
private fun splitTeachers(text: String): List<String> =
    text.split(',', '，', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
