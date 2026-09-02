package com.qingke.schedule.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.qingke.schedule.model.ScheduleData
import com.qingke.schedule.model.Settings
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 全部持久化：两个 JSON 小文件 + 一张背景图。
 * 刻意不用 Room —— 数据量只有几 KB，同步读写即可，省掉注解处理器与数据库初始化开销。
 */
class Store(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private val scheduleFile get() = File(ctx.filesDir, "schedule.json")
    private val settingsFile get() = File(ctx.filesDir, "settings.json")
    private val bgFile get() = File(ctx.filesDir, "bg.jpg")

    fun loadSchedule(): ScheduleData? = runCatching {
        if (!scheduleFile.exists()) null
        else json.decodeFromString<ScheduleData>(scheduleFile.readText())
    }.getOrNull()

    fun saveSchedule(data: ScheduleData?) {
        if (data == null) scheduleFile.delete()
        else scheduleFile.writeText(json.encodeToString(ScheduleData.serializer(), data))
    }

    fun loadSettings(): Settings = runCatching {
        if (!settingsFile.exists()) Settings()
        else json.decodeFromString<Settings>(settingsFile.readText())
    }.getOrElse { Settings() }

    fun saveSettings(s: Settings) {
        settingsFile.writeText(json.encodeToString(Settings.serializer(), s))
    }

    /**
     * 把相册选中的图降采样后存进私有目录。
     * 降采样是必须的：直接存原图会让一张 4000px 的照片每次解码吃掉几十 MB。
     */
    fun saveBackground(uri: Uri, reqWidth: Int, reqHeight: Int): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth && bounds.outHeight / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return false
        bgFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        true
    }.getOrElse { false }

    fun loadBackground(): Bitmap? =
        if (bgFile.exists()) runCatching { BitmapFactory.decodeFile(bgFile.path) }.getOrNull() else null

    fun clearBackground() {
        bgFile.delete()
    }
}
