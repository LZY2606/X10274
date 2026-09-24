package dicomgeo.dicom

data class Tag(val group: Int, val element: Int) {
    override fun toString(): String {
        fun h(v: Int) = "%04X".format(v)
        return "(${h(group)},${h(element)})"
    }
    val code: Int get() = (group shl 16) or (element and 0xFFFF)
    companion object {
        fun parse(s: String): Tag {
            val t = s.trim().trim('(', ')', ' ')
            val g = t.substring(0, 4).toInt(16)
            val e = t.substring(4).trimStart(',').substring(0, 4).toInt(16)
            return Tag(g, e)
        }
    }
}

object DicomTags {
    val TransferSyntaxUID = Tag(0x0002, 0x0010)
    val MediaStorageSOPClassUID = Tag(0x0002, 0x0002)
    val MediaStorageSOPInstanceUID = Tag(0x0002, 0x0003)
    val ImplementationVersionName = Tag(0x0002, 0x0012)
    val SourceApplicationEntityTitle = Tag(0x0002, 0x0016)

    val SpecificCharacterSet = Tag(0x0008, 0x0005)
    val SOPClassUID = Tag(0x0008, 0x0016)
    val SOPInstanceUID = Tag(0x0008, 0x0018)
    val StudyInstanceUID = Tag(0x0020, 0x000D)
    val SeriesInstanceUID = Tag(0x0020, 0x000E)
    val Modality = Tag(0x0008, 0x0060)
    val Rows = Tag(0x0028, 0x0010)
    val Columns = Tag(0x0028, 0x0011)
    val BitsAllocated = Tag(0x0028, 0x0100)
    val SamplesPerPixel = Tag(0x0028, 0x0002)
    val PhotometricInterpretation = Tag(0x0028, 0x0004)
    val PixelRepresentation = Tag(0x0028, 0x0103)
    val ImagePositionPatient = Tag(0x0020, 0x0032)
    val ImageOrientationPatient = Tag(0x0020, 0x0037)
    val PixelSpacing = Tag(0x0028, 0x0030)
    val SliceThickness = Tag(0x0018, 0x0050)
    val SpacingBetweenSlices = Tag(0x0018, 0x0088)
    val FrameOfReferenceUID = Tag(0x0020, 0x0052)
    val InstanceNumber = Tag(0x0020, 0x0013)
    val NumberOfFrames = Tag(0x0028, 0x0008)
    val PixelData = Tag(0x7FE0, 0x0010)

    val DimensionIndexValues = Tag(0x0020, 0x9157)
    val PlanePositionSequence = Tag(0x0048, 0x021A)
    val PlaneOrientationSequence = Tag(0x0048, 0x021B)
    val PixelMeasuresSequence = Tag(0x0028, 0x9110)
    val PixelValueTransformationSequence = Tag(0x0028, 0x9145)
    val FrameContentSequence = Tag(0x0020, 0x9111)

    val SharedFunctionalGroupsSequence = Tag(0x5200, 0x9229)
    val PerFrameFunctionalGroupsSequence = Tag(0x5200, 0x9230)

    val PatientName = Tag(0x0010, 0x0010)
    val PatientID = Tag(0x0010, 0x0020)
    val StudyID = Tag(0x0008, 0x0020)
    val SeriesNumber = Tag(0x0020, 0x0011)
}
