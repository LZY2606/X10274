package org.research.gsb.dicom.codec

/** In-memory DICOM dataset: preserves the original tag set and raw representations. */
class DicomDataset(elements: List<DicomElement> = emptyList()) {
    private val byTag = LinkedHashMap<Tag, DicomElement>()
    init { elements.forEach { byTag[it.tag] = it } }

    val elements: List<DicomElement> get() = byTag.values.toList()
    operator fun get(tag: Tag): DicomElement? = byTag[tag]
    fun contains(tag: Tag): Boolean = byTag.containsKey(tag)
    fun string(tag: Tag): String? = byTag[tag]?.stringValue()
    fun int(tag: Tag): Int? = byTag[tag]?.intValue()
    fun doubles(tag: Tag): List<Double?>? = byTag[tag]?.doubles()

    fun put(element: DicomElement): DicomDataset {
        byTag[element.tag] = element
        return this
    }

    fun remove(tag: Tag): DicomDataset {
        byTag.remove(tag)
        return this
    }

    /** First item of a sequence tag, if present. */
    fun sequenceItem(tag: Tag): DicomDataset? = byTag[tag]?.items?.firstOrNull()

    /** Merge: values from [other] override values here (used for functional groups). */
    fun mergedWith(other: DicomDataset?): DicomDataset {
        if (other == null) return this
        val ds = DicomDataset(elements)
        other.elements.forEach { ds.put(it) }
        return ds
    }

    /** Ordered list of (tag, raw textual repr) for evidence display. */
    fun evidenceRows(): List<EvidenceRow> = byTag.values.map { el ->
        EvidenceRow(
            tag = el.tag.toString(),
            vr = el.vr.code,
            value = renderValue(el),
        )
    }

    private fun renderValue(el: DicomElement): String = when (el.vr) {
        VR.SQ -> "Sequence(${el.items.size} item${if (el.items.size == 1) "" else "s"})"
        VR.OB, VR.OW, VR.OD, VR.OF, VR.UN -> "<${el.vr.code} ${el.raw.size} bytes>"
        else -> el.stringValue()
    }
}

data class EvidenceRow(val tag: String, val vr: String, val value: String)
