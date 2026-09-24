package gsb.dicom

import gsb.model.Diagnostic
import gsb.model.InstanceFile
import gsb.model.RawTag
import gsb.model.Vec3
import gsb.model.FrameGeometry

object Tags {
    fun find(tags: List<RawTag>, g: String, e: String): RawTag? =
        tags.firstOrNull { it.g.equals(g, true) && it.e.equals(e, true) }

    fun value(tags: List<RawTag>, g: String, e: String): String? = find(tags, g, e)?.value
}

object Geometry {
    const val RULE_VERSION = 1

    /** 本系统可解码像素的传输语法；其余保留元数据并给出诊断。 */
    val SUPPORTED_TRANSFER_SYNTAXES = setOf(
        "1.2.840.10008.1.2",    // Implicit VR Little Endian
        "1.2.840.10008.1.2.1",  // Explicit VR Little Endian
        "1.2.840.10008.1.2.2",  // Explicit VR Big Endian
    )

    fun parseDoubles(raw: String?): List<Double>? {
        if (raw == null) return null
        val parts = raw.split("\\").map { it.trim() }
        val out = ArrayList<Double>(parts.size)
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return null
            out.add(v)
        }
        return out
    }

    fun parseVec3(raw: String?): Vec3? =
        parseDoubles(raw)?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }

    /**
     * 从原始 tag 派生每帧几何。原始表示保留在 instances.tags_json；
     * 这里只做规范化，无法转换的值留下诊断。
     */
    fun derive(instanceId: String, file: InstanceFile): Pair<List<FrameGeometry>, List<Diagnostic>> {
        val diags = mutableListOf<Diagnostic>()
        if (file.transferSyntaxUid !in SUPPORTED_TRANSFER_SYNTAXES) {
            diags += Diagnostic(
                "UNSUPPORTED_TRANSFER_SYNTAX",
                "传输语法 ${file.transferSyntaxUid} 的像素不可解码；元数据与原始字节保留",
                file.transferSyntaxUid,
            )
        }
        val sopUid = Tags.value(file.tags, "0008", "0018")
        val seriesUid = Tags.value(file.tags, "0020", "000E")
        val forUid = Tags.value(file.tags, "0020", "0052")
        val instNum = Tags.value(file.tags, "0020", "0013")?.toIntOrNull()
        val spacing = Tags.value(file.tags, "0028", "0030")?.let { raw ->
            parseDoubles(raw)?.takeIf { it.size == 2 } ?: run {
                diags += Diagnostic("UNPARSABLE_VALUE", "PixelSpacing 无法解析，原值保留", raw)
                null
            }
        }
        val numFrames = Tags.value(file.tags, "0028", "0008")?.toIntOrNull() ?: 1
        val perFrame = Tags.find(file.tags, "5200", "9230")?.items

        fun frameGeom(index: Int, srcTags: List<RawTag>, srcLabel: String): FrameGeometry {
            val fid = "$instanceId#f$index"
            val fd = mutableListOf<Diagnostic>()
            val ippRaw = Tags.value(srcTags, "0020", "0032")
            val iopRaw = Tags.value(srcTags, "0020", "0037")
            val ipp = ippRaw?.let { raw ->
                parseVec3(raw) ?: run {
                    fd += Diagnostic("UNPARSABLE_VALUE", "ImagePositionPatient 无法解析，原值保留", raw)
                    null
                }
            }
            val iopVals = iopRaw?.let { raw ->
                parseDoubles(raw)?.takeIf { it.size == 6 } ?: run {
                    fd += Diagnostic("UNPARSABLE_VALUE", "ImageOrientationPatient 无法解析，原值保留", raw)
                    null
                }
            }
            if (ipp == null || iopVals == null) {
                fd += Diagnostic(
                    "MISSING_GEOMETRY",
                    "缺少可用切片几何（$srcLabel）；该帧进入 unpositioned 分支，不被丢弃",
                )
            }
            return FrameGeometry(
                frameId = fid,
                instanceId = instanceId,
                frameIndex = index,
                ippMm = ipp,
                iopRow = iopVals?.let { Vec3(it[0], it[1], it[2]) },
                iopCol = iopVals?.let { Vec3(it[3], it[4], it[5]) },
                pixelSpacingMm = spacing,
                forUid = forUid,
                sopUid = sopUid,
                seriesUid = seriesUid,
                instanceNumber = instNum,
                diagnostics = fd,
            )
        }

        val frames = mutableListOf<FrameGeometry>()
        if (numFrames > 1 && perFrame != null && perFrame.size == numFrames) {
            diags += Diagnostic("MULTI_FRAME_EXPANDED", "多帧对象展开为 $numFrames 帧（functional group 逐帧几何）")
            perFrame.forEachIndexed { idx, itemTags ->
                val pos = Tags.find(itemTags, "0020", "9113")?.items?.firstOrNull() ?: emptyList()
                val ori = Tags.find(itemTags, "0020", "9116")?.items?.firstOrNull() ?: emptyList()
                frames += frameGeom(idx, pos + ori, "functional group 帧 $idx")
            }
        } else {
            frames += frameGeom(0, file.tags, "顶层 tag")
        }
        return frames to diags
    }
}
