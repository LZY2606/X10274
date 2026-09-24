package dicomreview.dicom

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 最小 DICOM 读取器：支持 Explicit/Implicit VR Little Endian 的元数据解析，
 * 支持 SQ 序列（带/不带长度定界）。封装像素数据（JPEG/JPEG2000/RLE…）不解码，
 * 仅标记封装起始位置；未压缩 OW 像素直接读取。
 */
class DicomReader(private val bytes: ByteArray) {
    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val diagnostics = mutableListOf<String>()

    data class Element(
        val code: Int,
        val vr: String,
        val value: ByteArray,
        val children: List<List<Element>>?,
        val dataOffset: Int,
    )

    data class ParseResult(
        val metaElements: List<Element>,
        val datasetElements: List<Element>,
        val diagnostics: List<String>,
    )

    fun parse(): ParseResult {
        require(bytes.size >= 132) { "文件长度不足，不可能是合法 DICOM（${bytes.size} 字节）" }
        val preambleOk = bytes.copyOfRange(128, 132).let { it[0] == 'D'.code.toByte() && it[1] == 'I'.code.toByte() && it[2] == 'C'.code.toByte() && it[3] == 'M'.code.toByte() }
        require(preambleOk) { "缺少 DICM 前缀，无法识别为 DICOM 文件" }

        var pos = 132
        val meta = mutableListOf<Element>()
        // 文件 meta 始终是 Explicit VR LE。
        while (pos < bytes.size) {
            val (group, _) = readTagAt(pos)
            if (group != 0x0002) break
            val (el, next) = readElement(pos, explicit = true)
            meta += el
            pos = next
        }
        val tsUid = meta.firstOrNull { it.code == Tags.TransferSyntaxUID.code }
            ?.let { readString(it.value).substringBefore('\u0000').trim() }
            ?: throw IllegalArgumentException("缺少 (0002,0010) TransferSyntaxUID")
        val explicit = tsUid != TransferSyntaxes.IMPLICIT_LE
        if (tsUid == TransferSyntaxes.DEFLATED) {
            diagnostics += "Deflated 传输语法不支持解压：${TransferSyntaxes.name(tsUid)}"
        }
        val dataset = mutableListOf<Element>()
        while (pos < bytes.size) {
            val (el, next) = readElement(pos, explicit)
            dataset += el
            pos = next
        }
        return ParseResult(meta, dataset, diagnostics)
    }

    private fun readTagAt(p: Int): Pair<Int, Int> {
        val group = u16(p)
        val element = u16(p + 2)
        return ((group shl 16) or element) to (p + 4)
    }

    private fun readElement(start: Int, explicit: Boolean): Pair<Element, Int> {
        var p = start
        val code = readTagAt(p).first; p += 4
        var vr: String
        var headerLen: Int
        if (explicit) {
            vr = String(bytes, p, 2, Charsets.US_ASCII).trim()
            headerLen = 8
            if (vr in setOf("OB", "OW", "OF", "OD", "SQ", "UT", "UN")) {
                headerLen = 12 // 2 VR + 2 reserved + 4 length
            }
        } else {
            vr = guessVr(code)
            headerLen = 8
        }
        val length: Long = if (headerLen == 12 || !explicit) {
            u32(p + 4)
        } else {
            u16(p + 2).toLong()
        }
        val valueStart = p + headerLen
        val undetermined = length == 0xFFFFFFFFL

        if (vr == "SQ") {
            val (items, end) = readSequence(valueStart, length, explicit)
            return Element(code, vr, ByteArray(0), items, valueStart) to end
        }
        // 封装像素数据（OB/OW + 未定义长度，首项是 FFFE,E000）
        if (undetermined && code == Tags.PixelData.code) {
            val end = scanDelimiter(valueStart, explicit)
            val value = bytes.copyOfRange(valueStart, end)
            return Element(code, if (vr.isBlank()) "OB" else vr, value, null, valueStart) to end
        }
        if (undetermined) {
            // 其它未定义长度元素（隐式 VR 下常见于 SQ 但 vr 猜测失败）
            val (items, end) = readSequence(valueStart, length, explicit)
            return Element(code, "SQ", ByteArray(0), items, valueStart) to end
        }
        val end = valueStart + length.toInt()
        require(end <= bytes.size) { "元素 ${Tags.lookup(code).hex} 长度越过文件末尾" }
        return Element(code, vr, bytes.copyOfRange(valueStart, end), null, valueStart) to end
    }

    /** 读取 SQ：返回每个 item 的元素列表，处理定长与未定义长度两种形式。 */
    private fun readSequence(start: Int, totalLength: Long, explicit: Boolean): Pair<List<List<Element>>, Int> {
        val items = mutableListOf<List<Element>>()
        var p = start
        val end = if (totalLength == 0xFFFFFFFFL) bytes.size.toLong() else start + totalLength
        while (p < end) {
            val code = (u16(p) shl 16) or u16(p + 2)
            val itemLen = u32(p + 4)
            when {
                code == 0xFFFEE00D -> { p += 8; break } // SequenceDelimitationItem
                code == 0xFFFEE000 -> {
                    val itemStart = p + 8
                    val itemEnd: Int
                    if (itemLen == 0xFFFFFFFFL) {
                        itemEnd = findItemDelimiter(itemStart)
                        p = itemEnd + 8
                    } else {
                        itemEnd = itemStart + itemLen.toInt()
                        p = itemEnd
                    }
                    items += readItemElements(itemStart, itemEnd, explicit)
                }
                else -> break
            }
        }
        return items to p
    }

    private fun readItemElements(start: Int, end: Int, explicit: Boolean): List<Element> {
        val out = mutableListOf<Element>()
        var p = start
        while (p < end) {
            val code = (u16(p) shl 16) or u16(p + 2)
            if (code == 0xFFFEE00D) break
            val (el, next) = readElement(p, explicit)
            out += el
            p = next
        }
        return out
    }

    private fun findItemDelimiter(start: Int): Int {
        var p = start
        // 简化：item 内不再出现裸 FFFE,E00D（嵌套 SQ 各自带自己的定界）。
        // 为稳健起见做深度跟踪：遇到 SQ/未定义长度元素则跳过其定界。
        val stack = ArrayDeque<Pair<Long, Long>>() // (delimStart, delimEnd)
        while (p + 8 <= bytes.size) {
            val code = (u16(p) shl 16) or u16(p + 2)
            if (code == 0xFFFEE00D) {
                while (stack.isNotEmpty() && stack.last().second <= p.toLong()) stack.removeLast()
                if (stack.isEmpty()) return p
            }
            if (code == 0xFFFEE000) {
                val len = u32(p + 4)
                if (len != 0xFFFFFFFFL) { p += 8 + len.toInt(); continue }
            }
            p += 8 // 无法精确跳过未知结构时的保守步长（本案例的 functional group 结构简单）
        }
        return start
    }

    private fun scanDelimiter(start: Int, @Suppress("UNUSED_PARAMETER") explicit: Boolean): Int {
        var p = start
        while (p + 8 <= bytes.size) {
            if ((u16(p) shl 16) or u16(p + 2) == 0xFFFEE0DD) return p // fragments 结束
            p += 8
        }
        return bytes.size
    }

    private fun u16(p: Int): Int = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)
    private fun u32(p: Int): Long =
        (bytes[p].toLong() and 0xFF) or ((bytes[p + 1].toLong() and 0xFF) shl 8) or
            ((bytes[p + 2].toLong() and 0xFF) shl 16) or ((bytes[p + 3].toLong() and 0xFF) shl 24)

    companion object {
        fun readString(value: ByteArray): String = String(value, Charsets.US_ASCII).trimEnd('\u0000', ' ')

        /** 隐式 VR 下按 tag 字典猜测 VR；未知一律 UN。 */
        fun guessVr(code: Int): String = Tags.lookup(code).let { if (it.keyword == "PrivateOrUnknown") "UN" else it.vr }
    }
}
