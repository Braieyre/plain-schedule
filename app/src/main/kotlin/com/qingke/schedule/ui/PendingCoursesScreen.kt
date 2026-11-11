package com.qingke.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qingke.schedule.model.Course

@Composable
fun PendingCoursesScreen(onBack: () -> Unit, onFill: (Course) -> Unit) {
    val pending = LocalAppState.current.schedule?.pendingCourses.orEmpty()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        TextButton(onClick = onBack) { Text("返回课表") }
        Text("待补充安排", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Text("这些安排缺少周次，暂不显示在周视图。补充后会加入课表，其他已识别的同名课程不受影响。", modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pending, key = { it.id }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text("周${"一二三四五六日"[item.day - 1]} · " + (item.startPeriod?.let { "第 $it-${item.endPeriod} 节" } ?: "节次待补充"))
                        Text("来源：第 ${item.sourcePage ?: "?"} 页 · 周次未读取到", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onFill(Course(item.id, item.name, day = item.day,
                            startPeriod = item.startPeriod ?: 1, endPeriod = item.endPeriod ?: item.startPeriod ?: 1, weeks = emptyList())) }) { Text("补充并加入课表") }
                    }
                }
            }
        }
    }
}
