package dicomreview.dicom

/**
 * DICOM 标签字典：只声明本工具需要的 tag。所有 tag 均按 (group, element)
 * 标识，并保留 DICOM 标准关键字，便于审阅界面做“原始 tag 证据”回读。
 */
object Tags {
    data class Tag(val group: Int, val element: Int, val keyword: String, val vr: String) {
        val code: Int get() = (group shl 16) or element
        val hex: String get() = "(%04X,%04X)".format(group, element)
        override fun toString(): String = "$keyword$hex"
    }

    val TransferSyntaxUID = Tag(0x0002, 0x0010, "TransferSyntaxUID", "UI")
    val MediaStorageSOPClassUID = Tag(0x0002, 0x0002, "MediaStorageSOPClassUID", "UI")
    val MediaStorageSOPInstanceUID = Tag(0x0002, 0x0003, "MediaStorageSOPInstanceUID", "UI")

    val SOPClassUID = Tag(0x0008, 0x0016, "SOPClassUID", "UI")
    val SOPInstanceUID = Tag(0x0008, 0x0018, "SOPInstanceUID", "UI")
    val StudyInstanceUID = Tag(0x0020, 0x000D, "StudyInstanceUID", "UI")
    val SeriesInstanceUID = Tag(0x0020, 0x000E, "SeriesInstanceUID", "UI")
    val InstanceNumber = Tag(0x0020, 0x0013, "InstanceNumber", "IS")
    val Modality = Tag(0x0008, 0x0060, "Modality", "CS")

    val ImagePositionPatient = Tag(0x0020, 0x0032, "ImagePositionPatient", "DS")
    val ImageOrientationPatient = Tag(0x0020, 0x0037, "ImageOrientationPatient", "DS")
    val PixelSpacing = Tag(0x0028, 0x0030, "PixelSpacing", "DS")
    val SliceThickness = Tag(0x0018, 0x0050, "SliceThickness", "DS")
    val SpacingBetweenSlices = Tag(0x0018, 0x0088, "SpacingBetweenSlices", "DS")
    val FrameOfReferenceUID = Tag(0x0020, 0x0052, "FrameOfReferenceUID", "UI")

    val SamplesPerPixel = Tag(0x0028, 0x0002, "SamplesPerPixel", "US")
    val Rows = Tag(0x0028, 0x0010, "Rows", "US")
    val Columns = Tag(0x0028, 0x0011, "Columns", "US")
    val BitsAllocated = Tag(0x0028, 0x0100, "BitsAllocated", "US")
    val PixelRepresentation = Tag(0x0028, 0x0103, "PixelRepresentation", "US")
    val NumberOfFrames = Tag(0x0028, 0x0008, "NumberOfFrames", "IS")

    val SharedFunctionalGroupsSequence = Tag(0x5200, 0x9229, "SharedFunctionalGroupsSequence", "SQ")
    val PerFrameFunctionalGroupsSequence = Tag(0x5200, 0x9230, "PerFrameFunctionalGroupsSequence", "SQ")
    val PlanePositionSequence = Tag(0x0020, 0x9113, "PlanePositionSequence", "SQ")
    val PlaneOrientationSequence = Tag(0x0020, 0x9116, "PlaneOrientationSequence", "SQ")
    val PixelMeasuresSequence = Tag(0x0028, 0x9110, "PixelMeasuresSequence", "SQ")
    val PixelData = Tag(0x7FE0, 0x0010, "PixelData", "OW")

    private val byCode: Map<Int, Tag> = buildMap {
        Tags::class.java.declaredFields
            .filter { it.type == Tag::class.java }
            .forEach { f ->
                f.isAccessible = true
                val t = f.get(Tags) as Tag
                put(t.code, t)
            }
    }

    fun lookup(code: Int): Tag = byCode[code]
        ?: Tag((code ushr 16) and 0xFFFF, code and 0xFFFF, "PrivateOrUnknown", "UN")
}
