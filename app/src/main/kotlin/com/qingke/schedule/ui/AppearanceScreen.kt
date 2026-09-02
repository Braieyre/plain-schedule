@file:OptIn(ExperimentalMaterial3Api::class)

package com.qingke.schedule.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * T6b 实现：个性换肤页（抽屉「个性换肤」入口指向此页）。
 * 自上而下四个分组：配色 / 深浅色 / 背景图 / 可读性（仅在已设置背景图时出现，
 * 因为卡片不透明度和背景蒙层这两个滑块脱离了背景图就没有意义）。
 * 全部写入都经 state.updateSettings，本文件不直接碰 Store。
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val state = LocalAppState.current
    val settings = state.settings
    val background = state.background

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 用当前屏幕的像素尺寸作为背景图降采样目标——不需要保留比屏幕还大的分辨率，
    // 那只会白白多占内存和磁盘（真正的降采样逻辑在 Store.saveBackground 里）。
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val widthPx = (configuration.screenWidthDp * density.density).roundToInt().coerceAtLeast(1)
            val heightPx = (configuration.screenHeightDp * density.density).roundToInt().coerceAtLeast(1)
            if (!state.setBackground(uri, widthPx, heightPx)) {
                scope.launch { snackbarHostState.showSnackbar("背景图设置失败，请换一张图片重试") }
            }
        }
        // uri == null 代表用户取消了选择，不是失败，什么都不用做。
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            // 系统栏内边距已经由外层 Box 统一处理，这里清零避免重复叠加。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text("个性换肤") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
            ) {
                PaletteSection(
                    selectedId = settings.themeId,
                    onSelect = { id -> state.updateSettings { it.copy(themeId = id) } },
                )
                DarkModeSection(
                    selected = settings.darkMode,
                    onSelect = { mode -> state.updateSettings { it.copy(darkMode = mode) } },
                )
                BackgroundSection(
                    background = background,
                    onPick = {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onClear = { state.clearBackground() },
                )
                // 可读性调节脱离背景图没有意义，只在真有背景图时才出现——这是必须的
                // 显隐逻辑而不是装饰，所以严格按 settings.hasBackground 判断。
                if (settings.hasBackground) {
                    ReadabilitySection(
                        background = background,
                        themeId = settings.themeId,
                        initialCardAlpha = settings.cardAlpha,
                        initialBgDim = settings.bgDim,
                        onCardAlphaChange = { v -> state.updateSettings { it.copy(cardAlpha = v) } },
                        onBgDimChange = { v -> state.updateSettings { it.copy(bgDim = v) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** 配色分组：横向排列三套预设，每套用 2x2 色块预览（取 palette.card 前 4 个）。 */
@Composable
private fun PaletteSection(selectedId: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("配色")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PALETTES.forEach { palette ->
                PaletteOption(
                    palette = palette,
                    selected = palette.id == selectedId,
                    onClick = { onSelect(palette.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PaletteOption(
    palette: CoursePalette,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 2x2 摆放而不是一整排，避免在窄屏上和另外两套色卡挤在一起时被裁切。
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in palette.card.take(4).chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(swatch),
                        )
                    }
                }
            }
        }
        Text(
            palette.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 深浅色：跟随系统 / 浅色 / 深色 三选一。 */
private val DARK_MODE_OPTIONS = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")

@Composable
private fun DarkModeSection(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("深浅色")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DARK_MODE_OPTIONS.forEach { (value, label) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

/**
 * 背景图分组：没有背景图时给一个入口按钮；已有背景图时换成缩略预览 +「更换/移除」。
 * 用实际拿到的 Bitmap 是否为空来判断要显示哪个分支，而不是 settings.hasBackground——
 * 万一背景文件损坏导致解码失败，这样能优雅退回选图按钮，不会显示一块空白缩略图。
 */
@Composable
private fun BackgroundSection(background: Bitmap?, onPick: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("背景图")
        if (background != null) {
            Image(
                bitmap = background.asImageBitmap(),
                contentDescription = "当前背景图预览",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) { Text("更换背景") }
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("移除背景") }
            }
        } else {
            Text(
                "设置一张背景图，课表会以半透明卡片叠在上面显示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("选择背景图") }
        }
    }
}

/**
 * 可读性分组：卡片不透明度 + 背景蒙层两个滑块，仅在设了背景图时出现。
 * 拖动中的实时值放本地 state，只在松手（onValueChangeFinished）才落盘——
 * 预览要跟手，但没必要每一帧都触发一次同步文件写入，拖动久了会卡。
 */
@Composable
private fun ReadabilitySection(
    background: Bitmap?,
    themeId: String,
    initialCardAlpha: Float,
    initialBgDim: Float,
    onCardAlphaChange: (Float) -> Unit,
    onBgDimChange: (Float) -> Unit,
) {
    var cardAlpha by remember { mutableStateOf(initialCardAlpha) }
    var bgDim by remember { mutableStateOf(initialBgDim) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("可读性")
        ReadabilityPreview(
            background = background,
            palette = paletteOf(themeId),
            cardAlpha = cardAlpha,
            bgDim = bgDim,
        )
        LabeledSlider(
            label = "卡片不透明度",
            value = cardAlpha,
            valueRange = 0.3f..1.0f,
            onValueChange = { cardAlpha = it },
            onValueChangeFinished = { onCardAlphaChange(cardAlpha) },
        )
        LabeledSlider(
            label = "背景蒙层",
            value = bgDim,
            valueRange = 0f..0.8f,
            onValueChange = { bgDim = it },
            onValueChangeFinished = { onBgDimChange(bgDim) },
        )
    }
}

/** 实时预览：背景图 + 黑色蒙层 + 一张示例课程卡，让用户直接看到文字还清不清楚。 */
@Composable
private fun ReadabilityPreview(background: Bitmap?, palette: CoursePalette, cardAlpha: Float, bgDim: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (background != null) {
            Image(
                bitmap = background.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 正常不会走到这里（本分组只在 hasBackground 为真时渲染）；万一背景解码
            // 失败，退化成纯色底，蒙层和示例卡依然能正常演示，不留空白也不崩溃。
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = bgDim)))
        val (cardColor, textColor) = courseColors("示例课程", palette, cardAlpha)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(cardColor)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text("示例课程", color = textColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
        )
    }
}
