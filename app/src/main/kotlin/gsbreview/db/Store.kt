package gsbreview.db

import gsbreview.dicom.FrameFunctionalGroup
import gsbreview.dicom.RawTag
import java.nio.file.Path
import java.sql.Connection

class Store(val connection: Connection) {

    fun insertImportBatch(batchId: String, caseId: String?, createdAt: String, note: String?, stage: String) {
        connection.prepareStatement(
            "INSERT INTO import_batch(batch_id, case_id, created_at, note) VALUES (?,?,?,?)"
        ).use { ps ->
            ps.setString(1, batchId); ps.setString(2, caseId)
            ps.setString(3, createdAt); ps.setString(4, note); ps.executeUpdate()
        }
    }

    fun insertSource(
        sourceId: String, batchId: String, caseId: String?, relPath: String,
        mediaType: String?, sha: String, bytes: ByteArray, size: Int,
        supersedes: String?, importedAt: String, stage: String
    ) {
        connection.prepareStatement(
            """INSERT INTO source_file
              |(source_id,batch_id,case_id,rel_path,media_type,bytes_sha256,bytes,size_bytes,
              | supersedes_source_id,imported_at,stage)
              |VALUES (?,?,?,?,?,?,?,?,?,?,?)""".trimMargin()
        ).use { ps ->
            ps.setString(1, sourceId); ps.setString(2, batchId); ps.setString(3, caseId)
            ps.setString(4, relPath); ps.setString(5, mediaType)
            ps.setString(6, sha); ps.setBytes(7, bytes); ps.setInt(8, size)
            ps.setString(9, supersedes); ps.setString(10, importedAt); ps.setString(11, stage)
            ps.executeUpdate()
        }
    }

    fun sourceExists(sourceId: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM source_file WHERE source_id=?").use { ps ->
            ps.setString(1, sourceId); ps.executeQuery().use { it.next() }
        }

    fun replaceRawTags(sourceId: String, tags: List<RawTag>) {
        connection.prepareStatement("DELETE FROM raw_tag WHERE source_id=?").use {
            it.setString(1, sourceId); it.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO raw_tag(source_id,tag_path,tag_name,vr,raw_repr,kind,ord) VALUES (?,?,?,?,?,?,?)"
        ).use { ps ->
            for (t in tags) {
                ps.setString(1, sourceId); ps.setString(2, t.path); ps.setString(3, t.name)
                ps.setString(4, t.vr); ps.setString(5, t.rawRepr)
                ps.setString(6, t.kind); ps.setInt(7, t.ord); ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun findSubjectBySopUid(sopUid: String?): String? {
        if (sopUid.isNullOrBlank()) return null
        connection.prepareStatement(
            """SELECT subject_key FROM dicom_instance
              |WHERE subject_key = ? OR subject_key IN
              |(SELECT subject_key FROM dicom_instance WHERE current_source_id IN
              | (SELECT source_id FROM raw_tag WHERE tag_path='(0008,0018)' AND raw_repr=?))"""
                .trimMargin()
        ).use { ps -> ps.setString(1, ""); ps.setString(2, sopUid) }
        // Subjects are keyed deterministically by SOP UID, so compute directly.
        return gsbreview.imports.Ids.subjectKey(sopUid, "").let { key ->
            if (subjectExists(key)) key else null
        }
    }

    fun subjectExists(subjectKey: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM dicom_instance WHERE subject_key=?").use { ps ->
            ps.setString(1, subjectKey); ps.executeQuery().use { it.next() }
        }

    fun insertSubject(
        subjectKey: String, firstSourceId: String, currentSourceId: String, batchId: String,
        multiframe: Boolean, nFrames: Int, sopClassUid: String?, tsUid: String?,
        tsSupported: Boolean, hasGeometry: Boolean
    ) {
        connection.prepareStatement(
            """INSERT INTO dicom_instance
              |(subject_key,first_seen_source_id,current_source_id,current_batch_id,is_multiframe,
              | n_frames,sop_class_uid,transfer_syntax_uid,transfer_syntax_supported,has_geometry)
              |VALUES (?,?,?,?,?,?,?,?,?,?)""".trimMargin()
        ).use { ps ->
            ps.setString(1, subjectKey); ps.setString(2, firstSourceId)
            ps.setString(3, currentSourceId); ps.setString(4, batchId)
            ps.setInt(5, if (multiframe) 1 else 0); ps.setInt(6, nFrames)
            ps.setString(7, sopClassUid); ps.setString(8, tsUid)
            ps.setInt(9, if (tsSupported) 1 else 0); ps.setInt(10, if (hasGeometry) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun updateSubjectCurrentSource(
        subjectKey: String, sourceId: String, batchId: String, multiframe: Boolean,
        nFrames: Int, sopClassUid: String?, tsUid: String?, tsSupported: Boolean, hasGeometry: Boolean
    ) {
        connection.prepareStatement(
            """UPDATE dicom_instance SET current_source_id=?, current_batch_id=?, is_multiframe=?,
              |n_frames=?, sop_class_uid=?, transfer_syntax_uid=?, transfer_syntax_supported=?,
              |has_geometry=? WHERE subject_key=?""".trimMargin()
        ).use { ps ->
            ps.setString(1, sourceId); ps.setString(2, batchId)
            ps.setInt(3, if (multiframe) 1 else 0); ps.setInt(4, nFrames)
            ps.setString(5, sopClassUid); ps.setString(6, tsUid)
            ps.setInt(7, if (tsSupported) 1 else 0); ps.setInt(8, if (hasGeometry) 1 else 0)
            ps.setString(9, subjectKey); ps.executeUpdate()
        }
    }

    fun replaceFrames(subjectKey: String, frames: List<FrameFunctionalGroup>) {
        connection.prepareStatement("DELETE FROM instance_frame WHERE subject_key=?").use {
            it.setString(1, subjectKey); it.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO instance_frame(frame_key,subject_key,frame_no,functional_group) VALUES (?,?,?,?)"
        ).use { ps ->
            frames.forEach { f ->
                val fk = gsbreview.imports.Ids.frameKey(subjectKey, f.frameNo)
                ps.setString(1, fk); ps.setString(2, subjectKey)
                ps.setObject(3, f.frameNo); ps.setString(4, null); ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun currentTagValues(subjectKey: String): Map<String, String> {
        connection.prepareStatement(
            """SELECT tag_path, raw_repr FROM raw_tag
              |WHERE source_id=(SELECT current_source_id FROM dicom_instance WHERE subject_key=?)
              |AND kind IN ('tag','pixel')""".trimMargin()
        ).use { ps ->
            ps.setString(1, subjectKey)
            ps.executeQuery().use { rs ->
                val map = LinkedHashMap<String, String>()
                while (rs.next()) map[rs.getString(1)] = rs.getString(2)
                return map
            }
        }
    }

    fun sourceIdForSubject(subjectKey: String): String =
        connection.prepareStatement("SELECT current_source_id FROM dicom_instance WHERE subject_key=?").use { ps ->
            ps.setString(1, subjectKey)
            ps.executeQuery().use { require(it.next()) { "subject not found" }; it.getString(1) }
        }

    fun allSubjects(): List<SubjectRow> {
        connection.prepareStatement(
            """SELECT subject_key,current_source_id,current_batch_id,is_multiframe,n_frames,
              |sop_class_uid,transfer_syntax_uid,transfer_syntax_supported,has_geometry
              |FROM dicom_instance ORDER BY subject_key""".trimMargin()
        ).use { st ->
            st.executeQuery().use { rs ->
                val out = mutableListOf<SubjectRow>()
                while (rs.next()) out += SubjectRow(
                    subjectKey = rs.getString(1),
                    currentSourceId = rs.getString(2),
                    currentBatchId = rs.getString(3),
                    multiframe = rs.getInt(4) == 1,
                    nFrames = rs.getInt(5),
                    sopClassUid = rs.getString(6),
                    transferSyntaxUid = rs.getString(7),
                    transferSyntaxSupported = rs.getInt(8) == 1,
                    hasGeometry = rs.getInt(9) == 1
                )
                return out
            }
        }
    }

    fun framesForSubject(subjectKey: String): List<Int?> =
        connection.prepareStatement("SELECT frame_no FROM instance_frame WHERE subject_key=? ORDER BY frame_no")
            .use { ps ->
                ps.setString(1, subjectKey)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Int?>()
                    while (rs.next()) out += rs.getObject(1) as Int?
                    out
                }
            }

    fun sourceBytes(sourceId: String): ByteArray =
        connection.prepareStatement("SELECT bytes FROM source_file WHERE source_id=?").use { ps ->
            ps.setString(1, sourceId)
            ps.executeQuery().use { require(it.next()); it.getBytes(1) }
        }

    fun sourceMeta(sourceId: String): SourceMeta? =
        connection.prepareStatement(
            """SELECT source_id,batch_id,case_id,rel_path,media_type,bytes_sha256,size_bytes,
              |supersedes_source_id,imported_at,stage FROM source_file WHERE source_id=?""".trimMargin()
        ).use { ps ->
            ps.setString(1, sourceId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else SourceMeta(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8),
                    rs.getString(9), rs.getString(10)
                )
            }
        }

    fun rollbackBatch(batchId: String) {
        connection.prepareStatement("UPDATE import_batch SET rolled_back=1 WHERE batch_id=?").use {
            it.setString(1, batchId); it.executeUpdate()
        }
    }

    fun batches(): List<BatchRow> = connection.createStatement().executeQuery(
        """SELECT batch_id,case_id,created_at,rolled_back,note FROM import_batch
          |ORDER BY rowid""".trimMargin()
    ).use { rs ->
        val out = mutableListOf<BatchRow>()
        while (rs.next()) out += BatchRow(rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getInt(4) == 1, rs.getString(5))
        out
    }

    companion object {
        fun open(dbPath: Path): Store = Store(Database.open(dbPath))
    }
}

data class SubjectRow(
    val subjectKey: String,
    val currentSourceId: String,
    val currentBatchId: String,
    val multiframe: Boolean,
    val nFrames: Int,
    val sopClassUid: String?,
    val transferSyntaxUid: String?,
    val transferSyntaxSupported: Boolean,
    val hasGeometry: Boolean
)

data class SourceMeta(
    val sourceId: String, val batchId: String, val caseId: String?, val relPath: String,
    val mediaType: String?, val sha: String, val size: Int, val supersedes: String?,
    val importedAt: String, val stage: String
)

data class BatchRow(
    val batchId: String, val caseId: String?, val createdAt: String,
    val rolledBack: Boolean, val note: String?
)
