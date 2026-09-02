package com.qingke.schedule.pdf

import com.qingke.schedule.model.PdfUnsupportedException
import com.qingke.schedule.model.Span
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * T1：零依赖 PDF 文字提取器，只用 JDK 标准库。
 *
 * 核心策略是**线性对象扫描**而非解析 xref 表：正则找出所有 `N G obj ... endobj`，
 * 建立 objNum → body 的映射（增量更新时同一 objNum 出现多次，后出现的覆盖前面的，
 * 天然符合增量更新的覆盖语义）。这样即使 xref 损坏/缺失也能正常工作。
 *
 * 已知的刻意简化（样本不需要，其它教务系统 PDF 若命中会退化但不会崩）：
 * - 不做基于字形宽度的 Tj 后光标推进（样本每个折行都有独立 Tm，用不到）；
 * - TJ 数组里的多个串按顺序拼接成一个 span 输出，字距数字整体忽略；
 * - 不支持内联图像 `BI...ID...EI`、对象流 `/Type/ObjStm`、加密文档。
 */
object PdfText {

    /** 抽取全部文字及视图坐标（已按 CTM 与页面 /Rotate 归一）。无法解析时抛 [PdfUnsupportedException]。 */
    fun extract(bytes: ByteArray): List<Span> {
        val collected = ArrayList<Span>()
        try {
            val doc = String(bytes, Charsets.ISO_8859_1)

            // 检测加密：在解析对象之前先检查 trailer 中是否有 /Encrypt 字典
            checkEncryption(doc)

            val objects = scanObjects(doc)
            if (objects.isEmpty()) {
                throw PdfUnsupportedException("未找到任何 PDF 对象，可能不是有效的 PDF 文件")
            }
            val pageObjNums = resolvePageOrder(objects)
            if (pageObjNums.isEmpty()) {
                throw PdfUnsupportedException("未找到任何页面（既无 Catalog/Pages 树，也无独立 /Type/Page 对象）")
            }
            for ((idx, pageNum) in pageObjNums.withIndex()) {
                try {
                    collected += extractPageSpans(objects, pageNum, idx + 1)
                } catch (e: Exception) {
                    // 单页解析失败不影响其它页：继续处理剩余页面，最终是否算「解析失败」
                    // 由下面 collected 是否为空统一判定。
                }
            }
        } catch (e: PdfUnsupportedException) {
            throw e
        } catch (e: Exception) {
            val raw = collected.joinToString("\n") { it.text }
            throw PdfUnsupportedException("PDF 解析异常：${e.message}", raw)
        }
        if (collected.isEmpty()) {
            throw PdfUnsupportedException("未能从 PDF 中提取到任何文字")
        }
        return collected
    }
}

// ============================================================
// 对象扫描：正则线性扫描 N G obj ... endobj，不解析 xref
// ============================================================

private val OBJ_RE = Regex("""(\d+)\s+\d+\s+obj\b(.*?)endobj""", RegexOption.DOT_MATCHES_ALL)

/** 扫描整份文档，建立 objNum -> body（obj 与 endobj 之间的原始文本）映射。 */
private fun scanObjects(doc: String): LinkedHashMap<Int, String> {
    val map = LinkedHashMap<Int, String>()
    for (m in OBJ_RE.findAll(doc)) {
        val num = m.groupValues[1].toIntOrNull() ?: continue
        // 同一 objNum 多次出现（增量更新）时，后出现的覆盖先出现的。
        map[num] = m.groupValues[2]
    }
    return map
}

private val CATALOG_TYPE_RE = Regex("""/Type\s*/Catalog(?![A-Za-z])""")
private val PAGES_TYPE_RE = Regex("""/Type\s*/Pages(?![A-Za-z])""")
private val PAGE_TYPE_RE = Regex("""/Type\s*/Page(?![A-Za-z])""")

/**
 * 找出有序页列表（对象号）：/Type/Catalog -> /Pages -> 递归 /Kids。
 * 找不到 Catalog 或 Pages 树解析不出任何页时，退化为按文件出现顺序收集所有 /Type/Page 对象。
 */
private fun resolvePageOrder(objects: Map<Int, String>): List<Int> {
    val catalogEntry = objects.entries.firstOrNull { CATALOG_TYPE_RE.containsMatchIn(it.value) }
    if (catalogEntry != null) {
        val pagesRef = findRef(catalogEntry.value, "Pages")
        if (pagesRef != null && objects.containsKey(pagesRef)) {
            val ordered = LinkedHashSet<Int>()
            val visited = HashSet<Int>()
            fun walk(num: Int) {
                if (!visited.add(num)) return // 防御环引用
                val body = objects[num] ?: return
                val isPages = PAGES_TYPE_RE.containsMatchIn(body)
                val isPage = PAGE_TYPE_RE.containsMatchIn(body)
                val kids = findKidsRefs(body)
                if (isPages || (!isPage && kids.isNotEmpty())) {
                    for (k in kids) walk(k)
                } else {
                    ordered += num
                }
            }
            walk(pagesRef)
            if (ordered.isNotEmpty()) return ordered.toList()
        }
    }
    return objects.entries.filter { PAGE_TYPE_RE.containsMatchIn(it.value) }.map { it.key }
}

private fun findKidsRefs(body: String): List<Int> {
    val km = keyRegex("Kids").find(body) ?: return emptyList()
    var i = km.range.last + 1
    while (i < body.length && isPdfWhitespace(body[i])) i++
    if (i >= body.length || body[i] != '[') return emptyList()
    val end = body.indexOf(']', i)
    val inner = if (end >= 0) body.substring(i + 1, end) else body.substring(i + 1)
    return Regex("""(\d+)\s+\d+\s+R""").findAll(inner).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
}

// ============================================================
// 页面属性解析：/Rotate /MediaBox /Resources 都是可继承属性，
// 页对象自己没有时要沿 /Parent 链往上找。
// ============================================================

private fun resolveInheritedRaw(objects: Map<Int, String>, startObjNum: Int, key: String): String? {
    var current: Int? = startObjNum
    val visited = HashSet<Int>()
    val kr = keyRegex(key)
    while (current != null && visited.add(current)) {
        val body = objects[current] ?: return null
        if (kr.containsMatchIn(body)) return body
        current = findRef(body, "Parent")
    }
    return null
}

private fun resolveInheritedInt(objects: Map<Int, String>, pageNum: Int, key: String): Int? {
    val body = resolveInheritedRaw(objects, pageNum, key) ?: return null
    return findIntValue(body, key)
}

private fun resolveInheritedMediaBox(objects: Map<Int, String>, pageNum: Int): DoubleArray? {
    val body = resolveInheritedRaw(objects, pageNum, "MediaBox") ?: return null
    val km = keyRegex("MediaBox").find(body) ?: return null
    val rest = body.substring(km.range.last + 1)
    val m = Regex("""^\s*\[\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)""").find(rest) ?: return null
    return doubleArrayOf(
        m.groupValues[1].toDoubleOrNull() ?: 0.0,
        m.groupValues[2].toDoubleOrNull() ?: 0.0,
        m.groupValues[3].toDoubleOrNull() ?: 0.0,
        m.groupValues[4].toDoubleOrNull() ?: 0.0,
    )
}

/** 取 [key]（如 "Resources"）对应的字典文本；支持内联字典与间接引用两种写法。 */
private fun resolveInheritedDict(objects: Map<Int, String>, pageNum: Int, key: String): String {
    val body = resolveInheritedRaw(objects, pageNum, key) ?: return ""
    val ref = findRef(body, key)
    if (ref != null) return objects[ref] ?: ""
    return findSubDict(body, key) ?: ""
}

// ============================================================
// 内容流字节提取：/Contents（单引用或数组）-> stream ... endstream -> FlateDecode
// ============================================================

private fun resolveContentsRefs(pageBody: String): List<Int> {
    val km = keyRegex("Contents").find(pageBody) ?: return emptyList()
    var i = km.range.last + 1
    while (i < pageBody.length && isPdfWhitespace(pageBody[i])) i++
    if (i >= pageBody.length) return emptyList()
    return if (pageBody[i] == '[') {
        val end = pageBody.indexOf(']', i)
        val inner = if (end >= 0) pageBody.substring(i + 1, end) else pageBody.substring(i + 1)
        Regex("""(\d+)\s+\d+\s+R""").findAll(inner).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
    } else {
        val m = Regex("""(\d+)\s+(\d+)\s+R""").find(pageBody.substring(i))
        listOfNotNull(m?.groupValues?.get(1)?.toIntOrNull())
    }
}

/**
 * 从流对象的完整 body（obj 与 endobj 之间，含字典与 stream 数据）里取出解码后的原始字节。
 * /Length 若能解析（含间接引用）优先用它定位结尾，避免压缩数据里偶然出现 "endstream" 字样导致截断；
 * /Length 缺失或解析后紧跟的不是 endstream（不可靠）时，退回直接文本搜索 endstream。
 */
private fun extractStreamBytes(objBody: String, objects: Map<Int, String>): ByteArray? {
    val streamIdx = objBody.indexOf("stream")
    if (streamIdx < 0) return null
    val dictPart = objBody.substring(0, streamIdx)
    var dataStart = streamIdx + 6 // "stream".length

    // 跳过关键字后的行尾：优先 CRLF，其次单独的 LF 或 CR。
    if (dataStart + 1 < objBody.length && objBody[dataStart] == '\r' && objBody[dataStart + 1] == '\n') {
        dataStart += 2
    } else if (dataStart < objBody.length && (objBody[dataStart] == '\n' || objBody[dataStart] == '\r')) {
        dataStart += 1
    }

    val declaredLen = resolveLengthValue(dictPart, objects)
    var dataEnd: Int
    if (declaredLen != null && declaredLen >= 0 && dataStart + declaredLen <= objBody.length) {
        dataEnd = dataStart + declaredLen
        val tail = objBody.substring(dataEnd).trimStart('\r', '\n', ' ', '\t')
        if (!tail.startsWith("endstream")) {
            // /Length 不可靠，退回文本搜索。
            val fallback = objBody.indexOf("endstream", dataStart)
            dataEnd = if (fallback >= 0) fallback else dataEnd
        }
    } else {
        val fallback = objBody.indexOf("endstream", dataStart)
        dataEnd = if (fallback >= 0) fallback else objBody.length
    }
    if (dataEnd < dataStart) dataEnd = dataStart

    val raw = objBody.substring(dataStart, dataEnd).toByteArray(Charsets.ISO_8859_1)
    val hasFlate = Regex("""/Filter\s*(/FlateDecode(?![A-Za-z])|\[[^]]*/FlateDecode)""").containsMatchIn(dictPart)
    return if (hasFlate) {
        try {
            inflate(raw)
        } catch (e: Exception) {
            null
        }
    } else {
        raw // 也接受无 filter 的写法：原样返回
    }
}

private fun resolveLengthValue(dictPart: String, objects: Map<Int, String>): Int? {
    val ref = findRef(dictPart, "Length")
    if (ref != null) {
        val refBody = objects[ref] ?: return null
        return Regex("""-?\d+""").find(refBody)?.value?.toIntOrNull()
    }
    return findIntValue(dictPart, "Length")
}

private fun inflate(data: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(data)
    val out = ByteArrayOutputStream(maxOf(data.size * 3, 256))
    val buf = ByteArray(8192)
    while (!inflater.finished()) {
        val n = inflater.inflate(buf)
        if (n == 0) {
            if (inflater.needsInput() || inflater.needsDictionary()) break
        } else {
            out.write(buf, 0, n)
        }
    }
    inflater.end()
    return out.toByteArray()
}

// ============================================================
// 字体与文本解码：① Encoding 名含 UCS2/Identity -> UTF-16BE
//              ② 有 /ToUnicode -> 解析 bfchar/bfrange 建码表
//              ③ 其余 -> ISO-8859-1
// ============================================================

private enum class DecodeMode { UTF16BE, TO_UNICODE, LATIN1 }

private class FontInfo(val mode: DecodeMode, val toUnicode: Map<Int, String> = emptyMap(), val codeBytes: Int = 2)

/** 解析页面 /Resources 里的 /Font 子字典，建立资源名（如 "F1"）-> FontInfo 映射。 */
private fun buildFontMap(objects: Map<Int, String>, resourcesText: String): Map<String, FontInfo> {
    val fontDictText = findSubDict(resourcesText, "Font") ?: return emptyMap()
    val result = HashMap<String, FontInfo>()
    for (m in Regex("""/(\S+?)\s+(\d+)\s+\d+\s+R""").findAll(fontDictText)) {
        val resName = m.groupValues[1]
        val objNum = m.groupValues[2].toIntOrNull() ?: continue
        val fontBody = objects[objNum] ?: continue
        result[resName] = resolveFontInfo(objects, fontBody)
    }
    return result
}

private fun resolveFontInfo(objects: Map<Int, String>, fontBody: String): FontInfo {
    val encodingName = findNameValue(fontBody, "Encoding")
    if (encodingName != null && (encodingName.contains("UCS2") || encodingName.contains("Identity"))) {
        return FontInfo(DecodeMode.UTF16BE)
    }
    val toUnicodeRef = findRef(fontBody, "ToUnicode")
    if (toUnicodeRef != null) {
        val streamBody = objects[toUnicodeRef]
        val bytes = streamBody?.let { extractStreamBytes(it, objects) }
        if (bytes != null) {
            val cmapText = String(bytes, Charsets.ISO_8859_1)
            val map = parseToUnicodeCMap(cmapText)
            if (map.isNotEmpty()) {
                return FontInfo(DecodeMode.TO_UNICODE, map, detectCodeBytes(cmapText))
            }
        }
    }
    return FontInfo(DecodeMode.LATIN1)
}

/** 从 /ToUnicode CMap 的 begincodespacerange 推断每个码的字节宽度，找不到时默认 2（CJK 常见）。 */
private fun detectCodeBytes(cmapText: String): Int {
    val m = Regex("""begincodespacerange\s*<([0-9A-Fa-f]+)>""").find(cmapText)
    val hexLen = m?.groupValues?.get(1)?.length ?: return 2
    return if (hexLen > 0 && hexLen % 2 == 0) hexLen / 2 else 2
}

/** 解析 beginbfchar/endbfchar 与 beginbfrange/endbfrange，建立源码(Int) -> Unicode 字符串映射。 */
private fun parseToUnicodeCMap(cmapText: String): Map<Int, String> {
    val map = HashMap<Int, String>()
    for (block in Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL).findAll(cmapText)) {
        for (m in Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""").findAll(block.groupValues[1])) {
            val src = m.groupValues[1].toIntOrNull(16) ?: continue
            map[src] = hexToUnicodeString(m.groupValues[2])
        }
    }
    val entryRe = Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(\[[^]]*]|<[0-9A-Fa-f]+>)""")
    for (block in Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL).findAll(cmapText)) {
        for (m in entryRe.findAll(block.groupValues[1])) {
            val lo = m.groupValues[1].toIntOrNull(16) ?: continue
            val hi = m.groupValues[2].toIntOrNull(16) ?: continue
            val dst = m.groupValues[3]
            if (dst.startsWith("[")) {
                val items = Regex("""<([0-9A-Fa-f]+)>""").findAll(dst).map { it.groupValues[1] }.toList()
                for ((offset, code) in (lo..hi).withIndex()) {
                    if (offset < items.size) map[code] = hexToUnicodeString(items[offset])
                }
            } else {
                val dstHex = dst.trim('<', '>')
                val base = dstHex.toLongOrNull(16) ?: continue
                for (code in lo..hi) {
                    val v = base + (code - lo)
                    map[code] = hexToUnicodeString(v.toString(16).padStart(dstHex.length, '0'))
                }
            }
        }
    }
    return map
}

private fun hexToUnicodeString(hex: String): String {
    val h = if (hex.length % 2 == 1) hex + "0" else hex
    val bytes = ByteArray(h.length / 2) { i ->
        ((Character.digit(h[i * 2], 16) shl 4) or Character.digit(h[i * 2 + 1], 16)).toByte()
    }
    return String(bytes, Charsets.UTF_16BE)
}

private fun decodeBytes(bytes: ByteArray, fontInfo: FontInfo?): String = when (fontInfo?.mode) {
    DecodeMode.UTF16BE -> String(bytes, Charsets.UTF_16BE)
    DecodeMode.TO_UNICODE -> decodeWithCMap(bytes, fontInfo.toUnicode, fontInfo.codeBytes)
    else -> String(bytes, Charsets.ISO_8859_1)
}

private fun decodeWithCMap(bytes: ByteArray, map: Map<Int, String>, codeBytes: Int): String {
    val width = if (codeBytes > 0) codeBytes else 2
    val sb = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val take = minOf(width, bytes.size - i)
        var code = 0
        for (k in 0 until take) code = (code shl 8) or (bytes[i + k].toInt() and 0xFF)
        val mapped = map[code]
        when {
            mapped != null -> sb.append(mapped)
            take == 1 -> sb.append(code.toChar()) // 落单字节兜底，避免整段丢失
        }
        i += take
    }
    return sb.toString()
}

// ============================================================
// 内容流词法分析
// ============================================================

private sealed class Tok {
    class Num(val v: Double) : Tok()
    class Name(val v: String) : Tok()
    class Str(val v: ByteArray) : Tok()
    object ArrStart : Tok()
    object ArrEnd : Tok()
    class Op(val v: String) : Tok()
}

private fun tokenize(s: String): List<Tok> {
    val toks = ArrayList<Tok>()
    var i = 0
    val n = s.length
    while (i < n) {
        val c = s[i]
        when {
            isPdfWhitespace(c) -> i++
            c == '%' -> while (i < n && s[i] != '\n' && s[i] != '\r') i++
            c == '(' -> {
                val (b, next) = readLiteralString(s, i)
                toks += Tok.Str(b)
                i = next
            }
            c == '<' && i + 1 < n && s[i + 1] == '<' -> i = skipDict(s, i)
            c == '<' -> {
                val (b, next) = readHexString(s, i)
                toks += Tok.Str(b)
                i = next
            }
            c == '>' && i + 1 < n && s[i + 1] == '>' -> i += 2 // 落单的 >>，容错跳过
            c == '[' -> { toks += Tok.ArrStart; i++ }
            c == ']' -> { toks += Tok.ArrEnd; i++ }
            c == '/' -> {
                val (name, next) = readName(s, i)
                toks += Tok.Name(name)
                i = next
            }
            c == '+' || c == '-' || c == '.' || c.isDigit() -> {
                val (v, next) = readNumber(s, i)
                if (next > i) { toks += Tok.Num(v); i = next } else i++
            }
            c == ')' || c == '>' -> i++ // 不应单独出现，容错跳过避免死循环
            c == '{' || c == '}' -> i++ // PostScript 计算函数语法，内容流里不涉及
            else -> {
                val (op, next) = readOperator(s, i)
                if (op.isNotEmpty()) { toks += Tok.Op(op); i = next } else i++
            }
        }
    }
    return toks
}

private fun skipDict(s: String, start: Int): Int {
    var depth = 0
    var i = start
    while (i < s.length) {
        if (s.startsWith("<<", i)) { depth++; i += 2 } else if (s.startsWith(">>", i)) {
            depth--; i += 2
            if (depth <= 0) return i
        } else {
            i++
        }
    }
    return i
}

/**
 * 解析括号字面串 (...)，从 [start] 处的 '(' 开始。
 * 处理转义 \n \r \t \b \f \( \) \\、三位以内八进制 \ddd、行尾续行 \<EOL>，
 * 并按括号嵌套计数正确识别未转义的内层 ( )。
 *
 * 这是最容易写错的地方：解压后的中文 UTF-16BE 字节里经常直接含 0x28('(')/0x29(')')/0x5C('\\')，
 * 必须原样计入嵌套计数或转义处理，否则整段乱码或提前截断——整个文件用 Latin1 字符串表示原始字节
 * （每个 Char 精确对应一个原始字节，双向无损），下面按字节逐个处理正是为此。
 */
private fun readLiteralString(s: String, start: Int): Pair<ByteArray, Int> {
    var i = start + 1
    var depth = 1
    val out = ByteArrayOutputStream()
    while (i < s.length && depth > 0) {
        val c = s[i]
        when (c) {
            '\\' -> {
                i++
                if (i >= s.length) break
                val e = s[i]
                when (e) {
                    'n' -> { out.write('\n'.code); i++ }
                    'r' -> { out.write('\r'.code); i++ }
                    't' -> { out.write('\t'.code); i++ }
                    'b' -> { out.write(0x08); i++ }
                    'f' -> { out.write(0x0C); i++ }
                    '(' -> { out.write('('.code); i++ }
                    ')' -> { out.write(')'.code); i++ }
                    '\\' -> { out.write('\\'.code); i++ }
                    '\r' -> { i++; if (i < s.length && s[i] == '\n') i++ } // 行尾续行，不写入字节
                    '\n' -> i++ // 行尾续行，不写入字节
                    in '0'..'7' -> {
                        var oct = 0
                        var digits = 0
                        while (digits < 3 && i < s.length && s[i] in '0'..'7') {
                            oct = oct * 8 + (s[i] - '0')
                            i++
                            digits++
                        }
                        out.write(oct and 0xFF)
                    }
                    else -> { out.write(e.code and 0xFF); i++ } // 未知转义：反斜杠被忽略，字符按字面写入
                }
            }
            '(' -> { depth++; out.write(c.code and 0xFF); i++ }
            ')' -> { depth--; if (depth > 0) out.write(c.code and 0xFF); i++ }
            '\r' -> { out.write(0x0A); i++; if (i < s.length && s[i] == '\n') i++ } // 裸换行归一成 LF
            else -> { out.write(c.code and 0xFF); i++ }
        }
    }
    return out.toByteArray() to i
}

private fun readHexString(s: String, start: Int): Pair<ByteArray, Int> {
    var i = start + 1 // 跳过 '<'
    val hex = StringBuilder()
    while (i < s.length && s[i] != '>') {
        if (isHexDigit(s[i])) hex.append(s[i]) // 十六进制串里的空白等其它字符按规范忽略
        i++
    }
    if (i < s.length) i++ // 跳过 '>'
    if (hex.length % 2 == 1) hex.append('0') // 末位缺失按 0 补齐
    val out = ByteArray(hex.length / 2)
    for (k in out.indices) {
        val hi = Character.digit(hex[k * 2], 16)
        val lo = Character.digit(hex[k * 2 + 1], 16)
        out[k] = ((hi shl 4) or lo).toByte()
    }
    return out to i
}

private fun readName(s: String, start: Int): Pair<String, Int> {
    var i = start + 1 // 跳过 '/'
    val sb = StringBuilder()
    while (i < s.length && isRegularChar(s[i])) {
        if (s[i] == '#' && i + 2 < s.length && isHexDigit(s[i + 1]) && isHexDigit(s[i + 2])) {
            val v = (Character.digit(s[i + 1], 16) shl 4) or Character.digit(s[i + 2], 16)
            sb.append(v.toChar())
            i += 3
        } else {
            sb.append(s[i])
            i++
        }
    }
    return sb.toString() to i
}

private fun readNumber(s: String, start: Int): Pair<Double, Int> {
    var i = start
    val sb = StringBuilder()
    if (i < s.length && (s[i] == '+' || s[i] == '-')) { sb.append(s[i]); i++ }
    var saw = false
    while (i < s.length && (s[i].isDigit() || s[i] == '.')) { sb.append(s[i]); saw = true; i++ }
    val v = if (saw) sb.toString().toDoubleOrNull() ?: 0.0 else 0.0
    return v to i
}

private fun readOperator(s: String, start: Int): Pair<String, Int> {
    var i = start
    val sb = StringBuilder()
    while (i < s.length && isRegularChar(s[i])) { sb.append(s[i]); i++ }
    if (sb.isEmpty() && i < s.length) i++ // 兜底：无法识别的单字符也要前进，避免死循环
    return sb.toString() to i
}

// ============================================================
// 内容流解释执行：图形/文本状态机
// ============================================================

private class Mat(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    /** 依次应用 this 再应用 [outer]，即 PDF 规范里的 CTM' = this × outer（this 左乘）。 */
    fun then(outer: Mat): Mat = Mat(
        a = a * outer.a + b * outer.c,
        b = a * outer.b + b * outer.d,
        c = c * outer.a + d * outer.c,
        d = c * outer.b + d * outer.d,
        e = e * outer.a + f * outer.c + outer.e,
        f = e * outer.b + f * outer.d + outer.f,
    )

    companion object {
        val IDENTITY = Mat(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }
}

/** q/Q 保存与恢复的图形状态：CTM + 文本状态里同样属于图形状态的字体/字号/行距。 */
private class GState(val ctm: Mat, val fontName: String, val fontSize: Double, val leading: Double)

private fun interpretPage(
    tokens: List<Tok>,
    pageNumber: Int,
    fonts: Map<String, FontInfo>,
    rotate: Int,
    mediaBox: DoubleArray,
): List<Span> {
    val spans = ArrayList<Span>()
    var ctm = Mat.IDENTITY
    val gStack = ArrayDeque<GState>()
    var fontName = ""
    var fontSize = 0.0
    var leading = 0.0
    var textMatrix = Mat.IDENTITY
    var lineMatrix = Mat.IDENTITY
    val operands = ArrayList<Any>()

    fun newLine(tx: Double, ty: Double) {
        lineMatrix = Mat(1.0, 0.0, 0.0, 1.0, tx, ty).then(lineMatrix)
        textMatrix = lineMatrix
    }

    fun emit(bytes: ByteArray) {
        val text = decodeBytes(bytes, fonts[fontName])
        if (text.isNotBlank()) {
            // 每个串的位置 = 文本矩阵 × CTM 的平移分量。
            val combined = textMatrix.then(ctm)
            val (nx, ny) = normalizePoint(combined.e, combined.f, rotate, mediaBox)
            spans += Span(page = pageNumber, x = nx.toFloat(), y = ny.toFloat(), size = fontSize.toFloat(), text = text)
        }
    }

    fun readArray(startIdx: Int): Pair<List<Any>, Int> {
        val list = ArrayList<Any>()
        var j = startIdx
        while (j < tokens.size && tokens[j] !is Tok.ArrEnd) {
            when (val t = tokens[j]) {
                is Tok.Num -> { list += t.v; j++ }
                is Tok.Str -> { list += t.v; j++ }
                is Tok.Name -> { list += t.v; j++ }
                is Tok.ArrStart -> { val (sub, next) = readArray(j + 1); list.addAll(sub); j = next }
                is Tok.Op -> j++ // 数组里不应出现算符，容错跳过
                is Tok.ArrEnd -> j++
            }
        }
        return list to (j + 1) // 跳过 ArrEnd
    }

    fun mat6(off: Int): Mat {
        fun num(k: Int) = (operands.getOrNull(off + k) as? Double) ?: 0.0
        return Mat(num(0), num(1), num(2), num(3), num(4), num(5))
    }

    var i = 0
    while (i < tokens.size) {
        when (val t = tokens[i]) {
            is Tok.Num -> { operands += t.v; i++ }
            is Tok.Name -> { operands += t.v; i++ }
            is Tok.Str -> { operands += t.v; i++ }
            is Tok.ArrStart -> { val (arr, next) = readArray(i + 1); operands.addAll(arr); i = next }
            is Tok.ArrEnd -> i++ // 不应单独出现，容错跳过
            is Tok.Op -> {
                when (t.v) {
                    "q" -> gStack.addLast(GState(ctm, fontName, fontSize, leading))
                    "Q" -> if (gStack.isNotEmpty()) {
                        val g = gStack.removeLast()
                        ctm = g.ctm; fontName = g.fontName; fontSize = g.fontSize; leading = g.leading
                    }
                    "cm" -> if (operands.size >= 6) {
                        ctm = mat6(operands.size - 6).then(ctm)
                    }
                    "BT" -> { textMatrix = Mat.IDENTITY; lineMatrix = Mat.IDENTITY }
                    "ET" -> { /* 无需特殊处理，文本状态在下一个 BT 前保留也不会被读取 */ }
                    "Tm" -> if (operands.size >= 6) {
                        val m = mat6(operands.size - 6)
                        textMatrix = m; lineMatrix = m
                    }
                    "Td" -> if (operands.size >= 2) {
                        newLine(
                            operands[operands.size - 2] as? Double ?: 0.0,
                            operands[operands.size - 1] as? Double ?: 0.0,
                        )
                    }
                    "TD" -> if (operands.size >= 2) {
                        val ty = operands[operands.size - 1] as? Double ?: 0.0
                        leading = -ty
                        newLine(operands[operands.size - 2] as? Double ?: 0.0, ty)
                    }
                    "T*" -> newLine(0.0, -leading)
                    "TL" -> if (operands.isNotEmpty()) leading = operands.last() as? Double ?: leading
                    "Tf" -> if (operands.size >= 2) {
                        fontName = operands[operands.size - 2] as? String ?: fontName
                        fontSize = operands[operands.size - 1] as? Double ?: fontSize
                    }
                    "Tj" -> (operands.lastOrNull() as? ByteArray)?.let { emit(it) }
                    "'", "\"" -> {
                        // ' 等价于 T* 后接 Tj；" 多了 word/char spacing 两个操作数，位置无关直接忽略。
                        newLine(0.0, -leading)
                        (operands.lastOrNull() as? ByteArray)?.let { emit(it) }
                    }
                    "TJ" -> {
                        val arr = operands.lastOrNull() as? List<*>
                        if (arr != null) {
                            // 数组里的串按顺序拼接成一个 span，字距调整数字整体忽略。
                            val out = ByteArrayOutputStream()
                            for (elem in arr) if (elem is ByteArray) out.write(elem)
                            emit(out.toByteArray())
                        }
                    }
                    else -> { /* re/f/S/Tr/w/g/rg/... 等与文本定位无关的算符，忽略 */ }
                }
                operands.clear()
                i++
            }
        }
    }
    return spans
}

// ============================================================
// 坐标归一化：把 CTM 变换后的坐标按页面 /Rotate 转成「正视」方向
// ============================================================

private fun normalizeRotate(deg: Int): Int {
    val r = ((deg % 360) + 360) % 360
    return if (r == 90 || r == 180 || r == 270) r else 0
}

private fun normalizePoint(x: Double, y: Double, rotate: Int, mediaBox: DoubleArray): Pair<Double, Double> {
    val llx = mediaBox[0]
    val lly = mediaBox[1]
    val w = mediaBox[2] - llx
    val h = mediaBox[3] - lly
    val x0 = x - llx
    val y0 = y - lly
    return when (rotate) {
        90 -> y0 to (w - x0)
        180 -> (w - x0) to (h - y0)
        270 -> (h - y0) to x0
        else -> x0 to y0
    }
}

private fun extractPageSpans(objects: Map<Int, String>, pageObjNum: Int, pageIndex: Int): List<Span> {
    val pageBody = objects[pageObjNum] ?: return emptyList()
    val rotate = normalizeRotate(resolveInheritedInt(objects, pageObjNum, "Rotate") ?: 0)
    val mediaBox = resolveInheritedMediaBox(objects, pageObjNum) ?: doubleArrayOf(0.0, 0.0, 612.0, 792.0)
    val resourcesText = resolveInheritedDict(objects, pageObjNum, "Resources")
    val fonts = buildFontMap(objects, resourcesText)

    val contentText = StringBuilder()
    for (cn in resolveContentsRefs(pageBody)) {
        val body = objects[cn] ?: continue
        val bytes = extractStreamBytes(body, objects) ?: continue
        contentText.append(String(bytes, Charsets.ISO_8859_1)).append('\n')
    }
    val tokens = tokenize(contentText.toString())
    return interpretPage(tokens, pageIndex, fonts, rotate, mediaBox)
}

// ============================================================
// 通用小工具：PDF 字典/名字/间接引用的最小化解析
// ============================================================

private fun isPdfWhitespace(c: Char): Boolean =
    c == '\u0000' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r' || c == ' '

private fun isPdfDelimiter(c: Char): Boolean =
    c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%'

private fun isRegularChar(c: Char): Boolean = !isPdfWhitespace(c) && !isPdfDelimiter(c)

private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/** 匹配 /key 且后面紧跟分隔符或字符串结尾，避免把 /Type 误匹配成 /TypeXxx 的前缀。 */
private fun keyRegex(key: String): Regex = Regex("""/${Regex.escape(key)}(?=[\s()<>\[\]{}/%]|$)""")

/** 在字典文本里找 /key 后面的间接引用 "N G R"，返回对象号 N。 */
private fun findRef(dictText: String, key: String): Int? {
    val km = keyRegex(key).find(dictText) ?: return null
    val rest = dictText.substring(km.range.last + 1)
    val m = Regex("""^\s*(\d+)\s+\d+\s+R""").find(rest) ?: return null
    return m.groupValues[1].toIntOrNull()
}

/** 在字典文本里找 /key 后紧跟的子字典 <<...>>（支持嵌套），返回内部文本（不含最外层 << >>）。 */
private fun findSubDict(dictText: String, key: String): String? {
    val km = keyRegex(key).find(dictText) ?: return null
    var i = km.range.last + 1
    while (i < dictText.length && isPdfWhitespace(dictText[i])) i++
    if (i + 1 >= dictText.length || dictText[i] != '<' || dictText[i + 1] != '<') return null
    val bodyStart = i + 2
    var depth = 1
    var j = bodyStart
    while (j < dictText.length && depth > 0) {
        if (dictText.startsWith("<<", j)) { depth++; j += 2 } else if (dictText.startsWith(">>", j)) {
            depth--; j += 2
        } else {
            j++
        }
    }
    val bodyEnd = j - 2
    return if (bodyEnd >= bodyStart) dictText.substring(bodyStart, bodyEnd) else ""
}

/** 在字典文本里找 /key 后紧跟的名字值 /Xxx，返回不含斜杠的名字。 */
private fun findNameValue(dictText: String, key: String): String? {
    val km = keyRegex(key).find(dictText) ?: return null
    var i = km.range.last + 1
    while (i < dictText.length && isPdfWhitespace(dictText[i])) i++
    if (i >= dictText.length || dictText[i] != '/') return null
    val (name, _) = readName(dictText, i)
    return name
}

/** 在字典文本里找 /key 后紧跟的整数字面量（非间接引用）。 */
private fun findIntValue(dictText: String, key: String): Int? {
    val km = keyRegex(key).find(dictText) ?: return null
    val rest = dictText.substring(km.range.last + 1)
    val m = Regex("""^\s*(-?\d+)""").find(rest) ?: return null
    return m.groupValues[1].toIntOrNull()
}

// ============================================================
// 加密检测：扫描所有 trailer 字典中的 /Encrypt 键
// ============================================================

/** 检测 PDF 是否含有加密标记。找到 /Encrypt 时立即抛异常，避免后续解析密文流。 */
private fun checkEncryption(doc: String) {
    // 注意：PDF 可能有多个 trailer（增量更新），都要扫。
    val trailerRegex = Regex("""trailer\s*<<""")
    for (match in trailerRegex.findAll(doc)) {
        // << 出现在 match 的末尾
        val startIdx = match.range.last - 1
        val trailerDict = extractDictFromPos(doc, startIdx)
        if (trailerDict != null && keyRegex("Encrypt").containsMatchIn(trailerDict)) {
            throw PdfUnsupportedException("这份 PDF 已加密，暂不支持解析加密文档，请先移除密码保护后重试")
        }
    }
}

/** 从文档的指定位置（应该指向 << 处）提取完整的字典文本（不含外层 << >>）。 */
private fun extractDictFromPos(doc: String, startIdx: Int): String? {
    if (startIdx + 1 >= doc.length || !doc.startsWith("<<", startIdx)) return null
    var i = startIdx + 2
    var depth = 1
    while (i < doc.length && depth > 0) {
        if (doc.startsWith("<<", i)) {
            depth++
            i += 2
        } else if (doc.startsWith(">>", i)) {
            depth--
            i += 2
        } else {
            i++
        }
    }
    return if (depth == 0) doc.substring(startIdx + 2, i - 2) else null
}
