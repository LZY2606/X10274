package org.research.gsb.dicom.geometry

import org.research.gsb.dicom.codec.Tag
import org.research.gsb.dicom.codec.Tags

object TagNames {
    private val names = mapOf(
        Tags.SOPClassUID to "SOP Class UID",
        Tags.SOPInstanceUID to "SOP Instance UID",
        Tags.StudyInstanceUID to "Study Instance UID",
        Tags.SeriesInstanceUID to "Series Instance UID",
        Tags.InstanceNumber to "Instance Number",
        Tags.FrameOfReferenceUID to "Frame of Reference UID",
        Tags.ImagePositionPatient to "Image Position (Patient)",
        Tags.ImageOrientationPatient to "Image Orientation (Patient)",
        Tags.PixelSpacing to "Pixel Spacing",
        Tags.SliceThickness to "Slice Thickness",
        Tags.SpacingBetweenSlices to "Spacing Between Slices",
        Tags.Rows to "Rows",
        Tags.Columns to "Columns",
        Tags.NumberOfFrames to "Number of Frames",
        Tags.SharedFunctionalGroupsSequence to "Shared Functional Groups Sequence",
        Tags.PerFrameFunctionalGroupsSequence to "Per-Frame Functional Groups Sequence",
        Tags.PixelMeasuresSequence to "Pixel Measures Sequence",
        Tags.PlanePositionSequence to "Plane Position Sequence",
        Tags.PlaneOrientationSequence to "Plane Orientation Sequence",
        Tags.PixelData to "Pixel Data",
        Tags.TransferSyntaxUID to "Transfer Syntax UID",
        Tags.BitsAllocated to "Bits Allocated",
        Tags.SamplesPerPixel to "Samples per Pixel",
        Tags.PhotometricInterpretation to "Photometric Interpretation",
        Tags.PixelRepresentation to "Pixel Representation",
    )
    fun nameOf(tag: Tag): String = names[tag] ?: ""
}
