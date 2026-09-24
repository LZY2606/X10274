package org.research.gsb.dicom.codec

/** DICOM value representations supported by the bench (subset of PS3.5). */
enum class VR(val code: String, val longHeader: Boolean) {
    CS("CS", false),
    DA("DA", false),
    DS("DS", false),
    FD("FD", false),
    FL("FL", false),
    IS("IS", false),
    LO("LO", false),
    LT("LT", false),
    PN("PN", false),
    SH("SH", false),
    SL("SL", false),
    SS("SS", false),
    TM("TM", false),
    UI("UI", false),
    UL("UL", false),
    US("US", false),
    SQ("SQ", false),
    OW("OW", false),
    OB("OB", true),
    OD("OD", true),
    OF("OF", true),
    UN("UN", true);

    companion object {
        fun from(code: String): VR = entries.firstOrNull { it.code == code }
            ?: throw DicomSyntaxException("Unsupported VR '$code'")
    }
}

class DicomSyntaxException(message: String) : RuntimeException(message)
