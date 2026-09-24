package org.gsb.dicomreview.geom

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gsb.dicomreview.dicom.DicomDataset
import org.gsb.dicomreview.dicom.DicomElement
import org.gsb.dicomreview.dicom.DicomReader
import org.gsb.dicomreview.dicom.DicomTags
import org.gsb.dicomreview.dicom.Tag
import org.gsb.dicomreview.model.EvidenceRef
import org.gsb.dicomreview.model.FrameGeometry
import org.gsb.dicomreview.model.OrientationFrame
import org.gsb.dicomreview.model.Quantity
import org.gsb.dicomreview.model.RawInstance
import org.gsb.dicomreview.model.Vec3
import org.gsb.dicomreview.model.flattenTags

data class ExtractedMaterial(
    val raw: RawInstance,
    val frames: List<FrameGeometry>,
    val fatal: Boolean
)

object GeometryExtractor {

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    fun extract(batchId: String, ordinal: Int, fileName: String, bytes: ByteArray): ExtractedMaterial {
        val parsed = try {
            DicomReader.parse(bytes)
        } catch (t: Throwable) {
            throw MalformedDicomException(fileName, t.message ?: "parse failed")
        }
        val ds = parsed.dataset
        val bytesHash = StableIds.sha256Bytes(bytes)
        val instanceId = StableIds.of("inst", bytesHash)
        val rawTags = flattenTags(ds)
        val numberOfFrames = ds[DicomTags.NumberOfFrames]?.singleString()?.trim()?.toIntOrNull()
        val isMultiframe = numberOfFrames != null && numberOfFrames > 1 ||
            ds.contains(DicomTags.PerFrameFunctionalGroupsSequence)

        val raw = RawInstance(
            instanceId = instanceId,
            batchId = batchId,
            ordinalInBatch = ordinal,
            fileName = fileName,
            sopClassUid = ds[DicomTags.SOPClassUID]?.singleString(),
            sopInstanceUid = ds[DicomTags.SOPInstanceUID]?.singleString(),
            seriesInstanceUid = ds[DicomTags.SeriesInstanceUID]?.singleString(),
            studyInstanceUid = ds[DicomTags.StudyInstanceUID]?.singleString(),
            frameOfReferenceUid = ds[DicomTags.FrameOfReferenceUID]?.singleString(),
            instanceNumber = ds[DicomTags.InstanceNumber]?.singleString()?.trim()?.toIntOrNull(),
            transferSyntaxUid = parsed.transferSyntaxUID,
            transferSyntaxSupported = parsed.parseDiagnostic == null,
            mediaStored = true,
            mediaSopClassUid = ds[DicomTags.SOPClassUID]?.singleString(),
            fatalDiagnostic = parsed.parseDiagnostic,
            rawTagsJson = json.encodeToString(rawTags),
            rawBytesHash = bytesHash,
            rawBytesSize = bytes.size,
            pixelFrameCount = numberOfFrames ?: 1,
            rows = ds[DicomTags.Rows]?.singleString()?.trim()?.toIntOrNull(),
            columns = ds[DicomTags.Columns]?.singleString()?.trim()?.toIntOrNull()
        )

        val frames: List<FrameGeometry> = when {
            parsed.parseDiagnostic != null -> emptyList()
            ds.contains(DicomTags.PerFrameFunctionalGroupsSequence) ->
                extractFunctionalFrames(instanceId, raw, ds)
            else -> listOf(topLevelFrame(instanceId, raw, ds, frameNumber = 1))
        }

        return ExtractedMaterial(raw, frames, fatal = parsed.parseDiagnostic != null)
    }

    private fun topLevelFrame(
        instanceId: String, raw: RawInstance, ds: DicomDataset, frameNumber: Int
    ): FrameGeometry {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val evidence = mutableListOf<EvidenceRef>()

        val ippEl = ds[DicomTags.ImagePositionPatient]
        val iopEl = ds[DicomTags.ImageOrientationPatient]
        val psEl = ds[DicomTags.PixelSpacing]
        ippEl?.let { evidence += EvidenceRef(instanceId, "(0020,0032)", it.tag.toString(), it.singleString() ?: "") }
        iopEl?.let { evidence += EvidenceRef(instanceId, "(0020,0037)", it.tag.toString(), it.singleString() ?: "") }
        psEl?.let { evidence += EvidenceRef(instanceId, "(0028,0030)", it.tag.toString(), it.singleString() ?: "") }

        val pos = readPosition(ippEl, errors, evidence, instanceId)
        val ori = readOrientation(iopEl, errors, warnings, evidence, instanceId)
        val spacing = readSpacing(psEl, errors, evidence, instanceId)

        val usable = pos != null && ori != null && ori.orthogonal && spacing != null
        return FrameGeometry(
            frameNumber = frameNumber,
            instanceId = instanceId,
            sopInstanceUid = raw.sopInstanceUid,
            seriesInstanceUid = raw.seriesInstanceUid,
            frameOfReferenceUid = raw.frameOfReferenceUid,
            instanceNumber = raw.instanceNumber,
            position = pos,
            positionRaw = Quantity.mm(ippEl?.strings()?.getOrNull(0)),
            orientation = ori,
            orientationRaw = iopEl?.doublesOrNull()?.mapIndexed { i, v ->
                Quantity("%.6f".format(v), "1", v)
            },
            rowSpacingMm = spacing?.second,
            colSpacingMm = spacing?.first,
            source = "TOP_LEVEL",
            sourceFrameIndex = 0,
            evidence = evidence,
            errors = errors,
            warnings = warnings,
            usable = usable
        )
    }

    private fun extractFunctionalFrames(
        instanceId: String, raw: RawInstance, ds: DicomDataset
    ): List<FrameGeometry> {
        val perFrame = ds[DicomTags.PerFrameFunctionalGroupsSequence]?.sequenceItems() ?: return emptyList()
        val shared = ds[DicomTags.SharedFunctionalGroupsSequence]?.sequenceItems()?.firstOrNull()
        val sharedMeasures = shared?.get(DicomTags.PixelMeasuresSequence)?.sequenceItems()?.firstOrNull()
        val sharedOrientation = shared?.get(DicomTags.PlaneOrientationSequence)?.sequenceItems()?.firstOrNull()

        return perFrame.mapIndexed { idx, item ->
            val frameNumber = idx + 1
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val evidence = mutableListOf<EvidenceRef>()
            val measures = item[DicomTags.PixelMeasuresSequence]?.sequenceItems()?.firstOrNull() ?: sharedMeasures
            val positionItem = item[DicomTags.PlanePositionSequence]?.sequenceItems()?.firstOrNull()
            val orientationItem = item[DicomTags.PlaneOrientationSequence]?.sequenceItems()?.firstOrNull() ?: sharedOrientation

            val ippEl = positionItem?.get(DicomTags.ImagePositionPatient)
            val iopEl = orientationItem?.get(DicomTags.ImageOrientationPatient)
            val psEl = measures?.get(DicomTags.PixelSpacing)

            val base = "(5200,922E)/[$idx]/"
            ippEl?.let { evidence += EvidenceRef(instanceId, base + "(0020,9113)/(0020,0032)", it.tag.toString(), it.singleString() ?: "", frameNumber) }
            iopEl?.let { evidence += EvidenceRef(instanceId, base + "(0020,9116)/(0020,0037)", it.tag.toString(), it.singleString() ?: "", frameNumber) }
            psEl?.let { evidence += EvidenceRef(instanceId, base + "(0028,9110)/(0028,0030)", it.tag.toString(), it.singleString() ?: "", frameNumber) }

            val pos = readPosition(ippEl, errors, evidence, instanceId, frameNumber)
            val ori = readOrientation(iopEl, errors, warnings, evidence, instanceId, frameNumber)
            val spacing = readSpacing(psEl, errors, evidence, instanceId, frameNumber)
            val usable = pos != null && ori != null && ori.orthogonal && spacing != null

            FrameGeometry(
                frameNumber = frameNumber,
                instanceId = instanceId,
                sopInstanceUid = raw.sopInstanceUid,
                seriesInstanceUid = raw.seriesInstanceUid,
                frameOfReferenceUid = raw.frameOfReferenceUid,
                instanceNumber = raw.instanceNumber,
                position = pos,
                positionRaw = Quantity.mm(ippEl?.strings()?.getOrNull(0)),
                orientation = ori,
                orientationRaw = iopEl?.doublesOrNull()?.map { Quantity("%.6f".format(it), "1", it) },
                rowSpacingMm = spacing?.second,
                colSpacingMm = spacing?.first,
                source = "PER_FRAME_FUNCTIONAL_GROUP",
                sourceFrameIndex = idx,
                evidence = evidence,
                errors = errors,
                warnings = warnings,
                usable = usable
            )
        }
    }

    private fun readPosition(
        el: DicomElement?,
        errors: MutableList<String>,
        evidence: MutableList<EvidenceRef>,
        instanceId: String,
        frame: Int? = null
    ): Vec3? {
        if (el == null) {
            errors += "MISSING_GEOMETRY: ImagePositionPatient absent"
            return null
        }
        val nums = el.strings().map { it.trim() }
        if (nums.size != 3) {
            errors += "MISSING_GEOMETRY: ImagePositionPatient value multiplicity ${nums.size} != 3; raw retained at source"
            return null
        }
        val parsed = nums.map { it.replace(" ", "").toDoubleOrNull() }
        if (parsed.any { it == null }) {
            errors += "UNCONVERTIBLE_VALUE: ImagePositionPatient '${el.singleString() ?: ""}' kept in place, not used for layout"
            evidence += EvidenceRef(instanceId, el.tag.toString(), el.tag.toString(), el.singleString() ?: "", frame)
            return null
        }
        return Vec3(parsed[0]!!, parsed[1]!!, parsed[2]!!)
    }

    private fun readOrientation(
        el: DicomElement?,
        errors: MutableList<String>,
        warnings: MutableList<String>,
        evidence: MutableList<EvidenceRef>,
        instanceId: String,
        frame: Int? = null
    ): OrientationFrame? {
        if (el == null) {
            errors += "MISSING_GEOMETRY: ImageOrientationPatient absent"
            return null
        }
        val nums = el.strings().map { it.trim() }
        if (nums.size != 6) {
            errors += "MISSING_GEOMETRY: ImageOrientationPatient VM ${nums.size} != 6; raw retained"
            return null
        }
        val d = nums.map { it.replace(" ", "").toDoubleOrNull() }
        if (d.any { it == null }) {
            errors += "UNCONVERTIBLE_VALUE: ImageOrientationPatient '${el.singleString() ?: ""}' kept in place"
            return null
        }
        val row = Vec3(d[0]!!, d[1]!!, d[2]!!)
        val col = Vec3(d[3]!!, d[4]!!, d[5]!!)
        val rowNorm = row.norm()
        val colNorm = col.norm()
        val dot = row.dot(col)
        val normal = row.cross(col).normalized()
        val orthogonal = kotlin.math.abs(dot) <= 0.01 &&
            kotlin.math.abs(rowNorm - 1.0) <= 0.001 &&
            kotlin.math.abs(colNorm - 1.0) <= 0.001
        if (!orthogonal) warnings += "DIRECTION_NONORTHOGONAL: row.col=$dot rowNorm=$rowNorm colNorm=$colNorm"
        return OrientationFrame(row, col, normal, orthogonal, rowNorm, colNorm, dot)
    }

    private fun readSpacing(
        el: DicomElement?,
        errors: MutableList<String>,
        evidence: MutableList<EvidenceRef>,
        instanceId: String,
        frame: Int? = null
    ): Pair<Quantity, Quantity>? {
        if (el == null) {
            errors += "MISSING_GEOMETRY: PixelSpacing absent"
            return null
        }
        val nums = el.strings().map { it.trim() }
        if (nums.size != 2) {
            errors += "MISSING_GEOMETRY: PixelSpacing VM ${nums.size} != 2; raw retained"
            return null
        }
        val row = Quantity.mm(nums[0])
        val col = Quantity.mm(nums[1])
        if (row?.normalizedMm == null || col?.normalizedMm == null) {
            errors += "UNCONVERTIBLE_VALUE: PixelSpacing '${el.singleString() ?: ""}' kept in place, not normalized"
            return null
        }
        return row to col
    }
}

class MalformedDicomException(val fileName: String, reason: String) :
    RuntimeException("rejected $fileName: $reason")
