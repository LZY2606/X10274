package org.research.gsb.dicom.geometry

import org.research.gsb.dicom.codec.DicomDataset
import org.research.gsb.dicom.codec.DicomFile
import org.research.gsb.dicom.codec.Tag
import org.research.gsb.dicom.codec.Tags
import org.research.gsb.dicom.model.SliceGeometry
import org.research.gsb.dicom.model.TagEvidence

/**
 * Expands one parsed DICOM object into one or more [SliceGeometry] values. Multi-frame
 * enhanced objects read geometry from SharedFunctionalGroupsSequence and
 * PerFrameFunctionalGroupsSequence; classic images use top-level tags.
 */
class SliceExtractor(private val tolerance: Double) {

    fun extract(file: DicomFile, instanceKey: String): List<SliceGeometry> {
        val ds = file.dataset
        val numberOfFrames = ds.int(Tags.NumberOfFrames)
        val shared = ds.sequenceItem(Tags.SharedFunctionalGroupsSequence)
        val perFrames = ds[Tags.PerFrameFunctionalGroupsSequence]?.items ?: emptyList()

        return if (numberOfFrames != null || shared != null || perFrames.isNotEmpty()) {
            val frames = if (numberOfFrames != null) numberOfFrames else perFrames.size
            (0 until frames).map { frame ->
                buildMultiFrame(ds, shared, perFrames, frame, frames, instanceKey)
            }
        } else {
            listOf(buildClassic(ds, instanceKey))
        }
    }

    private fun buildClassic(ds: DicomDataset, key: String): SliceGeometry {
        val evidence = mutableListOf<TagEvidence>()
        collectEvidence(ds, null, -1, evidence)
        val iop = ds.doubles(Tags.ImageOrientationPatient)?.map { it?.toString() ?: "" }
        val ipp = ds.doubles(Tags.ImagePositionPatient)?.map { it?.toString() ?: "" }
        val spacing = ds.doubles(Tags.PixelSpacing)
        val diagnostics = mutableListOf<String>()
        if (ds.string(Tags.ImageOrientationPatient) == null) diagnostics += "Missing ImageOrientationPatient"
        if (ds.string(Tags.ImagePositionPatient) == null) diagnostics += "Missing ImagePositionPatient"
        if (ds.string(Tags.PixelSpacing) == null) diagnostics += "Missing PixelSpacing"
        return SliceGeometry(
            instanceKey = key,
            sopInstanceUid = requireUid(ds, Tags.SOPInstanceUID, diagnostics),
            seriesUid = requireUid(ds, Tags.SeriesInstanceUID, diagnostics),
            frameOfReferenceUid = ds.string(Tags.FrameOfReferenceUID),
            frameIndex = -1,
            instanceNumberRaw = ds.string(Tags.InstanceNumber),
            position = OrientationInterpreter.position(ipp),
            orientation = OrientationInterpreter.interpret(
                iop?.take(3), iop?.drop(3)?.take(3), tolerance),
            rowSpacingMm = spacing?.getOrNull(0)?.let { org.research.gsb.dicom.codec.DicomElement.fmt(it) },
            colSpacingMm = spacing?.getOrNull(1)?.let { org.research.gsb.dicom.codec.DicomElement.fmt(it) },
            sliceThicknessRaw = ds.string(Tags.SliceThickness),
            spacingBetweenSlicesRaw = ds.string(Tags.SpacingBetweenSlices),
            pixelRows = ds.int(Tags.Rows),
            pixelColumns = ds.int(Tags.Columns),
            evidence = evidence,
            diagnostics = diagnostics,
        )
    }

    private fun buildMultiFrame(
        ds: DicomDataset,
        shared: DicomDataset?,
        perFrames: List<DicomDataset>,
        frame: Int,
        totalFrames: Int,
        key: String,
    ): SliceGeometry {
        val evidence = mutableListOf<TagEvidence>()
        val diagnostics = mutableListOf<String>()
        val perFrame = perFrames.getOrNull(frame)
        if (perFrame == null && frame >= perFrames.size && totalFrames > perFrames.size) {
            diagnostics += "Frame $frame has no PerFrameFunctionalGroupsSequence item"
        }

        // Pixel measures + orientation from shared FG (may be overridden per frame).
        val sharedMeasures = shared?.sequenceItem(Tags.PixelMeasuresSequence)
        val frameMeasures = perFrame?.sequenceItem(Tags.PixelMeasuresSequence)
        val sharedOrient = shared?.sequenceItem(Tags.PlaneOrientationSequence)
        val frameOrient = perFrame?.sequenceItem(Tags.PlaneOrientationSequence)
        val framePosition = perFrame?.sequenceItem(Tags.PlanePositionSequence)

        collectEvidence(ds, null, frame, evidence, topLevelOnly = true)
        collectEvidence(shared, "SHARED_FG", frame, evidence)
        collectEvidence(perFrame, "PERFRAME_FG[$frame]", frame, evidence)

        val iopDs = frameOrient ?: sharedOrient
        val iopRaw = iopDs?.string(Tags.ImageOrientationPatient)
        val iop = iopDs?.doubles(Tags.ImageOrientationPatient)?.map { it?.toString() ?: "" }
        val ippRaw = framePosition?.string(Tags.ImagePositionPatient)
        val ipp = framePosition?.doubles(Tags.ImagePositionPatient)?.map { it?.toString() ?: "" }
        val spacingDs = frameMeasures ?: sharedMeasures
        val spacing = spacingDs?.doubles(Tags.PixelSpacing)

        if (iopRaw == null) diagnostics += "Frame $frame: no ImageOrientationPatient in functional groups"
        if (ippRaw == null) diagnostics += "Frame $frame: no ImagePositionPatient in functional groups"
        if (spacingDs?.string(Tags.PixelSpacing) == null)
            diagnostics += "Frame $frame: no PixelSpacing in PixelMeasuresSequence"

        return SliceGeometry(
            instanceKey = if (totalFrames > 1) "$key#f$frame" else key,
            sopInstanceUid = requireUid(ds, Tags.SOPInstanceUID, diagnostics),
            seriesUid = requireUid(ds, Tags.SeriesInstanceUID, diagnostics),
            frameOfReferenceUid = ds.string(Tags.FrameOfReferenceUID),
            frameIndex = frame,
            instanceNumberRaw = ds.string(Tags.InstanceNumber),
            position = OrientationInterpreter.position(ipp),
            orientation = OrientationInterpreter.interpret(
                iop?.take(3), iop?.drop(3)?.take(3), tolerance),
            rowSpacingMm = spacing?.getOrNull(0)?.let { org.research.gsb.dicom.codec.DicomElement.fmt(it) },
            colSpacingMm = spacing?.getOrNull(1)?.let { org.research.gsb.dicom.codec.DicomElement.fmt(it) },
            sliceThicknessRaw = spacingDs?.string(Tags.SliceThickness),
            spacingBetweenSlicesRaw = spacingDs?.string(Tags.SpacingBetweenSlices),
            pixelRows = ds.int(Tags.Rows),
            pixelColumns = ds.int(Tags.Columns),
            evidence = evidence,
            diagnostics = diagnostics,
        )
    }

    private fun requireUid(ds: DicomDataset, tag: Tag, diagnostics: MutableList<String>): String {
        val v = ds.string(tag)
        if (v == null) diagnostics += "Missing $tag"
        return v ?: "MISSING_${tag}"
    }

    private fun collectEvidence(
        ds: DicomDataset?,
        source: String?,
        frame: Int,
        out: MutableList<TagEvidence>,
        topLevelOnly: Boolean = false,
    ) {
        if (ds == null) return
        for (el in ds.elements) {
            val rendered = when {
                el.vr.code == "SQ" -> "Sequence(${el.items.size})"
                el.raw.size > 128 -> "<${el.vr.code} ${el.raw.size} bytes>"
                else -> el.stringValue()
            }
            out += TagEvidence(
                tag = el.tag.toString(),
                name = TagNames.nameOf(el.tag),
                vr = el.vr.code,
                rawValue = rendered,
                source = source ?: "INSTANCE",
                frameIndex = frame,
            )
            if (!topLevelOnly && el.vr.code == "SQ") {
                el.items.forEachIndexed { index, item ->
                    collectEvidence(item, "${source ?: "INSTANCE"}:SQ[$index]", frame, out)
                }
            }
        }
    }
}
