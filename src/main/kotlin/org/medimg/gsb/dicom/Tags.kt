package org.medimg.gsb.dicom

/**
 * DICOM tag 常量与最小字典。字典仅提供名称与 VR 提示（用于隐式 VR 数据集），
 * 不替代原数据：任何元素的原始 VR、原始字节表示都由 [DicomElement] 原样保留。
 */
object Tags {
    const val MediaStorageSOPClassUID = 0x00020002
    const val MediaStorageSOPInstanceUID = 0x00020003
    const val TransferSyntaxUID = 0x00020010
    const val ImplementationClassUID = 0x00020012

    const val SpecificCharacterSet = 0x00080005
    const val ImageType = 0x00080008
    const val SOPClassUID = 0x00080016
    const val SOPInstanceUID = 0x00080018
    const val StudyDate = 0x00080020
    const val Modality = 0x00080060
    const val SeriesDescription = 0x0008103E

    const val PatientName = 0x00100010
    const val PatientID = 0x00100020
    const val DeidentificationMethod = 0x00120063

    const val StudyInstanceUID = 0x0020000D
    const val SeriesInstanceUID = 0x0020000E
    const val StudyID = 0x00200010
    const val SeriesNumber = 0x00200011
    const val InstanceNumber = 0x00200013
    const val ImagePositionPatient = 0x00200032
    const val ImageOrientationPatient = 0x00200037
    const val FrameOfReferenceUID = 0x00200052

    const val NumberOfFrames = 0x00280008
    const val SamplesPerPixel = 0x00280002
    const val PhotometricInterpretation = 0x00280004
    const val Rows = 0x00280010
    const val Columns = 0x00280011
    const val PixelSpacing = 0x00280030
    const val BitsStored = 0x00280101
    const val BitsAllocated = 0x00280100
    const val PixelRepresentation = 0x00280103
    const val PixelValueTransformationSequence = 0x00289145

    const val FrameType = 0x00089007
    const val PixelData = 0x7FE00010

    const val SharedFunctionalGroups = 0x52009229
    const val PerFrameFunctionalGroups = 0x52009230
    const val PixelMeasures = 0x00289110
    const val PlanePosition = 0x00289120
    const val PlaneOrientation = 0x00289130

    /** tag -> (关键字, 隐式 VR 提示) */
    private val entries: List<Pair<Int, String>> = listOf(
        0x00020000 to "FileMetaInformationGroupLength|UL",
        MediaStorageSOPClassUID to "MediaStorageSOPClassUID|UI",
        MediaStorageSOPInstanceUID to "MediaStorageSOPInstanceUID|UI",
        TransferSyntaxUID to "TransferSyntaxUID|UI",
        ImplementationClassUID to "ImplementationClassUID|UI",
        SpecificCharacterSet to "SpecificCharacterSet|CS",
        ImageType to "ImageType|CS",
        SOPClassUID to "SOPClassUID|UI",
        SOPInstanceUID to "SOPInstanceUID|UI",
        StudyDate to "StudyDate|DA",
        Modality to "Modality|CS",
        SeriesDescription to "SeriesDescription|LO",
        PatientName to "PatientName|PN",
        PatientID to "PatientID|LO",
        DeidentificationMethod to "DeidentificationMethod|LO",
        StudyInstanceUID to "StudyInstanceUID|UI",
        SeriesInstanceUID to "SeriesInstanceUID|UI",
        StudyID to "StudyID|SH",
        SeriesNumber to "SeriesNumber|IS",
        InstanceNumber to "InstanceNumber|IS",
        ImagePositionPatient to "ImagePositionPatient|DS",
        ImageOrientationPatient to "ImageOrientationPatient|DS",
        FrameOfReferenceUID to "FrameOfReferenceUID|UI",
        NumberOfFrames to "NumberOfFrames|IS",
        SamplesPerPixel to "SamplesPerPixel|US",
        PhotometricInterpretation to "PhotometricInterpretation|CS",
        Rows to "Rows|US",
        Columns to "Columns|US",
        PixelSpacing to "PixelSpacing|DS",
        BitsAllocated to "BitsAllocated|US",
        BitsStored to "BitsStored|US",
        PixelRepresentation to "PixelRepresentation|US",
        PixelValueTransformationSequence to "PixelValueTransformationSequence|SQ",
        FrameType to "FrameType|CS",
        SharedFunctionalGroups to "SharedFunctionalGroupsSequence|SQ",
        PerFrameFunctionalGroups to "PerFrameFunctionalGroupsSequence|SQ",
        PixelMeasures to "PixelMeasuresSequence|SQ",
        PlanePosition to "PlanePositionSequence|SQ",
        PlaneOrientation to "PlaneOrientationSequence|SQ",
        PixelData to "PixelData|OW"
    )

    val dictionary: Map<Int, Pair<String, String>> =
        entries.associate { (tag, nv) -> tag to (nv.substringBefore('|') to nv.substringAfter('|')) }

    fun nameOf(tag: Int): String = dictionary[tag]?.first ?: tagHex(tag)

    fun vrHint(tag: Int): String? = dictionary[tag]?.second

    fun tagHex(tag: Int): String = "(%04X,%04X)".format(tag ushr 16 and 0xFFFF, tag and 0xFFFF)
}
