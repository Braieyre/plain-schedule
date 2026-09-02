package com.qingke.schedule.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.qingke.schedule.model.Settings

/** 一套课程卡配色：底色与对应文字色一一配对。 */
data class CoursePalette(
    val id: String,
    val label: String,
    val card: List<Color>,
    val text: List<Color>,
)

/** 粉彩（对照参考截图的观感），默认。 */
private val Pastel = CoursePalette(
    id = "pastel", label = "粉彩",
    card = listOf(
        Color(0xFFE8F0FC), Color(0xFFFDECF0), Color(0xFFE9F6E7), Color(0xFFFDF4E0),
        Color(0xFFEFEBFB), Color(0xFFE4F5F0), Color(0xFFFCEDE4), Color(0xFFDFF1F6),
        Color(0xFFF7E9F5), Color(0xFFEDF6DF), Color(0xFFFBE7E7), Color(0xFFE6ECF7),
    ),
    text = listOf(
        Color(0xFF5B7CB8), Color(0xFFD4738C), Color(0xFF62A05A), Color(0xFFD9A441),
        Color(0xFF7B6BC4), Color(0xFF4FA894), Color(0xFFD08A5E), Color(0xFF3F8FA6),
        Color(0xFFB265A8), Color(0xFF7D9B3E), Color(0xFFC96A6A), Color(0xFF6B7FA8),
    ),
)

private val Ink = CoursePalette(
    id = "ink", label = "水墨",
    card = listOf(
        Color(0xFFECEFF1), Color(0xFFEDEAE6), Color(0xFFE7EDF0), Color(0xFFF1EFEC),
        Color(0xFFE4E8EB), Color(0xFFEFEDE8), Color(0xFFE9EEF0), Color(0xFFF0F2F3),
        Color(0xFFE8E6E2), Color(0xFFE6ECEF), Color(0xFFF2F0ED), Color(0xFFE2E7EA),
    ),
    text = listOf(
        Color(0xFF41525C), Color(0xFF6B6257), Color(0xFF4C6270), Color(0xFF6E665C),
        Color(0xFF5A6B75), Color(0xFF7A6F5F), Color(0xFF47585F), Color(0xFF3E4E57),
        Color(0xFF5E564B), Color(0xFF44555F), Color(0xFF75695A), Color(0xFF52636D),
    ),
)

private val Candy = CoursePalette(
    id = "candy", label = "糖果",
    card = listOf(
        Color(0xFFFFE3E8), Color(0xFFE3F1FF), Color(0xFFFFF3D6), Color(0xFFE6FBEA),
        Color(0xFFF3E6FF), Color(0xFFFFE9DC), Color(0xFFDDF5F7), Color(0xFFFDE6F4),
        Color(0xFFEFF7DC), Color(0xFFE7E6FB), Color(0xFFFFEFE0), Color(0xFFD9F2EC),
    ),
    text = listOf(
        Color(0xFFCE5C77), Color(0xFF3F7FBF), Color(0xFFC28A22), Color(0xFF3F9A5C),
        Color(0xFF8558BE), Color(0xFFC96D3E), Color(0xFF2F8E99), Color(0xFFBE548F),
        Color(0xFF7C9433), Color(0xFF5F5FBF), Color(0xFFC57A45), Color(0xFF2E9184),
    ),
)

val PALETTES: List<CoursePalette> = listOf(Pastel, Ink, Candy)

fun paletteOf(id: String): CoursePalette = PALETTES.firstOrNull { it.id == id } ?: Pastel

/**
 * 按课名取色的序号 —— 同一门课在任何一周、任何页面颜色都一致。
 *
 * 刻意**不用哈希**：实测在真实课表上，哈希把 9 门课挤进了 4 种颜色（另外 4 种一次都没出现），
 * 导致同列相邻的两门课撞成同色。加大调色板治不了这个病（哈希+16 色反而比 +12 色更差）。
 * 改成「课名在全表去重排序后的名单里的序号」后，N ≤ 调色板大小时**保证互不同色**。
 *
 * [names] 是全表去重排序过的课名单（`AppState.courseNames`）。传空或课名不在其中时，
 * 退化成按课名自身长度与首字符算一个稳定序号——只用于「示例课程」这类脱离课表的预览调用。
 */
fun colorIndexOf(name: String, size: Int, names: List<String> = emptyList()): Int {
    if (size <= 0) return 0
    val i = names.indexOf(name)
    if (i >= 0) return i % size
    return ((name.length * 31 + (name.firstOrNull()?.code ?: 0)) and 0x7FFFFFFF) % size
}

/** 返回 (卡片底色, 文字色)。[alpha] 来自设置里的卡片不透明度，[names] 见 [colorIndexOf]。 */
fun courseColors(
    name: String,
    palette: CoursePalette,
    alpha: Float = 1f,
    names: List<String> = emptyList(),
): Pair<Color, Color> {
    val i = colorIndexOf(name, palette.card.size, names)
    return palette.card[i].copy(alpha = alpha) to palette.text[i]
}

/** 「非本周」灰卡（截图 05）。 */
fun mutedCourseColors(dark: Boolean, alpha: Float = 1f): Pair<Color, Color> =
    if (dark) Color(0xFF2E3134).copy(alpha = alpha) to Color(0xFF9AA0A6)
    else Color(0xFFEDEFF1).copy(alpha = alpha) to Color(0xFF9AA0A6)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4A6FA5),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1D1F),
    onSurface = Color(0xFF1C1D1F),
    surfaceVariant = Color(0xFFF4F5F7),
    onSurfaceVariant = Color(0xFF6B7075),
    error = Color(0xFFE2703A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9DB8DF),
    background = Color(0xFF16181A),
    surface = Color(0xFF1D2022),
    onBackground = Color(0xFFE4E6E8),
    onSurface = Color(0xFFE4E6E8),
    surfaceVariant = Color(0xFF26292C),
    onSurfaceVariant = Color(0xFF9AA0A6),
    error = Color(0xFFE2703A),
)

/** 「切回本周」按钮与非本周周数的强调色（截图 02 的橙）。 */
val AccentOrange = Color(0xFFE2703A)

@Composable
fun isAppInDarkTheme(settings: Settings): Boolean = when (settings.darkMode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun AppTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(settings)
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}

/** 全局状态注入点。MainActivity 负责 provide。 */
val LocalAppState = compositionLocalOf<com.qingke.schedule.data.AppState> {
    error("AppState 未注入")
}
