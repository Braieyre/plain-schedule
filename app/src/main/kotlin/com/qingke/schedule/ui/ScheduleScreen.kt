package com.qingke.schedule.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.schedule.WeekMath
import com.qingke.schedule.data.AppState
import com.qingke.schedule.model.Course
import com.qingke.schedule.model.PeriodTime
import com.qingke.schedule.model.Settings
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 左侧节次列的固定宽度，日期行与课表网格共用，保证两行对齐。 */
private val SideColumnWidth = 34.dp

/** 每节课的行高，网格总高 = 行高 × 显示节数。 */
private val PeriodRowHeight = 84.dp

/**
 * T3 实现：主课表页（参考截图 01 / 02 / 05）。
 * 结构：整屏背景层（图片/纯色） + 避让系统栏的前景（顶栏/日期行/周视图 Pager）。
 */
@Composable
fun ScheduleScreen(
    onOpenDrawer: () -> Unit,
    onAddCourse: () -> Unit,
    onEditCourse: (Course) -> Unit,
) {
    val state = LocalAppState.current
    val settings = state.settings

    Box(Modifier.fillMaxSize()) {
        BackgroundLayer(state.background, settings)

        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            if (!state.hasSchedule) {
                EmptyScheduleState(onOpenDrawer)
            } else {
                ScheduleContent(
                    state = state,
                    onOpenDrawer = onOpenDrawer,
                    onAddCourse = onAddCourse,
                    onEditCourse = onEditCourse,
                )
            }
        }
    }
}

/** 整屏背景：有自定义背景图则铺满裁切 + 黑色蒙层调暗，否则用主题背景色。 */
@Composable
private fun BackgroundLayer(background: Bitmap?, settings: Settings) {
    if (background != null) {
        Image(
            bitmap = background.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = settings.bgDim)),
        )
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

/** 还没有课表时的整屏引导。 */
@Composable
private fun EmptyScheduleState(onOpenDrawer: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "还没有课表",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "去设置里导入 PDF，马上就能看到课表",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onOpenDrawer) { Text("打开设置") }
    }
}

/**
 * 有课表时的主体内容：顶栏 + 日期行 + 按周翻页的课表网格，
 * 外加浮在右下角的「切回本周」按钮和点击课程后弹出的详情卡。
 */
@Composable
private fun ScheduleContent(
    state: AppState,
    onOpenDrawer: () -> Unit,
    onAddCourse: () -> Unit,
    onEditCourse: (Course) -> Unit,
) {
    val settings = state.settings
    val totalWeeks = state.totalWeeks
    val todayWeek = state.currentWeek()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = (todayWeek - 1).coerceIn(0, totalWeeks - 1)) { totalWeeks }
    val displayedWeek = pagerState.currentPage + 1

    var selectedCourse by remember { mutableStateOf<Course?>(null) }

    val dayOrder = if (settings.weekStartsSunday) listOf(7, 1, 2, 3, 4, 5, 6) else listOf(1, 2, 3, 4, 5, 6, 7)
    val dayNames = if (settings.weekStartsSunday) {
        listOf("日", "一", "二", "三", "四", "五", "六")
    } else {
        listOf("一", "二", "三", "四", "五", "六", "日")
    }

    // 网格行数由课表数据的最大结束节次决定，最少显示 1 节、最多 12 节，
    // 避免课程卡片被裁掉或错位。
    val rows = (state.courses.maxOfOrNull { it.endPeriod } ?: 10).coerceIn(1, 12)
    val dark = isAppInDarkTheme(settings)
    val palette = paletteOf(settings.themeId)
    val courseNames = state.courseNames

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScheduleTopBar(
                displayedWeek = displayedWeek,
                todayWeek = todayWeek,
                totalWeeks = totalWeeks,
                subtitle = state.semester?.title.orEmpty(),
                onOpenDrawer = onOpenDrawer,
                onAddCourse = onAddCourse,
                onPickWeek = { w -> scope.launch { pagerState.animateScrollToPage(w - 1) } },
            )
            DateHeaderRow(
                startMonday = state.startMonday,
                week = displayedWeek,
                dayOrder = dayOrder,
                dayNames = dayNames,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                WeekGridPage(
                    week = page + 1,
                    courses = state.courses,
                    rows = rows,
                    periodTimes = settings.periodTimes,
                    dayOrder = dayOrder,
                    showOtherWeeks = settings.showOtherWeeks,
                    dark = dark,
                    palette = palette,
                    cardAlpha = settings.cardAlpha,
                    courseNames = courseNames,
                    onCourseClick = { selectedCourse = it },
                )
            }
        }

        AnimatedVisibility(
            visible = displayedWeek != todayWeek,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            BackToCurrentWeekPill(
                onClick = { scope.launch { pagerState.animateScrollToPage(todayWeek - 1) } },
            )
        }
    }

    selectedCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onDismiss = { selectedCourse = null },
            onEdit = {
                onEditCourse(course)
                selectedCourse = null
            },
        )
    }
}

/** 顶栏：抽屉按钮 · 「第 N 周 ▼」+ 学期副标题（点击弹周次选择）· 加号。 */
@Composable
private fun ScheduleTopBar(
    displayedWeek: Int,
    todayWeek: Int,
    totalWeeks: Int,
    subtitle: String,
    onOpenDrawer: () -> Unit,
    onAddCourse: () -> Unit,
    onPickWeek: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 当前显示周与今天所在周不一致时，标题用醒目的橙色提示用户「这不是本周」。
    val titleColor = if (displayedWeek != todayWeek) AccentOrange else MaterialTheme.colorScheme.onBackground

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "打开菜单", tint = MaterialTheme.colorScheme.onBackground)
        }
        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "第${displayedWeek}周",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "选择周次",
                        tint = titleColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (w in 1..totalWeeks) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (w == todayWeek) "第${w}周 · 本周" else "第${w}周",
                                fontWeight = if (w == displayedWeek) FontWeight.Bold else FontWeight.Normal,
                                color = if (w == todayWeek) AccentOrange else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            expanded = false
                            onPickWeek(w)
                        },
                    )
                }
            }
        }
        AddButton(onClick = onAddCourse)
    }
}

/** 顶栏右侧黑底白「+」方角按钮：用 onBackground/background 互换色，深浅色主题自动保持高对比。 */
@Composable
private fun AddButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "添加课程",
            tint = MaterialTheme.colorScheme.background,
        )
    }
}

/** 右下角「切回本周」胶囊按钮，AccentOrange 是契约里明确豁免的强调色，白字与之搭配是固定组合。 */
@Composable
private fun BackToCurrentWeekPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = AccentOrange,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = "切回本周",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * 日期行：最左一格显示月份，右侧 7 列是「周几 + 日期数字」。
 * startMonday 为 null（学期起始日解析失败等异常情况）时只显示周几，日期格留空，不崩不算错日期。
 */
@Composable
private fun DateHeaderRow(startMonday: LocalDate?, week: Int, dayOrder: List<Int>, dayNames: List<String>) {
    val today = LocalDate.now()

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.width(SideColumnWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val firstDate = startMonday?.let { WeekMath.dateOf(it, week, dayOrder.first()) }
            if (firstDate != null) {
                Text("${firstDate.monthValue}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("月", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        dayOrder.forEachIndexed { i, day ->
            val date = startMonday?.let { WeekMath.dateOf(it, week, day) }
            val isToday = date != null && date == today
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = dayNames[i],
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                // 每月 1 号用「N月」代替日期数字，提示月份切换（参考截图 01 周二那格）。
                val label = when {
                    date == null -> ""
                    date.dayOfMonth == 1 -> "${date.monthValue}月"
                    else -> "${date.dayOfMonth}"
                }
                Box(
                    modifier = if (isToday) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onBackground)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    } else {
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        color = if (isToday) {
                            MaterialTheme.colorScheme.background
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * 单周课表网格：左侧节次列 + 7 天 × rows 节的背景网格，课程卡片按绝对坐标叠加在上层。
 * 整体包在 verticalScroll 里（内部都是普通 Row/Column，不是 Lazy 组件，可以安全嵌套）。
 */
@Composable
private fun WeekGridPage(
    week: Int,
    courses: List<Course>,
    rows: Int,
    periodTimes: List<PeriodTime>,
    dayOrder: List<Int>,
    showOtherWeeks: Boolean,
    dark: Boolean,
    palette: CoursePalette,
    cardAlpha: Float,
    courseNames: List<String>,
    onCourseClick: (Course) -> Unit,
) {
    val gridLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        val dayWidth = (maxWidth - SideColumnWidth) / 7
        val totalHeight = PeriodRowHeight * rows

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(totalHeight)) {
                // 背景层：左侧节次列 + 7 天网格线
                Row(Modifier.matchParentSize()) {
                    Column(Modifier.width(SideColumnWidth)) {
                        for (p in 1..rows) PeriodLabelCell(p, periodTimes)
                    }
                    repeat(7) {
                        Column(Modifier.width(dayWidth)) {
                            for (p in 1..rows) {
                                Box(
                                    modifier = Modifier
                                        .width(dayWidth)
                                        .height(PeriodRowHeight)
                                        .drawBehind {
                                            drawLine(
                                                color = gridLineColor,
                                                start = Offset(0f, size.height),
                                                end = Offset(size.width, size.height),
                                                strokeWidth = 1.dp.toPx(),
                                            )
                                        },
                                )
                            }
                        }
                    }
                }

                // 课程卡片层：逐天取出本周 + （可选）非本周的课，按重叠关系分泳道后绝对定位。
                for (col in dayOrder.indices) {
                    val day = dayOrder[col]
                    val dayCourses = courses.filter { it.day == day }
                    val active = dayCourses.filter { it.activeIn(week) }
                    val muted = if (showOtherWeeks) dayCourses.filterNot { it.activeIn(week) } else emptyList()
                    val cells = active.map { it to true } + muted.map { it to false }
                    for (item in layoutDayCells(cells)) {
                        val span = (item.course.endPeriod - item.course.startPeriod + 1).coerceAtLeast(1)
                        val laneWidth = dayWidth / item.laneCount
                        CourseCard(
                            course = item.course,
                            active = item.active,
                            dark = dark,
                            palette = palette,
                            cardAlpha = cardAlpha,
                            courseNames = courseNames,
                            onClick = { onCourseClick(item.course) },
                            modifier = Modifier
                                .offset(
                                    x = SideColumnWidth + dayWidth * col + laneWidth * item.lane,
                                    y = PeriodRowHeight * (item.course.startPeriod - 1),
                                )
                                .width(laneWidth)
                                .height(PeriodRowHeight * span)
                                .padding(2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PeriodLabelCell(period: Int, periodTimes: List<PeriodTime>) {
    val pt = periodTimes.firstOrNull { it.index == period }
    Column(
        modifier = Modifier.width(SideColumnWidth).height(PeriodRowHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "${period}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (pt != null) {
            Spacer(Modifier.height(3.dp))
            Text(pt.start, fontSize = 8.sp, lineHeight = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pt.end, fontSize = 8.sp, lineHeight = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 一门课在网格里排布后的结果：属于哪条泳道、这一组重叠课程一共几条泳道。 */
private data class LaidCourse(val course: Course, val active: Boolean, val lane: Int, val laneCount: Int)

/**
 * 把同一天里的课程（含非本周的灰卡）按周期重叠关系分组、分泳道：
 * 互不重叠的课程各占整列；有重叠的（哪怕是 3 门课挤在完全相同的节次）并排收窄成 laneCount 条等宽泳道，
 * 谁都不会盖住谁。传入的 cells 不要求预先排序——本周课在起始节次相同时排在非本周课前面，
 * 从而拿到更靠左的泳道，对应任务里「本周课优先占位」的要求。
 */
private fun layoutDayCells(cells: List<Pair<Course, Boolean>>): List<LaidCourse> {
    if (cells.isEmpty()) return emptyList()
    val sorted = cells.sortedWith(compareBy({ it.first.startPeriod }, { if (it.second) 0 else 1 }))
    val result = mutableListOf<LaidCourse>()
    var i = 0
    while (i < sorted.size) {
        var groupEnd = sorted[i].first.endPeriod
        var j = i + 1
        while (j < sorted.size && sorted[j].first.startPeriod <= groupEnd) {
            groupEnd = maxOf(groupEnd, sorted[j].first.endPeriod)
            j++
        }
        // 组内做区间划分：每条泳道记录自己当前占用到第几节，能塞下就塞，塞不下开新泳道。
        val laneEnds = mutableListOf<Int>()
        val lanes = mutableListOf<Int>()
        for (k in i until j) {
            val course = sorted[k].first
            val existingLane = laneEnds.indexOfFirst { it < course.startPeriod }
            if (existingLane == -1) {
                laneEnds += course.endPeriod
                lanes += laneEnds.lastIndex
            } else {
                laneEnds[existingLane] = course.endPeriod
                lanes += existingLane
            }
        }
        val laneCount = laneEnds.size
        for (k in i until j) {
            val (course, active) = sorted[k]
            result += LaidCourse(course, active, lanes[k - i], laneCount)
        }
        i = j
    }
    return result
}

/**
 * 课程卡片：顶部一条细色带（本周课）或「非本周」灰色角标（非本周课），
 * 下面是课名 + 学时标记（自动折行、超出截断），底部是教室行。
 * 外层 clip 到圆角形状，即使文字算多了溢出卡片高度也只会被裁掉，不会画出到相邻卡片上。
 *
 * 教室是每天都要看的信息，所以在版面上优先级最高：标记跟课名走（课名可多行，挤得起），
 * 教室独占底部一行并先拿到自己的高度（见 [RoomLine] 与课名的 `weight(fill = false)`）。
 */
@Composable
private fun CourseCard(
    course: Course,
    active: Boolean,
    dark: Boolean,
    palette: CoursePalette,
    cardAlpha: Float,
    courseNames: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = if (active) courseColors(course.name, palette, cardAlpha, courseNames) else mutedCourseColors(dark, cardAlpha)
    val span = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick),
    ) {
        if (active) {
            Box(
                Modifier.fillMaxWidth().height(5.dp).background(
                    Brush.horizontalGradient(listOf(fg.copy(alpha = 0.45f), fg.copy(alpha = 0.12f)))
                )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "非本周",
                    fontSize = 9.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.background,
                )
            }
        }
        CardText(
            name = course.displayName,
            room = course.room,
            color = fg,
            nameMaxLines = (span * 3).coerceIn(3, 9),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 2.dp, vertical = 5.dp),
        )
    }
}

/** 教室行的候选字号，从大到小试排。 */
private val RoomSizeSteps = listOf(9.sp, 8.5.sp, 8.sp, 7.5.sp, 7.sp)

/** 教室行最多铺几行；再多整张卡片就成一堵字墙了。 */
private const val RoomMaxLines = 4

/** 行高相对字号的倍率。试排和真正渲染必须用同一个值，否则量准了也会画溢出。 */
private const val RoomLineHeightRatio = 1.28f

/** 课名字号与行高，[CardText] 量高度时也要按它给课名留一行。 */
private val NameFontSize = 12.sp
private val NameLineHeight = 15.sp

/** 教室行的排版方案：最终显示成什么字串、多大字号、最多几行。 */
private data class RoomPlan(val text: String, val size: TextUnit, val maxLines: Int)

/**
 * 卡片的文字块：课名（含学时标记）在上，教室在下。
 *
 * 教室是每天真正要看的信息——课名忘了能猜，教室号猜不出来——所以版面上它优先级最高：
 * 先按真实格宽算出教室的排法（[planRoom]），课名再用 `weight(fill = false)` 吃剩下的高度。
 * 反过来（课名先量、教室捡漏）就是老版本把教室整行顶出卡片的原因。
 */
@Composable
private fun CardText(
    name: String,
    room: String,
    color: Color,
    nameMaxLines: Int,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val heightPx = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val spacerPx = with(density) { 2.dp.roundToPx() }
        // 给课名留一行，剩下的高度全部可以给教室。
        val nameLinePx = with(density) { NameLineHeight.toPx() }.toInt()

        val plan = remember(room, widthPx, heightPx, density.density, density.fontScale) {
            if (room.isBlank()) {
                null
            } else {
                planRoom(
                    measurer = measurer,
                    room = room,
                    widthPx = widthPx,
                    heightBudgetPx = (heightPx - spacerPx - nameLinePx).coerceAtLeast(0),
                    density = density,
                )
            }
        }

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(
                // 学时标记跟课名走：课名本来就能折行，多一个 ★ 挤得起；
                // 放在教室行则要白白吃掉一个全角字宽，窄屏上正是它把教室挤成 "XX-…" 的。
                text = name,
                fontSize = NameFontSize,
                lineHeight = NameLineHeight,
                fontWeight = FontWeight.Medium,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = nameMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            )
            if (plan != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = plan.text,
                    fontSize = plan.size,
                    lineHeight = plan.size * RoomLineHeightRatio,
                    color = color.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = plan.maxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 给教室行挑一个「一定落得了地」的排法。
 *
 * 一格的可用宽度只有 30–40dp（屏越窄越少），`sp` 还会跟着系统字体大小放大，
 * 所以不存在一个写死的字号能通吃所有机型——只能拿真实宽度去量。分两轮：
 *
 * 1. 字号从大到小试，能把**整串**教室放下就放整串；行数按剩余高度算，最多 [RoomMaxLines] 行。
 * 2. 整串怎么都放不下时（超长中文场地名 + 窄屏 + 大字体），**保尾不保头**：
 *    取能放下的最长后缀，前面补省略号。教室的区分度在尾部——同一栋楼的房间共享 "XX-" 前缀，
 *    截成 "XX-…" 等于什么都没说，截成 "…427" 还能照着找到教室。
 *    后缀退到 1 个字符总能放下，所以这一轮一定有结果。
 */
private fun planRoom(
    measurer: TextMeasurer,
    room: String,
    widthPx: Int,
    heightBudgetPx: Int,
    density: Density,
): RoomPlan {
    fun lineBudget(size: TextUnit): Int {
        val lineHeightPx = with(density) { (size * RoomLineHeightRatio).toPx() }
        if (lineHeightPx <= 0f) return 1
        return (heightBudgetPx / lineHeightPx).toInt().coerceIn(1, RoomMaxLines)
    }

    fun fits(text: String, size: TextUnit, maxLines: Int): Boolean = !measurer.measure(
        text = text,
        style = TextStyle(fontSize = size, lineHeight = size * RoomLineHeightRatio),
        maxLines = maxLines,
        constraints = Constraints(maxWidth = widthPx),
    ).hasVisualOverflow

    for (size in RoomSizeSteps) {
        val lines = lineBudget(size)
        if (fits(room, size, lines)) return RoomPlan(room, size, lines)
    }

    val size = RoomSizeSteps.last()
    val lines = lineBudget(size)
    for (keep in room.length - 1 downTo 1) {
        val candidate = "…" + room.takeLast(keep)
        if (fits(candidate, size, lines)) return RoomPlan(candidate, size, lines)
    }
    // 宽度小到连一个字都摆不下（理论兜底）：交给 Text 自己截，至少不会崩。
    return RoomPlan(room, size, lines)
}
