package org.gsb.dicomreview.store

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gsb.dicomreview.model.Branch
import org.gsb.dicomreview.model.EventPayload
import org.gsb.dicomreview.model.RecordedEvent
import org.gsb.dicomreview.model.RawInstance

class Store(val dbPath: Path) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val conn: Connection = run {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").apply {
            createStatement().execute("PRAGMA foreign_keys = ON")
        }
    }

    init {
        if (currentVersion() == 0) {
            applyResource("/db/migration/V1__baseline.sql")
            applyResource("/db/migration/V2__derived_lineage.sql")
            setVersion(2)
        } else if (currentVersion() < 2) {
            applyResource("/db/migration/V2__derived_lineage.sql")
            setVersion(2)
        }
    }

    fun currentVersion(): Int =
        runCatching {
            conn.createStatement().executeQuery("SELECT MAX(version) FROM schema_version").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }.getOrDefault(0)

    private fun setVersion(v: Int) {
        conn.prepareStatement("INSERT INTO schema_version(version, applied_at) VALUES (?, ?)")
            .apply { setInt(1, v); setString(2, java.time.Instant.now().toString()) }.executeUpdate()
    }

    private fun applyResource(resource: String) {
        val sql = Store::class.java.getResource(resource)?.readText()
            ?: error("missing migration resource $resource")
        conn.createStatement().apply {
            sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { execute(it) }
        }.close()
    }

    fun <T> transaction(block: () -> T): T {
        val oldAuto = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = oldAuto
        }
    }

    fun ensureMainBranch() {
        val exists = conn.prepareStatement("SELECT 1 FROM branch WHERE branch_id = ?").apply {
            setString(1, "br_main")
        }.executeQuery().use { it.next() }
        if (!exists) {
            conn.prepareStatement("INSERT INTO branch(branch_id, name, parent_branch_id, forked_from_event_id) VALUES (?, 'main', NULL, NULL)")
                .apply { setString(1, "br_main") }.executeUpdate()
            val rootId = "evt_root"
            conn.prepareStatement(
                "INSERT INTO event(event_id, branch_id, seq, type, payload_json, parent_event_id, client_head_event_id, created_at) " +
                    "VALUES (?, ?, 0, 'ROOT', ?, NULL, NULL, ?)"
            ).apply {
                setString(1, rootId); setString(2, "br_main")
                setString(3, json.encodeToString(EventPayload.Root()))
                setString(4, java.time.Instant.EPOCH.toString())
            }.executeUpdate()
            setHead("br_main", rootId, 0)
            conn.commit()
        }
    }

    fun listBranches(): List<Branch> =
        conn.createStatement().executeQuery(
            "SELECT branch_id, name, parent_branch_id, forked_from_event_id FROM branch ORDER BY branch_id"
        ).use { rs ->
            buildList {
                while (rs.next()) {
                    add(Branch(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                }
            }
        }

    fun createBranch(branch: Branch) {
        conn.prepareStatement(
            "INSERT INTO branch(branch_id, name, parent_branch_id, forked_from_event_id) VALUES (?, ?, ?, ?)"
        ).apply {
            setString(1, branch.branchId); setString(2, branch.name)
            setString(3, branch.parentBranchId); setString(4, branch.forkedFromEventId)
        }.executeUpdate()
    }

    fun head(branchId: String): RecordedEvent? =
        conn.prepareStatement(
            "SELECT e.* FROM event e JOIN client_head h ON h.head_event_id = e.event_id WHERE h.branch_id = ?"
        ).apply { setString(1, branchId) }.executeQuery().use { if (it.next()) toEvent(it) else null }

    fun setHead(branchId: String, eventId: String, seq: Long) {
        conn.prepareStatement(
            "INSERT INTO client_head(branch_id, head_event_id, head_seq) VALUES (?, ?, ?) " +
                "ON CONFLICT(branch_id) DO UPDATE SET head_event_id = excluded.head_event_id, head_seq = excluded.head_seq"
        ).apply { setString(1, branchId); setString(2, eventId); setLong(3, seq) }.executeUpdate()
    }

    fun nextSeq(branchId: String): Long =
        conn.prepareStatement("SELECT COALESCE(MAX(seq), 0) + 1 FROM event WHERE branch_id = ?")
            .apply { setString(1, branchId) }.executeQuery().use { if (it.next()) it.getLong(1) else 1L }

    fun appendEvent(event: RecordedEvent) {
        conn.prepareStatement(
            "INSERT INTO event(event_id, branch_id, seq, type, payload_json, parent_event_id, client_head_event_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).apply {
            setString(1, event.eventId); setString(2, event.branchId); setLong(3, event.seq)
            setString(4, event.type); setString(5, event.payloadJson)
            setString(6, event.parentEventId); setString(7, event.clientHeadEventId)
            setString(8, java.time.Instant.now().toString())
        }.executeUpdate()
        setHead(event.branchId, event.eventId, event.seq)
    }

    fun eventsOnBranch(branchId: String): List<RecordedEvent> =
        conn.prepareStatement("SELECT * FROM event WHERE branch_id = ? ORDER BY seq")
            .apply { setString(1, branchId) }.executeQuery().use { rs ->
                buildList { while (rs.next()) add(toEvent(rs)) }
            }

    fun event(eventId: String): RecordedEvent? =
        conn.prepareStatement("SELECT * FROM event WHERE event_id = ?").apply { setString(1, eventId) }
            .executeQuery().use { if (it.next()) toEvent(it) else null }

    fun eventSeqOnBranch(branchId: String, seq: Long): RecordedEvent? =
        conn.prepareStatement("SELECT * FROM event WHERE branch_id = ? AND seq = ?")
            .apply { setString(1, branchId); setLong(2, seq) }
            .executeQuery().use { if (it.next()) toEvent(it) else null }

    private fun toEvent(rs: ResultSet): RecordedEvent = RecordedEvent(
        rs.getString("event_id"),
        rs.getString("branch_id"),
        rs.getLong("seq"),
        rs.getString("type"),
        rs.getString("payload_json"),
        rs.getString("parent_event_id"),
        rs.getString("client_head_event_id")
    )

    fun upsertBatch(batchId: String, label: String, ordinal: Long) {
        conn.prepareStatement(
            "INSERT INTO batch(batch_id, label, ordinal) VALUES (?, ?, ?) " +
                "ON CONFLICT(batch_id) DO UPDATE SET label = excluded.label"
        ).apply { setString(1, batchId); setString(2, label); setLong(3, ordinal) }.executeUpdate()
    }

    fun batchExists(batchId: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM batch WHERE batch_id = ?").apply { setString(1, batchId) }
            .executeQuery().use { it.next() }

    fun nextBatchOrdinal(): Long =
        conn.createStatement().executeQuery("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM batch").use {
            if (it.next()) it.getLong(1) else 1L
        }

    fun insertRaw(raw: RawInstance, bytes: ByteArray) {
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO raw_instance(
              instance_id, batch_id, ordinal_in_batch, file_name, sop_class_uid, sop_instance_uid,
              series_instance_uid, study_instance_uid, frame_of_reference_uid, instance_number,
              transfer_syntax_uid, transfer_syntax_supported, media_stored, media_sop_class_uid,
              fatal_diagnostic, raw_tags_json, raw_bytes, raw_bytes_hash, raw_bytes_size,
              pixel_frame_count, rows, columns)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()
        ).apply {
            setString(1, raw.instanceId); setString(2, raw.batchId); setInt(3, raw.ordinalInBatch)
            setString(4, raw.fileName); setString(5, raw.sopClassUid); setString(6, raw.sopInstanceUid)
            setString(7, raw.seriesInstanceUid); setString(8, raw.studyInstanceUid)
            setString(9, raw.frameOfReferenceUid); setObject(10, raw.instanceNumber)
            setString(11, raw.transferSyntaxUid); setInt(12, if (raw.transferSyntaxSupported) 1 else 0)
            setInt(13, if (raw.mediaStored) 1 else 0); setString(14, raw.mediaSopClassUid)
            setString(15, raw.fatalDiagnostic); setString(16, raw.rawTagsJson)
            setBytes(17, bytes); setString(18, raw.rawBytesHash); setInt(19, raw.rawBytesSize)
            setInt(20, raw.pixelFrameCount); setObject(21, raw.rows); setObject(22, raw.columns)
        }.executeUpdate()
    }

    fun addBatchMember(batchId: String, instanceId: String, ordinal: Int) {
        conn.prepareStatement("INSERT OR IGNORE INTO batch_member(batch_id, instance_id, ordinal) VALUES (?, ?, ?)")
            .apply { setString(1, batchId); setString(2, instanceId); setInt(3, ordinal) }.executeUpdate()
    }

    fun loadInstance(instanceId: String): Pair<RawInstance, ByteArray>? =
        conn.prepareStatement("SELECT * FROM raw_instance WHERE instance_id = ?").apply { setString(1, instanceId) }
            .executeQuery().use { rs ->
                if (!rs.next()) null else toRaw(rs) to rs.getBytes("raw_bytes")
            }

    fun allInstances(): List<RawInstance> =
        conn.createStatement().executeQuery("SELECT * FROM raw_instance ORDER BY instance_id").use { rs ->
            buildList { while (rs.next()) add(toRaw(rs)) }
        }

    fun instancesForBatch(batchId: String): List<RawInstance> =
        conn.prepareStatement(
            "SELECT ri.* FROM raw_instance ri JOIN batch_member bm ON bm.instance_id = ri.instance_id " +
                "WHERE bm.batch_id = ? ORDER BY bm.ordinal"
        ).apply { setString(1, batchId) }.executeQuery().use { rs ->
            buildList { while (rs.next()) add(toRaw(rs)) }
        }

    fun rawBytes(instanceId: String): ByteArray? =
        conn.prepareStatement("SELECT raw_bytes FROM raw_instance WHERE instance_id = ?").apply {
            setString(1, instanceId)
        }.executeQuery().use { if (it.next()) it.getBytes(1) else null }

    fun saveInvalidation(reportJson: String, reportId: String, branchId: String, triggerEventId: String, seq: Long) {
        conn.prepareStatement(
            "INSERT INTO invalidation_report(report_id, branch_id, trigger_event_id, report_json, seq) VALUES (?,?,?,?,?)"
        ).apply {
            setString(1, reportId); setString(2, branchId); setString(3, triggerEventId)
            setString(4, reportJson); setLong(5, seq)
        }.executeUpdate()
    }

    fun invalidationFor(eventId: String): String? =
        conn.prepareStatement("SELECT report_json FROM invalidation_report WHERE trigger_event_id = ?")
            .apply { setString(1, eventId) }.executeQuery().use { if (it.next()) it.getString(1) else null }

    fun listInvalidations(branchId: String): List<Pair<String, String>> =
        conn.prepareStatement("SELECT trigger_event_id, report_json FROM invalidation_report WHERE branch_id = ? ORDER BY seq")
            .apply { setString(1, branchId) }.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }

    fun saveCounterexample(entryId: String, branchId: String, variant: String, title: String, accepted: Boolean,
                          baselineEventId: String?, variantEventId: String?, reportJson: String) {
        conn.prepareStatement(
            """INSERT OR REPLACE INTO counterexample_entry
              (entry_id, branch_id, variant, title, accepted, baseline_event_id, variant_event_id, report_json)
              VALUES (?,?,?,?,?,?,?,?)"""
        ).apply {
            setString(1, entryId); setString(2, branchId); setString(3, variant); setString(4, title)
            setInt(5, if (accepted) 1 else 0); setString(6, baselineEventId)
            setString(7, variantEventId); setString(8, reportJson)
        }.executeUpdate()
    }

    fun listCounterexamples(): List<CounterexampleRow> =
        conn.createStatement().executeQuery(
            "SELECT entry_id, branch_id, variant, title, accepted, baseline_event_id, variant_event_id, report_json " +
                "FROM counterexample_entry ORDER BY variant"
        ).use { rs ->
            buildList {
                while (rs.next()) {
                    add(CounterexampleRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5) == 1, rs.getString(6), rs.getString(7), rs.getString(8)
                    ))
                }
            }
        }

    fun wipeAllData() {
        conn.createStatement().use { st ->
            listOf(
                "counterexample_entry", "invalidation_report", "client_head", "event", "branch",
                "batch_member", "raw_instance", "batch", "schema_version"
            ).forEach { st.execute("DELETE FROM $it") }
        }
    }

    private fun toRaw(rs: ResultSet): RawInstance = RawInstance(
        instanceId = rs.getString("instance_id"),
        batchId = rs.getString("batch_id"),
        ordinalInBatch = rs.getInt("ordinal_in_batch"),
        fileName = rs.getString("file_name"),
        sopClassUid = rs.getString("sop_class_uid"),
        sopInstanceUid = rs.getString("sop_instance_uid"),
        seriesInstanceUid = rs.getString("series_instance_uid"),
        studyInstanceUid = rs.getString("study_instance_uid"),
        frameOfReferenceUid = rs.getString("frame_of_reference_uid"),
        instanceNumber = rs.getObject("instance_number") as? Int,
        transferSyntaxUid = rs.getString("transfer_syntax_uid"),
        transferSyntaxSupported = rs.getInt("transfer_syntax_supported") == 1,
        mediaStored = rs.getInt("media_stored") == 1,
        mediaSopClassUid = rs.getString("media_sop_class_uid"),
        fatalDiagnostic = rs.getString("fatal_diagnostic"),
        rawTagsJson = rs.getString("raw_tags_json"),
        rawBytesHash = rs.getString("raw_bytes_hash"),
        rawBytesSize = rs.getInt("raw_bytes_size"),
        pixelFrameCount = rs.getInt("pixel_frame_count"),
        rows = rs.getObject("rows") as? Int,
        columns = rs.getObject("columns") as? Int
    )
}

data class CounterexampleRow(
    val entryId: String,
    val branchId: String,
    val variant: String,
    val title: String,
    val accepted: Boolean,
    val baselineEventId: String?,
    val variantEventId: String?,
    val reportJson: String
)
