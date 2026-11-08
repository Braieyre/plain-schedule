package com.qingke.schedule.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.qingke.schedule.model.Span
import java.io.File
import kotlin.math.abs

/** 保留原始坐标、周次和节次，仅以本机 OCR 修复文字层无法解码的单字。 */
object PdfGlyphRecovery {
    fun recover(context: Context, bytes: ByteArray, spans: List<Span>): List<Span> {
        if (spans.none { '\u0000' in it.text || '�' in it.text }) return spans
        val file = File.createTempFile("pdf-glyph-", ".pdf", context.cacheDir)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val votes = mutableMapOf<String, MutableMap<String, Int>>()
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for ((pageNumber, glyphs) in spans.filter { it.text == "\u0000" || it.text == "�" }.groupBy { it.page }) {
                        if (pageNumber !in 1..renderer.pageCount) continue
                        renderer.openPage(pageNumber - 1).use { page ->
                            val scale = minOf(3f, 3000f / maxOf(page.width, page.height))
                            val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                                val symbols = text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.flatMap { it.symbols }
                                for (glyph in glyphs) {
                                    val x = glyph.x + glyph.width / 2
                                    val y = page.height - glyph.y - glyph.size * 0.3f
                                    val candidates = symbols.filter { symbol ->
                                        val box = symbol.boundingBox
                                        box != null && symbol.text.length in 1..3 && symbol.confidence >= 0.3f &&
                                            (symbol.text[0] in "()（）:：;；,.，。、[]" || symbol.text[0] in 'A'..'Z' || symbol.text[0] in 'a'..'z') &&
                                            box.exactCenterX() / scale >= glyph.x - 0.3f &&
                                            box.exactCenterX() / scale <= glyph.x + glyph.width + 0.3f &&
                                            abs(box.exactCenterY() / scale - y) < glyph.size * 0.55f
                                    }
                                    val recognized = candidates.sortedBy { it.boundingBox!!.left }.joinToString("") { it.text }
                                    val wideSingleLetter = recognized.length == 1 && recognized[0].isLetter() && glyph.width > glyph.size * 0.65f
                                    if (recognized.isNotEmpty() && recognized.length <= 3 && !wideSingleLetter && glyph.glyphKey != null) {
                                        val tally = votes.getOrPut(glyph.glyphKey!!) { mutableMapOf() }
                                        tally[recognized] = (tally[recognized] ?: 0) + 1
                                    }
                                }
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
        } finally {
            recognizer.close()
            file.delete()
        }
        val replacements = votes.mapNotNull { (key, tally) ->
            val best = tally.maxByOrNull { it.value }!!
            if (best.value >= 3 && best.value.toFloat() / tally.values.sum() >= 0.85f) key to best.key else null
        }.toMap()
        return spans.map { glyph -> if (glyph.text == "\u0000" || glyph.text == "�") {
            replacements[glyph.glyphKey]?.let { glyph.copy(text = it) } ?: glyph
        } else glyph }
    }
}
