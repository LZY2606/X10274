package dicomreview.derive

import dicomreview.dicom.Tags
import dicomreview.dicom.TransferSyntaxes
import dicomreview.model.*
import kotlin.math.abs

data class ToleranceParams(
    val orthogonalityTolerance: Double = 1e-3,
    val unitLengthTolerance: Double = 1e-3,
    val duplicatePositionToleranceMm: Double = 0.05,
    val localSpacingJumpRatio: Double = 1.5,
    val localSpacingResidualMm: Double = 0.5
) {
    companion object {
        val V1 = ToleranceParams()
    }
    fun toMap() = mapOf(
        "orthogonalityTolerance" to orthogonalityTolerance,
        "unitLengthTolerance" to unitLengthTolerance,
        "duplicatePositionToleranceMm" to duplicatePositionToleranceMm,
        "localSpacingJumpRatio" to localSpacingJumpRatio,
        "localSpacingResidualMm" to localSpacingResidualMm
    )
}

object GeometryExtractor {

    fun extract(file: ParsedFile, fileStableId: String): FileExtraction {
        val diagnostics = file.parseWarnings.toMutableList()
        val tsUnsupported = file.transferSyntaxUID != null &&
            !TransferSyntaxes.isSupportedPixel(file.transferSyntaxUID)
        if (tsUnsupported)
            diagnostics.add("UNSUPPORTED_TRANSFER_SYNTAX: pixel data of ${file.transferSyntaxUID} not decoded; tags preserved")

        val numberOfFrames = file.find(Tags.NumberOfFrames ushr 16, Tags.NumberOfFrames and 0xFFFF)?.rawValue
        val multiframe = (numberOfFrames?.toIntOrNull() ?: 1) > 1
        val sopInstance = file.find(Tags.SOPInstanceUID ushr 16, Tags.SOPInstanceUID and 0xFFFF)?.rawValue
            ?: file.find(0x0002, 0x0003)?.rawValue
        val seriesUID = file.find(Tags.SeriesInstanceUID ushr 16, Tags.SeriesInstanceUID and 0xFFFF)?.rawValue
        val sopClass = file.find(Tags.SOPClassUID ushr 16, Tags.SOPClassUID and 0xFFFF)?.rawValue
            ?: file.mediaStorageSOPClassUID
        val instanceNumber = file.find(Tags.InstanceNumber ushr 16, Tags.InstanceNumber and 0xFFFF)?.rawValue

        val frames = mutableListOf<FrameGeometry>()
        val nFrames = numberOfFrames?.toIntOrNull() ?: 1
        if (multiframe) {
            val sharedPath = "5200,9229/0"
            for (f in 0 until nFrames) {
                val perFramePath = "5200,9230/$f"
                frames += buildFrame(
                    file, "$fileStableId#f$f+1", instanceNumber, multiframe = true,
                    sharedPath = sharedPath, perFramePath = perFramePath,
                    sopInstance = sopInstance, seriesUID = seriesUID, sopClass = sopClass,
                    diagnosticsSeed = diagnostics
                )
            }
        } else {
            frames += buildFrame(
                file, "$fileStableId#f1", instanceNumber, multiframe = false,
                sharedPath = null, perFramePath = "",
                sopInstance = sopInstance, seriesUID = seriesUID, sopClass = sopClass,
                diagnosticsSeed = diagnostics
            )
        }
        return FileExtraction(fileStableId, frames, tsUnsupported, diagnostics.distinct())
    }

    private fun buildFrame(
        file: ParsedFile, frameKey: String, instanceNumber: String?, multiframe: Boolean,
        sharedPath: String?, perFramePath: String,
        sopInstance: String?, seriesUID: String?, sopClass: String?,
        diagnosticsSeed: List<String>
    ): FrameGeometry {
        val missing = mutableListOf<String>()
        val diags = mutableListOf<String>()

        // ImagePositionPatient
        var ippPath: String? = null
        var ipp: Vec3? = null
        run {
            val candidates = frameTagCandidates(file, perFramePath, sharedPath,
                Tags.ImagePositionPatient, listOf("0020,9113/0", "0020,9113/0"))
            val tag = candidates.firstOrNull()
            if (tag == null) { missing += "ImagePositionPatient"; diags += "MISSING_GEOMETRY:ImagePositionPatient" }
            else {
                ippPath = tag.path
                ipp = parseVec3(tag, diags, "ImagePositionPatient")
                if (ipp == null) missing += "ImagePositionPatient"
            }
        }

        // ImageOrientationPatient (6 doubles)
        var iopPath: String? = null
        var iop: List<Double>? = null
        run {
            val tag = frameTagCandidates(file, perFramePath, sharedPath,
                Tags.ImageOrientationPatient, listOf("0020,9116/0", "0020,9116/0")).firstOrNull()
            if (tag == null) { missing += "ImageOrientationPatient"; diags += "MISSING_GEOMETRY:ImageOrientationPatient" }
            else {
                iopPath = tag.path
                val parsed = parseDoubles(tag, diags, "ImageOrientationPatient")
                if (parsed == null || parsed.size != 6) {
                    missing += "ImageOrientationPatient"
                    if (parsed != null) diags += "UNCONVERTIBLE:ImageOrientationPatient expected 6 values, got ${parsed.size}"
                } else iop = parsed
            }
        }

        // PixelSpacing (row\col)
        var spacingPath: String? = null
        var rowSpacing: Quantity? = null; var colSpacing: Quantity? = null
        run {
            val tag = frameTagCandidates(file, perFramePath, sharedPath,
                Tags.PixelSpacing, listOf("0028,9110/0", "0028,9110/0")).firstOrNull()
            if (tag == null) { missing += "PixelSpacing"; diags += "MISSING_GEOMETRY:PixelSpacing" }
            else {
                spacingPath = tag.path
                val v = parseDoubles(tag, diags, "PixelSpacing")
                if (v == null || v.size != 2) {
                    missing += "PixelSpacing"
                    diags += "UNCONVERTIBLE:PixelSpacing remains at ${tag.tagHex} path='${tag.path}' raw='${tag.rawValue}'"
                } else {
                    rowSpacing = Quantity(v[0], "mm", tag.tagHex, tag.rawValue)
                    colSpacing = Quantity(v[1], "mm", tag.tagHex, tag.rawValue)
                }
            }
        }

        var thickness: Quantity? = null
        run {
            val tag = frameTagCandidates(file, perFramePath, sharedPath,
                Tags.SliceThickness, listOf("0028,9110/0", "0028,9110/0")).firstOrNull()
            if (tag != null) {
                val v = parseDoubles(tag, diags, "SliceThickness")?.firstOrNull()
                if (v != null) thickness = Quantity(v, "mm", tag.tagHex, tag.rawValue)
                else diags += "UNCONVERTIBLE:SliceThickness raw='${tag.rawValue}' preserved at ${tag.tagHex}"
            }
        }

        // FrameOfReferenceUID: per-frame functional group, then top-level
        val foTag = frameTagCandidates(file, perFramePath, sharedPath,
            Tags.FrameOfReferenceUID, listOf("0020,9116/0", "0020,9116/0")).firstOrNull()
            ?: file.find(Tags.FrameOfReferenceUID ushr 16, Tags.FrameOfReferenceUID and 0xFFFF, "")
        val foUID = foTag?.rawValue?.takeIf { it.isNotBlank() }
        if (foUID == null) { missing += "FrameOfReferenceUID"; diags += "MISSING_GEOMETRY:FrameOfReferenceUID" }

        val fgPath = when {
            multiframe && perFramePath.isNotEmpty() -> "$perFramePath (per-frame) / $sharedPath (shared)"
            multiframe -> sharedPath
            else -> null
        }

        return FrameGeometry(
            frameKey = frameKey,
            instanceNumberRaw = instanceNumber,
            imagePosition = ipp,
            imageOrientation = iop,
            rowSpacing = rowSpacing,
            colSpacing = colSpacing,
            sliceThickness = thickness,
            frameOfReferenceUID = foUID,
            sopClassUID = sopClass,
            sopInstanceUID = sopInstance,
            seriesUID = seriesUID,
            multiframe = multiframe,
            functionalGroupPath = fgPath,
            missing = missing.distinct(),
            diagnostics = diags.distinct()
        )
    }

    /**
     * Search order: functional-group item nested under the per-frame item,
     * then under the shared item, then top-level dataset.
     */
    private fun frameTagCandidates(
        file: ParsedFile, perFramePath: String?, sharedPath: String?, tag: Int,
        nested: List<String>
    ): List<RawTag> {
        val g = tag ushr 16; val e = tag and 0xFFFF
        val result = mutableListOf<RawTag>()
        if (perFramePath != null && perFramePath.isNotEmpty())
            file.find(g, e, "$perFramePath/${nested[0]}")?.let(result::add)
        if (sharedPath != null)
            file.find(g, e, "$sharedPath/${nested[1]}")?.let(result::add)
        file.find(g, e, "")?.let(result::add)
        return result
    }

    fun parseDoubles(tag: RawTag, diags: MutableList<String>, label: String): List<Double>? {
        val parts = tag.rawValue.split("\\")
        val out = mutableListOf<Double>()
        for (p in parts) {
            val v = p.trim().toDoubleOrNull()
            if (v == null) {
                diags += "UNCONVERTIBLE:$label raw='${tag.rawValue}' at ${tag.tagHex} path='${tag.path}' left in place"
                return null
            }
            out += v
        }
        return out
    }

    fun parseVec3(tag: RawTag, diags: MutableList<String>, label: String): Vec3? {
        val v = parseDoubles(tag, diags, label) ?: return null
        if (v.size != 3) {
            diags += "UNCONVERTIBLE:$label expected 3 values, got ${v.size}, raw='${tag.rawValue}'"
            return null
        }
        return Vec3(v[0], v[1], v[2])
    }

    fun checkOrientation(iop: List<Double>, tol: ToleranceParams): OrientationCheck {
        val row = Vec3(iop[0], iop[1], iop[2])
        val col = Vec3(iop[3], iop[4], iop[5])
        val rowDotCol = row.dot(col)
        val rowNorm = row.norm()
        val colNorm = col.norm()
        val orthogonal = abs(rowDotCol) <= tol.orthogonalityTolerance
        val unitLength = abs(rowNorm - 1.0) <= tol.unitLengthTolerance &&
            abs(colNorm - 1.0) <= tol.unitLengthTolerance
        val reasons = mutableListOf<String>()
        if (!orthogonal) reasons += "NON_ORTHOGONAL: row·col=$rowDotCol exceeds tolerance ${tol.orthogonalityTolerance}"
        if (!unitLength) reasons += "DIRECTION_NOT_UNIT: |row|=$rowNorm |col|=$colNorm tolerance ${tol.unitLengthTolerance}"
        return OrientationCheck(rowDotCol, rowNorm, colNorm,
            tol.orthogonalityTolerance, orthogonal, unitLength,
            orthogonal && unitLength, reasons)
    }

    /** Slice normal (cross product), defined only when orientation is valid. */
    fun sliceNormal(iop: List<Double>): Vec3 {
        val row = Vec3(iop[0], iop[1], iop[2])
        val col = Vec3(iop[3], iop[4], iop[5])
        return Vec3(
            row.y * col.z - row.z * col.y,
            row.z * col.x - row.x * col.z,
            row.x * col.y - row.y * col.x
        ).normalized()
    }
}

data class FileExtraction(
    val fileStableId: String,
    val frames: List<FrameGeometry>,
    val unsupportedTransferSyntax: Boolean,
    val diagnostics: List<String>
)
