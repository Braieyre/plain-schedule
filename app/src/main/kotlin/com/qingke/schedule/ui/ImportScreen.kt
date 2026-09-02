@file:OptIn(ExperimentalMaterial3Api::class)

package com.qingke.schedule.ui

import android.content.ActivityNotFoundException
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import com.qingke.schedule.WeekMath
import com.qingke.schedule.model.Course
import com.qingke.schedule.model.FlexCourse
import com.qingke.schedule.model.ParseResult
import com.qingke.schedule.model.PdfUnsupportedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 导入流程的四个步骤：选文件 -> 解析（含失败态）-> 预览+设开学日期 -> 确认导入。
 * 用 sealed interface 而不是裸 Int，是为了把每一步需要携带的数据（解析结果 / 失败原因）
 * 跟“当前在哪一步”绑在一起，避免出现“步骤是 3 但 result 还是 null”这种不一致状态。
 */
private sealed interface ImportStep {
    data object Pick : ImportStep
    data object Parsing : ImportStep
    data class Failed(val reason: String, val rawText: String) : ImportStep
    data class Preview(val result: ParseResult) : ImportStep
    data class Confirm(val result: ParseResult) : ImportStep
}

/**
 * 包一层 PDF 提取 + 解析调用，方便 T1（PdfText）/T2（ScheduleParser）落地后只改这一处。
 *
 * 提取与解析都在 core 模块，纯 JVM 无 Android 依赖，已由 ScheduleParserSmokeTest 端到端验证。
 */
private fun parsePdf(bytes: ByteArray): ParseResult {
    val spans = com.qingke.schedule.pdf.PdfText.extract(bytes)
    return com.qingke.schedule.parse.ScheduleParser.parse(spans)
}

/** 1=周一 … 7=周日。 */
private fun dayLabel(day: Int): String = when (day) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "?"
}

private fun periodLabel(start: Int, end: Int): String =
    if (start == end) "第${start}节" else "第${start}-${end}节"

/** 解析周数输入，范围 [1, 52]。 */
private fun parseWeekNumber(text: String): Int =
    text.toIntOrNull()?.coerceIn(1, 52) ?: 1

/** T6 实现：选 PDF -> 解析预览 -> 设开学日期 -> 确认导入。 */
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val appState = LocalAppState.current
    val context = LocalContext.current
    val palette = paletteOf(appState.settings.themeId)

    var step by remember { mutableStateOf<ImportStep>(ImportStep.Pick) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var parseJobId by remember { mutableStateOf(0) }
    var pickError by remember { mutableStateOf<String?>(null) }

    // 开学日期设置（步骤 3 用），默认方式①「今天是第几周」。
    var dateMethod by remember { mutableStateOf(1) }
    var weekNumberText by remember { mutableStateOf("1") }
    var pickedRawDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    val computedMonday: LocalDate = if (dateMethod == 1) {
        val n = parseWeekNumber(weekNumberText)
        WeekMath.startMondayFromCurrentWeek(LocalDate.now(), n)
    } else {
        WeekMath.snapToMonday(pickedRawDate ?: LocalDate.now())
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickError = null
            pickedUri = uri
            parseJobId += 1
            step = ImportStep.Parsing
        }
    }

    // 读文件 + 解析全部放到 IO 线程；解析结束前 step 一直停在 Parsing，界面显示 loading。
    LaunchedEffect(parseJobId) {
        if (parseJobId == 0) return@LaunchedEffect
        val uri = pickedUri ?: return@LaunchedEffect
        try {
            val result = withContext(Dispatchers.IO) {
                // 检查文件大小，防止选到超大文件导致 OOM。
                val fileSize = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.SIZE), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0) cursor.getLong(sizeIndex) else null
                    } else {
                        null
                    }
                }

                // 文件大小可查且超过 20 MB，直接拦掉。
                if (fileSize != null && fileSize > 20 * 1024 * 1024) {
                    val sizeMB = fileSize / (1024 * 1024)
                    throw PdfUnsupportedException("文件过大（$sizeMB MB），请确认选择的是课表 PDF")
                }

                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw PdfUnsupportedException("无法打开所选文件，请确认文件是否仍然存在")
                parsePdf(bytes)
            }
            step = ImportStep.Preview(result)
        } catch (e: CancellationException) {
            throw e // 页面被提前关闭导致协程取消，属于正常退出，不当作解析失败处理
        } catch (e: Throwable) {
            // 使用 Throwable 而不是 Exception，确保 OutOfMemoryError 等 Error 也能被正确捕获。
            val reason = e.message?.takeIf { it.isNotBlank() } ?: "解析失败，原因未知"
            val rawText = (e as? PdfUnsupportedException)?.rawText.orEmpty()
            step = ImportStep.Failed(reason, rawText)
        }
    }

    val currentStep = step
    val (stepIndex, stepLabel) = when (currentStep) {
        ImportStep.Pick -> 1 to "选择文件"
        ImportStep.Parsing -> 2 to "正在解析"
        is ImportStep.Failed -> 2 to "解析失败"
        is ImportStep.Preview -> 3 to "预览 · 设置开学日期"
        is ImportStep.Confirm -> 4 to "确认导入"
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar(title = "导入课表", onBack = onBack)
        Text(
            "步骤 $stepIndex/4 · $stepLabel",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (currentStep) {
                ImportStep.Pick -> PickContent(
                    pickError = pickError,
                    onPick = {
                        try {
                            launcher.launch(arrayOf("application/pdf"))
                        } catch (e: ActivityNotFoundException) {
                            pickError = "未找到可用的文件选择器，请安装一个文件管理器后重试"
                        }
                    },
                )

                ImportStep.Parsing -> ParsingContent()

                is ImportStep.Failed -> FailedContent(
                    reason = currentStep.reason,
                    rawText = currentStep.rawText,
                    onRetry = { step = ImportStep.Pick },
                )

                is ImportStep.Preview -> PreviewContent(
                    result = currentStep.result,
                    palette = palette,
                    dateMethod = dateMethod,
                    onDateMethodChange = { dateMethod = it },
                    weekNumberText = weekNumberText,
                    onWeekNumberChange = { weekNumberText = it },
                    pickedRawDate = pickedRawDate,
                    onPickDateClick = { showDatePicker = true },
                    computedMonday = computedMonday,
                    onReChoose = { step = ImportStep.Pick },
                    onNext = { step = ImportStep.Confirm(currentStep.result) },
                )

                is ImportStep.Confirm -> ConfirmContent(
                    result = currentStep.result,
                    computedMonday = computedMonday,
                    hasExisting = appState.hasSchedule,
                    onPrev = { step = ImportStep.Preview(currentStep.result) },
                    onConfirm = {
                        appState.importResult(currentStep.result, computedMonday)
                        onBack()
                    },
                )
            }
        }
    }

    if (showDatePicker) {
        // 限制年份范围为当前年份 ±2 年，防止用户误选导致导入后日期离谱。
        val currentYear = LocalDate.now().year
        val dpState = rememberDatePickerState(yearRange = (currentYear - 2)..(currentYear + 2))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // DatePickerState 的 selectedDateMillis 是 UTC 时区下的毫秒数，
                    // 必须用 ZoneOffset.UTC 转换，否则东八区会多算一天。
                    dpState.selectedDateMillis?.let { millis ->
                        pickedRawDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = dpState) }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun PickContent(pickError: String?, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "从教务系统下载课表 PDF 后，在这里选择文件",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("选择 PDF 文件", style = MaterialTheme.typography.titleMedium)
        }
        if (pickError != null) {
            Spacer(Modifier.height(12.dp))
            Text(pickError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ParsingContent() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("正在解析 PDF…", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 解析失败态：把原始文字暴露出来，方便用别的教务系统的同学反馈问题，绝不能省略。
 * 同时支持长按选中复制（SelectionContainer）和一键复制按钮两种方式。
 */
@Composable
private fun FailedContent(reason: String, rawText: String, onRetry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text("无法识别这份 PDF", style = MaterialTheme.typography.titleMedium)
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rawText.isNotBlank()) {
            Text("已提取到的原始文字（反馈问题时可以复制发给开发者）", style = MaterialTheme.typography.labelLarge)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                SelectionContainer { Text(rawText, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(rawText))
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }) { Text("复制") }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry, Modifier.fillMaxWidth()) { Text("重新选择文件") }
    }
}

@Composable
private fun PreviewContent(
    result: ParseResult,
    palette: CoursePalette,
    dateMethod: Int,
    onDateMethodChange: (Int) -> Unit,
    weekNumberText: String,
    onWeekNumberChange: (String) -> Unit,
    pickedRawDate: LocalDate?,
    onPickDateClick: () -> Unit,
    computedMonday: LocalDate,
    onReChoose: () -> Unit,
    onNext: () -> Unit,
) {
    val practiceCount = result.flexCourses.count { it.kind == "实践" }
    val otherCount = result.flexCourses.size - practiceCount
    val summary = buildString {
        append("识别到 ${result.courses.size} 门课、$practiceCount 门实践课")
        if (otherCount > 0) append("、$otherCount 门其他课程")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(summary, style = MaterialTheme.typography.titleMedium)
                Text(
                    result.semesterTitle ?: "未识别到学期名称，将存为“我的课表”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (result.warnings.isNotEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "解析提示",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        result.warnings.forEach { w ->
                            Text(
                                "• $w",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
        }
        items(result.courses, key = { it.id }) { c -> CourseRow(c, palette) }
        if (result.flexCourses.isNotEmpty()) {
            item {
                Text(
                    "实践 / 其他课程",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(result.flexCourses, key = { it.id }) { f -> FlexCourseRow(f, palette) }
        }
        item {
            Spacer(Modifier.height(8.dp))
            StartDateSection(
                dateMethod = dateMethod,
                onDateMethodChange = onDateMethodChange,
                weekNumberText = weekNumberText,
                onWeekNumberChange = onWeekNumberChange,
                pickedRawDate = pickedRawDate,
                onPickDateClick = onPickDateClick,
                computedMonday = computedMonday,
            )
        }
        item {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("下一步") }
            TextButton(onClick = onReChoose, modifier = Modifier.fillMaxWidth()) { Text("重新选择文件") }
        }
    }
}

@Composable
private fun CourseRow(c: Course, palette: CoursePalette) {
    ListItem(
        leadingContent = {
            val (dot, _) = courseColors(c.displayName, palette, 1f)
            Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
        },
        headlineContent = { Text(c.displayName) },
        supportingContent = {
            Column {
                val head = buildString {
                    append(dayLabel(c.day)).append(' ').append(periodLabel(c.startPeriod, c.endPeriod))
                    if (c.room.isNotBlank()) append(" · ").append(c.room)
                }
                Text(head, style = MaterialTheme.typography.bodySmall)
                Text(
                    WeekMath.formatWeekRanges(c.weeks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun FlexCourseRow(f: FlexCourse, palette: CoursePalette) {
    val name = f.name + f.marker
    ListItem(
        leadingContent = {
            val (dot, _) = courseColors(name, palette, 1f)
            Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
        },
        headlineContent = { Text(name) },
        supportingContent = {
            val parts = mutableListOf(f.kind, WeekMath.formatWeekRanges(f.weeks))
            f.room?.let { if (it.isNotBlank()) parts += it }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        },
    )
}

/**
 * 设置开学日期：方式① 今天是第几周（默认）；方式② 选开学那周任意一天。
 * 两种方式算出的 Monday 实时显示在下方，导入前用户能立刻确认对不对。
 */
@Composable
private fun StartDateSection(
    dateMethod: Int,
    onDateMethodChange: (Int) -> Unit,
    weekNumberText: String,
    onWeekNumberChange: (String) -> Unit,
    pickedRawDate: LocalDate?,
    onPickDateClick: () -> Unit,
    computedMonday: LocalDate,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("设置开学日期", style = MaterialTheme.typography.titleSmall)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onDateMethodChange(1) },
        ) {
            RadioButton(selected = dateMethod == 1, onClick = { onDateMethodChange(1) })
            Text("今天是第几周")
        }
        if (dateMethod == 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 40.dp),
            ) {
                val n = parseWeekNumber(weekNumberText)
                IconButton(onClick = { onWeekNumberChange(maxOf(1, n - 1).toString()) }) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = weekNumberText,
                    onValueChange = { v -> if (v.length <= 2 && v.all { it.isDigit() }) onWeekNumberChange(v) },
                    modifier = Modifier.width(72.dp),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                IconButton(onClick = { onWeekNumberChange(minOf(52, n + 1).toString()) }) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
                Text("周", Modifier.padding(start = 4.dp))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onDateMethodChange(2) },
        ) {
            RadioButton(selected = dateMethod == 2, onClick = { onDateMethodChange(2) })
            Text("选择开学那一周的任意一天")
        }
        if (dateMethod == 2) {
            OutlinedButton(onClick = onPickDateClick, modifier = Modifier.padding(start = 40.dp)) {
                Text(pickedRawDate?.toString() ?: "选择日期")
            }
        }

        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "开学日：$computedMonday（周一）",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ConfirmContent(
    result: ParseResult,
    computedMonday: LocalDate,
    hasExisting: Boolean,
    onPrev: () -> Unit,
    onConfirm: () -> Unit,
) {
    var showOverwrite by remember { mutableStateOf(false) }
    val practiceCount = result.flexCourses.count { it.kind == "实践" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("确认导入信息", style = MaterialTheme.typography.titleMedium)
        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("学期：${result.semesterTitle ?: "我的课表"}")
                Text("课程：${result.courses.size} 门课、$practiceCount 门实践课")
                Text("开学日：$computedMonday（周一）")
            }
        }
        if (hasExisting) {
            Text(
                "导入将覆盖现有课表，此操作不可撤销。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onPrev, modifier = Modifier.weight(1f)) { Text("上一步") }
            Button(
                onClick = { if (hasExisting) showOverwrite = true else onConfirm() },
                modifier = Modifier.weight(1f),
            ) { Text("确认导入") }
        }
    }

    if (showOverwrite) {
        AlertDialog(
            onDismissRequest = { showOverwrite = false },
            title = { Text("覆盖确认") },
            text = { Text("导入将覆盖现有课表，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = { showOverwrite = false; onConfirm() }) { Text("确定覆盖") }
            },
            dismissButton = { TextButton(onClick = { showOverwrite = false }) { Text("取消") } },
        )
    }
}
