@file:OptIn(ExperimentalMaterial3Api::class)

package com.qingke.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingke.schedule.BuildConfig
import com.qingke.schedule.R
import com.qingke.schedule.Screen
import com.qingke.schedule.WeekMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * T5 实现：设置抽屉（参考截图 04）。
 * 自上而下：导入入口 / 上课时间 / 开学日期 / 「通用设置」分组（显示节数、起始日、非本周课程、换肤）。
 */
@Composable
fun SettingsDrawer(onNavigate: (Screen) -> Unit, onClose: () -> Unit) {
    val state = LocalAppState.current
    val settings = state.settings
    val hasSchedule = state.hasSchedule

    var showStartDateDialog by remember { mutableStateOf(false) }
    var showWeekStartDialog by remember { mutableStateOf(false) }

    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight(),
        // 自己接管系统栏内边距（下面用 systemBarsPadding），避免和默认 insets 重复叠加。
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // 菜单项区占满剩余高度并可滚动，把 DrawerFooter 压在抽屉底部；
            // footer 放在滚动区外面，条目再多也不会把它顶走。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                DrawerHeader(
                    subtitle = state.semester?.title ?: "尚未导入课表",
                    onClose = onClose,
                )

                DrawerItem(
                    title = "导入 PDF 课表",
                    onClick = { onNavigate(Screen.Import) },
                )
                DrawerItem(
                    title = "上课时间",
                    onClick = { onNavigate(Screen.PeriodTimes) },
                )
                DrawerItem(
                    title = "设置开学日期",
                    value = state.startMonday?.toString(),
                    // 无课表时没有学期可挂开学日期，整行置灰且不可点。
                    onClick = if (hasSchedule) ({ showStartDateDialog = true }) else null,
                )

                SectionLabel("通用设置")

                DrawerItem(
                    title = "每周起始日",
                    value = if (settings.weekStartsSunday) "周日" else "周一",
                    onClick = { showWeekStartDialog = true },
                )
                DrawerItem(
                    title = "显示非本周课程",
                    showArrow = false,
                    trailing = {
                        Switch(
                            checked = settings.showOtherWeeks,
                            onCheckedChange = { checked ->
                                state.updateSettings { it.copy(showOtherWeeks = checked) }
                            },
                        )
                    },
                    onClick = {
                        state.updateSettings { it.copy(showOtherWeeks = !it.showOtherWeeks) }
                    },
                )
                DrawerItem(
                    title = "个性换肤",
                    value = paletteOf(settings.themeId).label,
                    onClick = { onNavigate(Screen.Appearance) },
                )

                Spacer(Modifier.height(24.dp))
            }

            DrawerFooter()
        }
    }

    if (showStartDateDialog && hasSchedule) {
        StartDateDialog(
            initialStartMonday = state.startMonday,
            initialCurrentWeek = state.currentWeek(),
            onConfirm = { date ->
                state.setStartMonday(date)
                showStartDateDialog = false
            },
            onDismiss = { showStartDateDialog = false },
        )
    }
    if (showWeekStartDialog) {
        RadioListDialog(
            title = "每周起始日",
            options = listOf("周一", "周日"),
            selectedIndex = if (settings.weekStartsSunday) 1 else 0,
            onSelect = { idx ->
                state.updateSettings { it.copy(weekStartsSunday = idx == 1) }
                showWeekStartDialog = false
            },
            onDismiss = { showWeekStartDialog = false },
        )
    }
}

/** 抽屉顶部：标题 + 学期副标题 + 右上角关闭按钮。 */
@Composable
private fun DrawerHeader(subtitle: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "课表设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "（$subtitle）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭设置")
        }
    }
}

/** 分组小标题，如「通用设置」。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** 抽屉底部：应用名 + 版本号 + 一句话说明。 */
@Composable
private fun DrawerFooter() {
    // 不画分隔线：footer 已经被压到抽屉底部，和菜单项之间隔着一大片留白，
    // 再加一条横线只会在空白里凭空多一道横杠。
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp)) {
        Text(
            // 应用名读 strings.xml、版本号读 BuildConfig，两处都不另抄一份，改名改版本时自动跟着走。
            text = "${stringResource(R.string.app_name)}  v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "就是个好用的课程表。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 抽屉里的一行设置项。[onClick] 为 null 时整行置灰且不可点（用于无课表时的「设置开学日期」）。
 * [trailing] 非空则替换默认的 `>` 箭头（如显示 Switch）。
 */
@Composable
private fun DrawerItem(
    title: String,
    value: String? = null,
    showArrow: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val alpha = if (onClick != null) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        when {
            trailing != null -> trailing()
            showArrow -> Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

/** 单选列表弹窗，点选项立即生效并关闭（用于「课表显示节数」「每周起始日」）。 */
@Composable
private fun RadioListDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        Spacer(Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 设置开学日期弹窗，两种输入方式二选一：
 * ① 日期选择器选开学第一周的任意一天，保存时用 [WeekMath.snapToMonday] 吸附到周一；
 * ② 「今天是第 N 周」反推（默认，对学生更直觉），用 [WeekMath.startMondayFromCurrentWeek]。
 */
@Composable
private fun StartDateDialog(
    initialStartMonday: LocalDate?,
    initialCurrentWeek: Int,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var byWeekNumber by remember { mutableStateOf(true) }
    var weekNumber by remember { mutableIntStateOf(initialCurrentWeek.coerceIn(1, 52)) }
    var pickedDate by remember { mutableStateOf(initialStartMonday ?: LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val resultMonday = if (byWeekNumber) {
        WeekMath.startMondayFromCurrentWeek(LocalDate.now(), weekNumber)
    } else {
        WeekMath.snapToMonday(pickedDate)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置开学日期") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    ModeTab("第几周", selected = byWeekNumber, modifier = Modifier.weight(1f)) { byWeekNumber = true }
                    ModeTab("选日期", selected = !byWeekNumber, modifier = Modifier.weight(1f)) { byWeekNumber = false }
                }
                Spacer(Modifier.height(16.dp))
                if (byWeekNumber) {
                    Text(
                        text = "今天是开学第几周？",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StepperButton("－") { weekNumber = (weekNumber - 1).coerceIn(1, 52) }
                        Text(
                            text = "第 $weekNumber 周",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        StepperButton("＋") { weekNumber = (weekNumber + 1).coerceIn(1, 52) }
                    }
                } else {
                    Text(
                        text = "选开学第一周内任意一天，保存时自动对齐到周一",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showDatePicker = true }) {
                        Text("选择日期：$pickedDate")
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "开学周一：$resultMonday",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(resultMonday) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDatePicker) {
        // DatePicker 约定 selectedDateMillis 是 UTC 零点，统一按 UTC 换算，避免时区导致偏一天。
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = pickedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = dpState)
        }
    }
}

/** 「第几周 / 选日期」模式切换的一个分段。 */
@Composable
private fun ModeTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 「今天是第 N 周」的数字选择用的加减圆钮。 */
@Composable
private fun StepperButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
