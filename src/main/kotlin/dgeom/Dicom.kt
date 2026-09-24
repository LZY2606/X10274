package dgeom

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val NUL: Char = '\u0000'
private const val NUL_STR: String = "\u0000"

/**
 * 最小 DICOM P10 读写：支持 Explicit/Implicit VR Little Endian 的解析，
 * 其它传输语法（如 JPEG 1.2.840.10008.1.2.4.50）标记为 unsupported_transfer_syntax，
 * 原始字节与 meta tag 保留，但不进入几何解释层。
 */
object Dicom {
    const val TS_EXPLICIT_LE = "1.2.840.10008.1.2.1"
    const val TS_IMPLICIT_LE = "1.2.840.10008.1.2"

    fun tagKey(group: Int, element: Int) = "(%04X,%04X)".format(group, element)

    private val LONG_VRS = setOf("OB", "OW", "OF", "SQ", "UT", "UN", "OD", "OL", "OV", "UC", "UR")
    private val STRING_VRS = setOf("AE", "AS", "CS", "DA", "DS", "DT", "IS", "LO", "LT", "PN", "SH", "ST", "TM", "UC", "UI", "UR", "UT")
    private val IMPLICIT_KNOWN = mapOf(
        "(0008,0016)" to "UI", "(0008,0018)" to "UI", "(0020,000D)" to "UI", "(0020,000E)" to "UI",
        "(0020,0052)" to "UI", "(0020,0013)" to "IS", "(0020,0032)" to "DS", "(0020,0037)" to "DS",
        "(0028,0030)" to "DS", "(0028,0010)" to "US", "(0028,0011)" to "US",
        "(5200,9230)" to "SQ", "(0020,9111)" to "SQ", "(0020,9113)" to "SQ", "(0020,9116)" to "SQ",
        "(0020,9057)" to "UL", "(7FE0,0010)" to "OW",
    )

    // ---------------- 解析 ----------------

    fun parse(bytes: ByteArray, fileName: String): Material {
        val hash = sha256Hex(bytes)
        val id = shortId("mat", hash)
        val diagnostics = mutableListOf<Diagnostic>()
        if (bytes.size < 132 || String(bytes, 128, 4, Charsets.US_ASCII) != "DICM") {
            diagnostics += Diagnostic("not_dicom_p10", "缺少 128 字节前导与 DICM 前缀，仅按原始字节保存")
            return Material(id, hash, fileName, null, emptyMap(), diagnostics)
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(132)
        val tags = linkedMapOf<String, RawTag>()
        var transferSyntax: String? = null
        try {
            while (buf.remaining() >= 8) {
                val pos = buf.position()
                val group = buf.short.toInt() and 0xFFFF
                if (group != 0x0002) { buf.position(pos); break }
                buf.position(pos)
                val (key, tag) = readExplicitElement(buf)
                tags[key] = tag
                if (key == "(0002,0010)") transferSyntax = tag.values.firstOrNull()
            }
        } catch (e: Exception) {
            diagnostics += Diagnostic("meta_parse_error", "文件 meta 解析失败: ${e.message}")
        }
        if (transferSyntax == null) {
            diagnostics += Diagnostic("missing_transfer_syntax", "缺少 (0002,0010) TransferSyntaxUID", "(0002,0010)")
        } else if (transferSyntax != TS_EXPLICIT_LE && transferSyntax != TS_IMPLICIT_LE) {
            diagnostics += Diagnostic(
                "unsupported_transfer_syntax",
                "传输语法 $transferSyntax 不受支持；原始字节与 meta tag 保留，不进入几何解释层",
                "(0002,0010)"
            )
            return Material(id, hash, fileName, transferSyntax, tags.toMap(), diagnostics)
        }
        try {
            if (transferSyntax == TS_IMPLICIT_LE) readImplicitDataset(buf, tags, 0)
            else readExplicitDataset(buf, tags, 0)
        } catch (e: Exception) {
            diagnostics += Diagnostic("dataset_parse_error", "数据集解析在偏移 ${buf.position()} 处停止: ${e.message}")
        }
        return Material(id, hash, fileName, transferSyntax, tags.toMap(), diagnostics)
    }

    private fun readExplicitDataset(buf: ByteBuffer, out: MutableMap<String, RawTag>, depth: Int) {
        while (buf.remaining() >= 8) {
            val (key, tag) = readExplicitElement(buf, depth)
            out[key] = tag
        }
    }

    private fun readImplicitDataset(buf: ByteBuffer, out: MutableMap<String, RawTag>, depth: Int) {
        while (buf.remaining() >= 8) {
            val group = buf.short.toInt() and 0xFFFF
            val elem = buf.short.toInt() and 0xFFFF
            if (group == 0xFFFE) { buf.position(buf.position() - 4); return }
            val len = buf.int
            val key = tagKey(group, elem)
            val vr = IMPLICIT_KNOWN[key] ?: "UN"
            out[key] = readValue(buf, vr, len, key, explicit = false, depth = depth)
        }
    }

    private fun readExplicitElement(buf: ByteBuffer, depth: Int = 0): Pair<String, RawTag> {
        val group = buf.short.toInt() and 0xFFFF
        val elem = buf.short.toInt() and 0xFFFF
        val vr = String(byteArrayOf(buf.get(), buf.get()), Charsets.US_ASCII)
        val len: Int
        if (vr in LONG_VRS) {
            buf.short // reserved
            len = buf.int
        } else {
            len = buf.short.toInt() and 0xFFFF
        }
        val key = tagKey(group, elem)
        return key to readValue(buf, vr, len, key, explicit = true, depth = depth)
    }

    private fun readValue(buf: ByteBuffer, vr: String, len: Int, key: String, explicit: Boolean, depth: Int): RawTag {
        if (vr == "SQ" && depth < 6) {
            val items = mutableListOf<Map<String, RawTag>>()
            val end = if (len < 0) Int.MAX_VALUE else buf.position() + len
            while (buf.remaining() >= 8 && buf.position() < end) {
                val ig = buf.short.toInt() and 0xFFFF
                val ie = buf.short.toInt() and 0xFFFF
                val ilen = buf.int
                if (ig == 0xFFFE && ie == 0xE00D) break // sequence delimitation
                if (ig == 0xFFFE && ie == 0xE000) {
                    val item = linkedMapOf<String, RawTag>()
                    val iend = if (ilen < 0) Int.MAX_VALUE else buf.position() + ilen
                    while (buf.remaining() >= 8 && buf.position() < iend) {
                        val pos = buf.position()
                        val g = buf.short.toInt() and 0xFFFF
                        buf.position(pos)
                        if (g == 0xFFFE) { buf.int; buf.int; break } // item delimitation
                        if (explicit) {
                            val (k, t) = readExplicitElement(buf, depth + 1)
                            item[k] = t
                        } else {
                            readImplicitDataset(buf, item, depth + 1)
                            break
                        }
                    }
                    items += item
                } else break
            }
            return RawTag(key, "SQ", items = items)
        }
        if (len < 0 || len > buf.remaining()) {
            val avail = maxOf(0, buf.remaining())
            buf.position(buf.position() + avail)
            return RawTag(key, vr, binaryLength = avail)
        }
        val raw = ByteArray(len)
        buf.get(raw)
        return when {
            vr in STRING_VRS -> {
                val s = String(raw, Charsets.US_ASCII).trimEnd(NUL, ' ')
                RawTag(key, vr, values = if (s.isEmpty()) emptyList() else s.split('\\'))
            }
            vr == "US" || vr == "SS" -> {
                val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val vals = (0 until len / 2).map { (b.short.toInt() and 0xFFFF).toString() }
                RawTag(key, vr, values = vals)
            }
            vr == "UL" || vr == "SL" -> {
                val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val vals = (0 until len / 4).map { (b.int.toLong() and 0xFFFFFFFFL).toString() }
                RawTag(key, vr, values = vals)
            }
            else -> RawTag(key, vr, binaryLength = len) // OB/OW/UN/未知：原字节保留在材料 BLOB 中
        }
    }

    // ---------------- 写出（离线案例包用） ----------------

    data class El(
        val group: Int, val element: Int, val vr: String,
        val text: String? = null, val ints: List<Long>? = null,
        val bin: ByteArray? = null, val items: List<List<El>>? = null,
    )

    fun ui(g: Int, e: Int, v: String) = El(g, e, "UI", text = v)
    fun cs(g: Int, e: Int, v: String) = El(g, e, "CS", text = v)
    fun ds(g: Int, e: Int, vararg v: String) = El(g, e, "DS", text = v.joinToString("\\"))
    fun isn(g: Int, e: Int, v: Int) = El(g, e, "IS", text = v.toString())
    fun us(g: Int, e: Int, vararg v: Int) = El(g, e, "US", ints = v.map { it.toLong() })
    fun ul(g: Int, e: Int, vararg v: Long) = El(g, e, "UL", ints = v.toList())
    fun ow(g: Int, e: Int, b: ByteArray) = El(g, e, "OW", bin = b)
    fun ob(g: Int, e: Int, b: ByteArray) = El(g, e, "OB", bin = b)
    fun sq(g: Int, e: Int, items: List<List<El>>) = El(g, e, "SQ", items = items)

    fun build(dataset: List<El>, transferSyntax: String = TS_EXPLICIT_LE, sopClassUid: String = "1.2.840.10008.1.5.1.4.1.1.2", sopInstanceUid: String = "1.2.3"): ByteArray {
        val meta = mutableListOf(
            ob(0x0002, 0x0001, byteArrayOf(0, 1)),
            ui(0x0002, 0x0002, sopClassUid),
            ui(0x0002, 0x0003, sopInstanceUid),
            ui(0x0002, 0x0010, transferSyntax),
            ui(0x0002, 0x0012, "1.2.826.0.1.3680043.9.7433"),
        )
        val metaBody = ByteArrayOutputStream()
        meta.forEach { writeEl(metaBody, it) }
        val metaBytes = metaBody.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(ByteArray(128))
        out.write("DICM".toByteArray(Charsets.US_ASCII))
        writeEl(out, ul(0x0002, 0x0000, metaBytes.size.toLong()))
        out.write(metaBytes)
        dataset.forEach { writeEl(out, it) }
        return out.toByteArray()
    }

    private fun encodeValue(el: El): ByteArray = when {
        el.bin != null -> el.bin
        el.items != null -> {
            val out = ByteArrayOutputStream()
            for (item in el.items) {
                val body = ByteArrayOutputStream()
                item.forEach { writeEl(body, it) }
                val b = body.toByteArray()
                writeU16(out, 0xFFFE); writeU16(out, 0xE000); writeU32(out, b.size)
                out.write(b)
            }
            out.toByteArray()
        }
        el.ints != null -> {
            val out = ByteArrayOutputStream()
            when (el.vr) {
                "US" -> el.ints.forEach { writeU16(out, it.toInt()) }
                "UL" -> el.ints.forEach { writeU32(out, it.toInt()) }
                else -> error("unsupported int vr ${el.vr}")
            }
            out.toByteArray()
        }
        else -> {
            var s = el.text ?: ""
            if (s.length % 2 == 1) s += if (el.vr == "UI") NUL_STR else " "
            s.toByteArray(Charsets.US_ASCII)
        }
    }

    private fun writeEl(out: ByteArrayOutputStream, el: El) {
        val value = encodeValue(el)
        writeU16(out, el.group); writeU16(out, el.element)
        out.write(el.vr.toByteArray(Charsets.US_ASCII))
        if (el.vr in LONG_VRS) {
            writeU16(out, 0); writeU32(out, value.size)
        } else {
            writeU16(out, value.size)
        }
        out.write(value)
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    private fun writeU32(out: ByteArrayOutputStream, v: Int) { writeU16(out, v); writeU16(out, v shr 16) }
}
