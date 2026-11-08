package com.qingke.schedule.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.qingke.schedule.transfer.ScheduleTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ExportScreen(onBack: () -> Unit) {
    val state = LocalAppState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 在打开保存面板时固定这一份快照，避免返回后导出内容悄悄改变。
    var pendingJson by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingJson
        pendingJson = null
        if (uri != null && content != null) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("无法写入所选位置，请换一个保存位置")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                    }
                    Toast.makeText(context, "课表已保存，可以发给同学导入", Toast.LENGTH_LONG).show()
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { error = e.message ?: "保存失败，请重试"
                } finally { busy = false }
            }
        }
    }

    fun snapshot(): String? = try {
        val data = state.schedule ?: error("请先添加或导入课程")
        ScheduleTransfer.encode(data, state.settings.periodTimes).also { error = null }
    } catch (e: Exception) { error = e.message ?: "无法导出课表"; null }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("导出课表", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.semester?.title ?: "尚未添加课表", style = MaterialTheme.typography.titleMedium)
            Text("${state.courses.size} 条上课安排 · ${state.flexCourses.size} 门实践 / 其他课程")
            Text("导出当前课表为 JSON 文件，包含你添加和修改的课程、开学日期及上课时间。同学在「导入课表」里选择该文件，就能使用同一份课表。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled = state.hasSchedule && !busy && pendingJson == null, modifier = Modifier.fillMaxWidth(), onClick = {
                snapshot()?.let { content ->
                    pendingJson = content
                    try { save.launch("普通课程表-${state.semester?.startMonday}.json")
                    } catch (e: Exception) { pendingJson = null; error = "无法打开保存面板：${e.message}" }
                }
            }) { Text("保存课表文件") }
            OutlinedButton(enabled = state.hasSchedule && !busy && pendingJson == null, modifier = Modifier.fillMaxWidth(), onClick = {
                val content = snapshot() ?: return@OutlinedButton
                scope.launch {
                    busy = true
                    try {
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(context.cacheDir, "shared-schedules").apply { mkdirs() }
                            File.createTempFile("课表-", ".json", dir).apply { writeText(content, Charsets.UTF_8) }
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.schedule-files", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("课表", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享课表"))
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { error = e.message ?: "分享失败，请先保存文件后发送"
                    } finally { busy = false }
                }
            }) { Text("分享给同学") }
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
