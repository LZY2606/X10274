package bench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * 规则 id（机器可读事件/diagnostic 的依据）：
 *  R-ORDER-INSTANCENUMBER : InstanceNumber 不当空间排序真值
 *  R-DIR-ORTHOGONAL      : 方向向量先检查正交与单位长度容差
 *  R-LOCAL-SPACING       : 局部间距变化产生分支（GAP）
 *  R-DUPLICATE-SLICE     : 重片产生分支（重复位置）
 *  R-FOR-BRANCH          : 不同 FrameOfReference 产生分支而不强合
 *  R-MULTIFRAME          : 多帧 functional group 逐帧解释
 *  R-UID-CONFLICT        : SOP/Series UID 冲突
 *  R-GEOM-MISSING        : 缺失几何不参与成体，值留在原位
 *  R-TRANSFER-SYNTAX     : 不支持的传输语法不解码像素
 */
object Rules {
    const val ORDER_INSTANCE_NUMBER = "R-ORDER-INSTANCENUMBER"
    const val DIR_ORTHOGONAL = "R-DIR-ORTHOGONAL"
    const val LOCAL_SPACING = "R-LOCAL-SPACING"
    const val DUPLICATE_SLICE = "R-DUPLICATE-SLICE"
    const val FOR_BRANCH = "R-FOR-BRANCH"
    const val MULTIFRAME = "R-MULTIFRAME"
    const val UID_CONFLICT = "R-UID-CONFLICT"
    const val GEOM_MISSING = "R-GEOM-MISSING"
    const val TRANSFER_SYNTAX = "R-TRANSFER-SYNTAX"

    /** 明确不支持解码像素的传输语法（案例包自定义 + 常见压缩占位）。 */
    val unsupportedTransferSyntaxes = setOf(
        "1.2.840.10008.1.2.4.999",
        "1.2.840.10008.1.2.4.998",
        "1.2.840.10008.1.2.4.70", // JPEG Lossless, 仅元数据审阅台不解码
        "1.2.840.10008.1.2.4.50", // JPEG Baseline
        "1.2.840.10008.1.2.5",    // RLE
        "9.9.9.9.999"
    )

    val ruleBasis: Map<String, String> = mapOf(
        ORDER_INSTANCE_NUMBER to "InstanceNumber is a presentation label, never ground truth for spatial ordering; spatial order derives from projection onto the slice normal.",
        DIR_ORTHOGONAL to "Direction cosines must satisfy |row.col| <= orthogonalityTolerance and unit-length tolerance before a normal axis is accepted.",
        LOCAL_SPACING to "A local spacing jump beyond gapTolerance opens a branch rather than re-sampling or silently merging.",
        DUPLICATE_SLICE to "Frames whose projected positions coincide within duplicateToleranceMm are duplicates: all legal candidates are retained.",
        FOR_BRANCH to "Distinct FrameOfReferenceUID values cannot share one volume even when SeriesInstanceUID matches.",
        MULTIFRAME to "Multi-frame objects are interpreted per frame via Shared/PerFrame functional groups, not by a single SOP position.",
        UID_CONFLICT to "The same SOP Instance UID with different bytes is a conflict; both raw sources are retained and flagged.",
        GEOM_MISSING to "Instances without ImagePositionPatient/ImageOrientationPatient stay in place with diagnostics and are excluded from volume geometry.",
        TRANSFER_SYNTAX to "Unsupported transfer syntaxes retain metadata and raw pixel bytes; pixel decoding is refused with a diagnostic, never coerced."
    )
}

data class OrthogonalityCheck(
    val accepted: Boolean,
    val rowNorm: Double,
    val colNorm: Double,
    val dot: Double,
    val tolerance: Double,
    val unitTolerance: Double
)

object GeometryEngine {

    fun checkOrthogonality(row: Vec3, col: Vec3, rs: RuleSet): OrthogonalityCheck {
        val dot = row.dot(col)
        return OrthogonalityCheck(
            accepted = abs(dot) <= rs.orthogonalityTolerance &&
                abs(row.norm() - 1.0) <= rs.unitVectorTolerance &&
                abs(col.norm() - 1.0) <= rs.unitVectorTolerance,
            rowNorm = row.norm(),
            colNorm = col.norm(),
            dot = dot,
            tolerance = rs.orthogonalityTolerance,
            unitTolerance = rs.unitVectorTolerance
        )
    }

    fun issue(id: String, rule: String, severity: String, msg: String,
              frames: List<String> = emptyList(), measured: String? = null, expected: String? = null) =
        GeometryIssue(
            id = stableId("iss", rule, id, frames.joinToString(","), msg),
            ruleId = rule, severity = severity, message = msg,
            frameIds = frames, measured = measured, expected = expected
        )

    /** 从单个实例材料构造解释（逐帧；来源 raw tag 不动）。 */
    fun interpret(material: InstanceMaterial, importId: String, createdAt: String): Interpretation {
        val diagnostics = mutableListOf<GeometryIssue>()
        val evidence = mutableListOf<TagEvidence>()

        fun ev(tag: RawTag?, basis: String?, path: String) {
            if (tag != null) evidence += TagEvidence(tag.tagHex, tag.name, tag.vr, tag.rawValue, basis, path)
        }

        val transferSupported = material.transferSyntaxUid == null ||
            material.transferSyntaxUid !in Rules.unsupportedTransferSyntaxes
        if (!transferSupported) {
            diagnostics += issue(
                "transfer_unsupported_${material.stableId}", Rules.TRANSFER_SYNTAX, "ERROR",
                "Transfer syntax ${material.transferSyntaxUid} is not decodable by this review bench; raw pixel bytes are retained without pixel coercion.",
                measured = material.transferSyntaxUid,
                expected = "uncompressed/little-endian transfer syntax for pixel display"
            )
        }

        // 收集帧：多帧 -> perFrameJson 数量；否则单帧。
        val frameSources: List<FrameSource> = buildFrameSources(material)

        val frames = frameSources.mapIndexed { idx, fs ->
            interpretFrame(material, fs, idx, transferSupported, importId)
        }

        if (material.numberOfFrames != null && material.numberOfFrames > 1) {
            diagnostics += issue(
                "multiframe_${material.stableId}", Rules.MULTIFRAME, "INFO",
                "Multi-frame object with ${material.numberOfFrames} frames interpreted through functional groups.",
                frames.map { it.frameId }
            )
        }

        val interpId = stableId("interp", importId, material.stableId,
            material.tags.joinToString("") { it.rawValue },
            material.perFrameJson?.toString() ?: "")

        // 实例级诊断挂到第一帧的解释之外，单独保留在解释对象内：放入每个缺几何帧不合适，
        // 这里追加到第一个帧（或占位帧）的 diagnostics；前端按 ruleId 聚合。
        val mergedFrames = if (diagnostics.isNotEmpty() && frames.isNotEmpty()) {
            frames.toMutableList().also {
                it[0] = it[0].copy(diagnostics = it[0].diagnostics + diagnostics)
            }
        } else frames

        return Interpretation(interpId, importId, material.stableId, transferSupported, mergedFrames, createdAt)
    }

    private data class FrameSource(
        val frameIndex: Int?,
        val ipp: RawTag?,
        val iop: RawTag?,
        val spacing: RawTag?,
        val rows: RawTag?,
        val cols: RawTag?,
        val pathPrefix: String
    )

    private fun buildFrameSources(m: InstanceMaterial): List<FrameSource> {
        val perFrame = m.perFrameJson
        return if (!perFrame.isNullOrEmpty()) {
            perFrame.mapIndexed { i, pf ->
                FrameSource(
                    frameIndex = i,
                    ipp = jsonTag(pf, "ipp"),
                    iop = jsonTag(pf, "iop"),
                    spacing = jsonTag(pf, "spacing") ?: m.tag("0028", "0030"),
                    rows = m.tag("0028", "0010"),
                    cols = m.tag("0028", "0011"),
                    pathPrefix = "PerFrameFunctionalGroupsSequence[$i]"
                )
            }
        } else {
            listOf(
                FrameSource(
                    frameIndex = null,
                    ipp = m.tag("0020", "0032"),
                    iop = m.tag("0020", "0037"),
                    spacing = m.tag("0028", "0030"),
                    rows = m.tag("0028", "0010"),
                    cols = m.tag("0028", "0011"),
                    pathPrefix = "tags"
                )
            )
        }
    }

    /** perFrame JSON 内嵌的键约定：ipp/iop/spacing 直接复用原始多值字符串。 */
    private fun jsonTag(obj: JsonObject, key: String): RawTag? {
        val prim = obj[key] as? JsonPrimitive ?: return null
        return when (key) {
            "ipp" -> RawTag("0020", "0032", "(0020,0032)", "ImagePositionPatient", "DS", prim.content, splitDs(prim.content))
            "iop" -> RawTag("0020", "0037", "(0020,0037)", "ImageOrientationPatient", "DS", prim.content, splitDs(prim.content))
            "spacing" -> RawTag("0028", "0030", "(0028,0030)", "PixelSpacing", "DS", prim.content, splitDs(prim.content))
            else -> null
        }
    }

    private fun splitDs(s: String): List<String> = s.split('\\').map { it.trim() }.filter { it.isNotEmpty() }

    private fun interpretFrame(
        material: InstanceMaterial, fs: FrameSource, idx: Int,
        transferSupported: Boolean, importId: String
    ): FrameInterpretation {
        val frameId = if (fs.frameIndex != null) "${material.stableId}#f${fs.frameIndex}" else "${material.stableId}#f0"
        val diags = mutableListOf<GeometryIssue>()
        val evs = mutableListOf<TagEvidence>()

        listOf(fs.ipp, fs.iop, fs.spacing, fs.rows, fs.cols).forEach { t ->
            if (t != null) evs += TagEvidence(
                t.tagHex, t.name, t.vr, t.rawValue,
                unitOrBasis = when (t.tagHex) {
                    "(0020,0032)" -> "mm; DICOM Patient Coordinate System"
                    "(0020,0037)" -> "dimensionless direction cosines"
                    "(0028,0030)" -> "mm (row,col spacing at detector)"
                    else -> null
                },
                sourcePath = "${fs.pathPrefix}/${t.tagHex}"
            )
        }

        fun parseFailure(tag: RawTag?): Boolean {
            if (tag == null) return true
            return tag.multiValue?.any { it.toDoubleOrNull() == null } ?: (tag.rawValue.toDoubleOrNull() == null)
        }

        if (fs.ipp == null || fs.iop == null) {
            diags += issue(
                "missing_geom_$frameId", Rules.GEOM_MISSING, "ERROR",
                "Frame lacks ImagePositionPatient/ImageOrientationPatient; original tags kept at source, frame excluded from volume.",
                listOf(frameId)
            )
            return FrameInterpretation(frameId, material.stableId, fs.frameIndex, null, "MISSING_GEOMETRY", diags, evs)
        }

        val ippVals = splitDs(fs.ipp.rawValue)
        val iopVals = splitDs(fs.iop.rawValue)
        val spacingVals = fs.spacing?.let { splitDs(it.rawValue) }

        if (ippVals.size != 3 || iopVals.size != 6 || parseFailure(fs.ipp) || parseFailure(fs.iop) ||
            (fs.spacing != null && (spacingVals?.size != 2 || parseFailure(fs.spacing)))
        ) {
            diags += issue(
                "parse_error_$frameId", Rules.GEOM_MISSING, "ERROR",
                "Geometry tag present but unparseable in original representation; value left in place with diagnostic.",
                listOf(frameId),
                measured = "IPP=${fs.ipp.rawValue} IOP=${fs.iop.rawValue} spacing=${fs.spacing?.rawValue ?: "<absent>"}"
            )
            return FrameInterpretation(frameId, material.stableId, fs.frameIndex, null, "PARSE_ERROR", diags, evs)
        }

        val origin = Vec3(ippVals[0].toDouble(), ippVals[1].toDouble(), ippVals[2].toDouble())
        val row = Vec3(iopVals[0].toDouble(), iopVals[1].toDouble(), iopVals[2].toDouble())
        val col = Vec3(iopVals[3].toDouble(), iopVals[4].toDouble(), iopVals[5].toDouble())
        val spacingRow = spacingVals!![0].toDouble()
        val spacingCol = spacingVals[1].toDouble()

        val rows = fs.rows?.rawValue?.toIntOrNull() ?: 0
        val cols = fs.cols?.rawValue?.toIntOrNull() ?: 0

        // 解释层不做阈值判定：保留测量值（与规则版本无关），正交性在候选层按当版规则集检查。
        val rowNorm = row.norm()
        val colNorm = col.norm()
        val dirDot = row.dot(col)
        val normal = row.cross(col).normalized()
        val plane = SlicePlane(
            frameId = frameId,
            instanceStableId = material.stableId,
            originMm = origin,
            rowDir = row,
            colDir = col,
            normal = normal,
            pixelSpacingRowMm = spacingRow,
            pixelSpacingColMm = spacingCol,
            rows = rows,
            cols = cols,
            frameOfReferenceUid = material.frameOfReferenceUid
        )
        val status = if (transferSupported) "OK" else "UNSUPPORTED_TRANSFER_SYNTAX"
        if (!transferSupported) {
            diags += issue(
                "transfer_frame_$frameId", Rules.TRANSFER_SYNTAX, "ERROR",
                "Geometry retained but pixel decoding refused due to unsupported transfer syntax.",
                listOf(frameId), measured = material.transferSyntaxUid
            )
        }
        return FrameInterpretation(
            frameId, material.stableId, fs.frameIndex, plane,
            rowNorm, colNorm, dirDot, status, diags, evs
        )
    }
}
