@file:OptIn(ExperimentalMaterial3Api::class)

package com.qingke.schedule.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qingke.schedule.model.DEFAULT_PERIOD_TIMES
import com.qingke.schedule.model.PeriodTime

/** T5 实现：编辑每节课的起止时间（抽屉「上课时间」入口指向此页）。 */
@Composable
fun PeriodTimesScreen(onBack: () -> Unit) {
    val state = LocalAppState.current
    val settings = state.settings
    // 正常情况下有 12 节；即便被改成别的长度，也只按现有条目渲染，不假设固定 12 项，避免崩溃。
    val periods = remember(settings.periodTimes) { settings.periodTimes.sortedBy { it.index } }

    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            // 系统栏内边距已经由外层 Box 统一处理，这里清零避免重复叠加。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text("上课时间") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedButton(
                            onClick = { showResetConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("恢复默认") }
                    }
                }
            },
        ) { padding ->
            if (periods.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("暂无上课时间数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    // 不用 index 当 key：数据一旦被改坏出现重复 index 时，用 key 会直接崩溃。
                    items(periods) { period ->
                        PeriodRow(
                            period = period,
                            onEditStart = { editing = EditTarget(period.index, isStart = true) },
                            onEditEnd = { editing = EditTarget(period.index, isStart = false) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }

    editing?.let { target ->
        val currentValue = settings.periodTimes.firstOrNull { it.index == target.periodIndex }
            ?.let { if (target.isStart) it.start else it.end }
            ?: "08:00"
        AppTimePickerDialog(
            title = if (target.isStart) "第${target.periodIndex}节 · 开始时间" else "第${target.periodIndex}节 · 结束时间",
            initial = currentValue,
            onConfirm = { newTime ->
                val updated = settings.periodTimes.map { pt ->
                    when {
                        pt.index != target.periodIndex -> pt
                        target.isStart -> pt.copy(start = newTime)
                        else -> pt.copy(end = newTime)
                    }
                }
                state.updateSettings { it.copy(periodTimes = updated) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认时间") },
            text = { Text("将把全部节次的上下课时间恢复为系统默认值，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    state.updateSettings { it.copy(periodTimes = DEFAULT_PERIOD_TIMES) }
                    showResetConfirm = false
                }) { Text("恢复默认") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("取消") } },
        )
    }
}

/** 正在编辑第几节课的哪一端时间。 */
private data class EditTarget(val periodIndex: Int, val isStart: Boolean)

@Composable
private fun PeriodRow(period: PeriodTime, onEditStart: () -> Unit, onEditEnd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "第${period.index}节",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        TimeChip(period.start, onEditStart)
        Text(
            text = "－",
            modifier = Modifier.padding(horizontal = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TimeChip(period.end, onEditEnd)
    }
}

@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 把 "HH:mm" 安全解析成 (时, 分)；格式异常时兜底为 8:00，绝不抛异常。 */
private fun parseHm(text: String): Pair<Int, Int> {
    val parts = text.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hour to minute
}

/** Material3 没有现成的 TimePickerDialog，这里按官方推荐用法把 TimePicker 包进 AlertDialog。 */
@Composable
private fun AppTimePickerDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (initialHour, initialMinute) = remember(initial) { parseHm(initial) }
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm("%02d:%02d".format(state.hour, state.minute)) }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
