package com.qingke.schedule

import com.qingke.schedule.model.PdfUnsupportedException
import com.qingke.schedule.pdf.PdfText
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 验收测试。基准文件 sample-schedule.pdf（iText 5.5.10 生成，单页 /Rotate 90，
 * 字体 /UniGB-UCS2-H）+ 参考真值 sample-spans.tsv（103 条，原始未变换 Tm 坐标，
 * 只用来核对文本内容与相对布局，不直接比绝对坐标——PLAN.md 已说明原因）。
 */
class PdfTextTest {

    private fun loadSampleBytes(): ByteArray {
        val stream = javaClass.getResourceAsStream("/sample-schedule.pdf")
            ?: error("测试资源 sample-schedule.pdf 未找到")
        return stream.use { it.readBytes() }
    }

    // ---------- 基准 PDF：必须逐条满足的断言 ----------

    @Test
    fun `恰好返回103条非空span`() {
        val spans = PdfText.extract(loadSampleBytes())
        assertEquals(103, spans.size)
        assertTrue("不应含空白 span", spans.all { it.text.isNotBlank() })
        assertTrue("单页样本，所有 span 的 page 应一致", spans.all { it.page == spans.first().page })
    }

    @Test
    fun `恰好一条学年学期标题`() {
        val spans = PdfText.extract(loadSampleBytes())
        val matches = spans.filter { it.text == "2026-2027学年第1学期" }
        assertEquals("应恰好一条「2026-2027学年第1学期」", 1, matches.size)
    }

    @Test
    fun `七个表头y相同x严格递增且间距约103_8`() {
        val spans = PdfText.extract(loadSampleBytes())
        val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val headers = weekdays.map { name ->
            val found = spans.filter { it.text == name }
            assertEquals("表头「$name」应恰好出现一次，实际 $found", 1, found.size)
            found.single()
        }

        val yBase = headers.first().y
        for (h in headers) {
            assertTrue(
                "七个表头 y 应基本相同（容差 0.5），实际 ${headers.map { it.y }}",
                abs(h.y - yBase) <= 0.5f,
            )
        }

        val xs = headers.map { it.x }
        for (k in 0 until xs.size - 1) {
            assertTrue("表头 x 应严格递增，实际 $xs", xs[k] < xs[k + 1])
        }
        for (k in 0 until xs.size - 1) {
            val gap = xs[k + 1] - xs[k]
            assertTrue("相邻表头 x 间距应约 103.8（容差 1.0），实际 $gap", abs(gap - 103.8f) <= 1.0f)
        }
    }

    @Test
    fun `含节次周次场地说明的span`() {
        val spans = PdfText.extract(loadSampleBytes())
        assertTrue(
            "应含 (1-2节)1-6周,8-13周/场地 这条 span",
            spans.any { it.text == "(1-2节)1-6周,8-13周/场地" },
        )
    }

    @Test
    fun `思政课程名跨行为两条独立span且第二行y更小`() {
        val spans = PdfText.extract(loadSampleBytes())
        val firstLines = spans.filter { it.text == "习近平新时代中国特色" }
        val secondLines = spans.filter { it.text == "社会主义思想概论★" }
        assertTrue("应含「习近平新时代中国特色」span", firstLines.isNotEmpty())
        assertTrue("应含「社会主义思想概论★」span", secondLines.isNotEmpty())

        // 样本里这两行文字在多个单元格各出现一次，按同一列的 x 坐标配对，
        // 逐对校验换行后的第二行 y 严格小于第一行。
        for (second in secondLines) {
            val first = firstLines.firstOrNull { abs(it.x - second.x) < 0.5f }
            assertNotNull("应能找到与 $second 同列（同一单元格换行）的上一行", first)
            assertTrue(
                "换行后的第二行 y 应小于第一行：first=$first second=$second",
                second.y < first!!.y,
            )
        }
    }

    @Test
    fun `随机字节抛出PdfUnsupportedException`() {
        val random = ByteArray(256) { it.toByte() }
        assertThrows(PdfUnsupportedException::class.java) {
            PdfText.extract(random)
        }
    }

    // ---------- 边界情况：基准样本覆盖不到，自行构造最小 PDF 验证 ----------

    @Test
    fun `空字节数组抛出PdfUnsupportedException`() {
        assertThrows(PdfUnsupportedException::class.java) {
            PdfText.extract(ByteArray(0))
        }
    }

    @Test
    fun `结构完整但内容流为空时抛出PdfUnsupportedException`() {
        val fontObj = "<</Type/Font/Subtype/Type0/BaseFont/Test/Encoding/UniGB-UCS2-H>>"
        val pdf = buildMinimalPdf(fontObjBody = fontObj, content = "")
        assertThrows(PdfUnsupportedException::class.java) {
            PdfText.extract(pdf)
        }
    }

    /**
     * 基准样本唯一的页是 /Rotate 90，为验证「归一」不是碰巧对了，
     * 这里单独构造一个 /Rotate 0 的合成 PDF：CTM 恒等、不旋转时，
     * 坐标应原样等于 Tm 的平移分量。
     */
    @Test
    fun `Rotate为0时坐标不做归一变换`() {
        val fontObj = "<</Type/Font/Subtype/Type0/BaseFont/Test/Encoding/UniGB-UCS2-H>>"
        val text = "你好"
        val hex = text.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val content = "BT\n1 0 0 1 12 34 Tm\n/F1 10 Tf\n<$hex>Tj\nET"
        val pdf = buildMinimalPdf(rotate = 0, fontObjBody = fontObj, content = content)

        val spans = PdfText.extract(pdf)
        assertEquals(1, spans.size)
        val span = spans.single()
        assertEquals("你好", span.text)
        assertEquals(12f, span.x, 0.01f)
        assertEquals(34f, span.y, 0.01f)
        assertEquals(10f, span.size, 0.01f)
    }

    /**
     * 样本 PDF 用的是 Encoding 名直接含 UCS2 这条最简路径，没有覆盖 /ToUnicode
     * 这条——但 PLAN 明确要求必须实现（兼容其它学校教务系统的唯一途径），
     * 这里单独构造一份不含 UCS2/Identity、但带 /ToUnicode 流的合成 PDF 来验证：
     * beginbfchar 单字符映射 + beginbfrange 区间映射都要能正确解出来。
     */
    @Test
    fun `ToUnicode流的bfchar与bfrange都能正确解码`() {
        val fontObj = "<</Type/Font/Subtype/Type1/BaseFont/Test/Encoding/WinAnsiEncoding/ToUnicode 6 0 R>>"
        val cmap = listOf(
            "/CIDInit /ProcSet findresource begin",
            "12 dict begin",
            "begincmap",
            "1 begincodespacerange",
            "<00> <ff>",
            "endcodespacerange",
            "1 beginbfchar",
            "<01> <0041>",
            "endbfchar",
            "1 beginbfrange",
            "<02> <04> <0042>",
            "endbfrange",
            "endcmap",
            "end",
            "end",
        ).joinToString("\n")
        val toUnicodeObj = "<</Length ${cmap.toByteArray(Charsets.ISO_8859_1).size}>>\nstream\n$cmap\nendstream"
        val content = "BT\n1 0 0 1 5 5 Tm\n/F1 12 Tf\n<01020304>Tj\nET"
        val pdf = buildMinimalPdf(
            fontObjBody = fontObj,
            extraObjects = mapOf(6 to toUnicodeObj),
            content = content,
        )

        val spans = PdfText.extract(pdf)
        assertEquals(1, spans.size)
        // 0x01 -> bfchar -> 'A'；0x02/0x03/0x04 -> bfrange -> 'B'/'C'/'D'。
        assertEquals("ABCD", spans.single().text)
    }

    /**
     * 加密 PDF 测试：trailer 中含有 /Encrypt 键时应立即抛异常，不尝试解析密文内容。
     */
    @Test
    fun `trailer含Encrypt键的PDF抛加密异常`() {
        val fontObj = "<</Type/Font/Subtype/Type0/BaseFont/Test/Encoding/UniGB-UCS2-H>>"
        val content = "BT\n1 0 0 1 5 5 Tm\n/F1 10 Tf\n<0001>Tj\nET"
        val pdf = buildMinimalPdf(
            fontObjBody = fontObj,
            content = content,
            trailerExtra = "/Encrypt 5 0 R",
        )
        val ex = assertThrows(PdfUnsupportedException::class.java) {
            PdfText.extract(pdf)
        }
        assertTrue("异常消息应含「加密」", ex.message?.contains("加密") ?: false)
    }

    /**
     * 反向测试：内容流里含有"加密"字样但 trailer 无 /Encrypt 键时不应被误判为加密文件。
     * 应能正常解析文本内容。
     */
    @Test
    fun `内容含加密二字但trailer无Encrypt键不误判`() {
        val fontObj = "<</Type/Font/Subtype/Type0/BaseFont/Test/Encoding/UniGB-UCS2-H>>"
        val text = "加密"
        val hex = text.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val content = "BT\n1 0 0 1 5 5 Tm\n/F1 10 Tf\n<$hex>Tj\nET"
        val pdf = buildMinimalPdf(fontObjBody = fontObj, content = content)
        val spans = PdfText.extract(pdf)
        assertEquals(1, spans.size)
        assertEquals("加密", spans.single().text)
    }

    /**
     * 拼一份最小可用的合成 PDF：固定 Catalog(1)/Pages(2)/Page(3)/Contents(5) 骨架，
     * 调用方提供 4 号字体对象体、可选附加对象（如 6 号 ToUnicode 流）与内容流正文。
     * 刻意不写正确的 xref 偏移量/trailer 完整性——PdfText 是线性对象扫描，不依赖它们，
     * 这也顺带验证了实现真的没有偷偷依赖 xref。
     */
    private fun buildMinimalPdf(
        rotate: Int = 0,
        mediaW: Int = 200,
        mediaH: Int = 100,
        fontObjBody: String,
        extraObjects: Map<Int, String> = emptyMap(),
        content: String,
        trailerExtra: String = "",
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        sb.append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n")
        sb.append("2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj\n")
        sb.append(
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 $mediaW $mediaH]" +
                "/Rotate $rotate/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>endobj\n",
        )
        sb.append("4 0 obj$fontObjBody\nendobj\n")
        for ((num, body) in extraObjects) sb.append("$num 0 obj$body\nendobj\n")
        val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
        sb.append("5 0 obj<</Length ${contentBytes.size}>>\nstream\n")
        sb.append(content)
        sb.append("\nendstream\nendobj\n")
        sb.append("trailer<</Root 1 0 R$trailerExtra>>\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
