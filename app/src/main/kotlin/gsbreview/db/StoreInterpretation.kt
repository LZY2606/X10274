package gsbreview.db

import gsbreview.domain.CandidateMember
import gsbreview.domain.Diagnostic
import gsbreview.domain.GeomFact
import gsbreview.domain.InterpretationView
import gsbreview.domain.InvalidationView
import gsbreview.domain.SlicePlane
import gsbreview.domain.VolumeCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DependencyRow(
    val dependencyId: String,
    val interpretationId: String,
    val depType: String,
    val depKey: String,
    val scopeJson: String
)

data class InvalidationRow(
    val invalidationId: String,
    val oldInterpretationId: String,
    val newInterpretationId: String?,
    val reason: String,
    val depType: String,
    val depKey: String,
    val scopeJson: String,
    val createdAt: String
)

data class EventRow(
    val eventSeq: Long,
    val eventId: String,
    val branchId: String,
    val eventType: String,
    val payloadJson: String,
    val actor: String,
    val createdAt: String,
    val basisEventSeq: Long?
)

data class CandidateDecisionRow(
    val candidateId: String,
    val interpretationId: String,
    val branchId: String,
    val decision: String,
    val eventSeq: Long,
    val note: String?
)

fun Store.ensureParameterSet(parameterSetId: String, fingerprint: String, createdAt: String) {
    connection.prepareStatement(
        "INSERT OR IGNORE INTO parameter_set(parameter_set_id,label,params_json,created_at) VALUES (?,?,?,?)"
    ).use { ps ->
        ps.setString(1, parameterSetId)
        ps.setString(2, fingerprint)
        ps.setString(3, JsonObject(mapOf("fingerprint" to JsonPrimitive(fingerprint))).toString())
        ps.setString(4, createdAt)
        ps.executeUpdate()
    }
}

fun Store.interpretationExists(id: String): Boolean =
    connection.prepareStatement("SELECT 1 FROM interpretation WHERE interpretation_id=?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { it.next() }
    }

fun Store.latestInterpretation(): String? =
    connection.createStatement().executeQuery(
        "SELECT interpretation_id FROM interpretation ORDER BY rowid DESC LIMIT 1"
    ).use { if (it.next()) it.getString(1) else null }

fun Store.parameterFingerprintForInterpretation(interpId: String): String? =
    connection.prepareStatement(
        """SELECT p.params_json FROM parameter_set p
          |JOIN interpretation i ON i.parameter_set_id=p.parameter_set_id
          |WHERE i.interpretation_id=?""".trimMargin()
    ).use { ps ->
        ps.setString(1, interpId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return null
            val obj = Json.parseToJsonElement(rs.getString(1)).jsonObject
            obj["fingerprint"]?.jsonPrimitive?.content
        }
    }

fun Store.changedSourcesSinceInterpretation(oldInterpId: String): List<String> {
    return connection.prepareStatement(
        """SELECT s.source_id FROM source_file s
          |JOIN interpretation i ON i.interpretation_id=?
          |WHERE s.imported_at > i.created_at OR s.batch_id != i.batch_id
          |GROUP BY s.source_id""".trimMargin()
    ).use { ps ->
        ps.setString(1, oldInterpId)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<String>()
            while (rs.next()) out += rs.getString(1)
            out
        }
    }
}

fun Store.persistInterpretation(
    interpretationId: String,
    ruleVersion: String,
    parameterSetId: String,
    batchId: String,
    caseId: String?,
    createdAt: String,
    trigger: String,
    facts: List<GeomFact>,
    candidates: List<VolumeCandidate>,
    diagnostics: List<Diagnostic>,
    dependencies: List<DependencyRow>,
    invalidations: List<InvalidationRow>
) {
    val conn = connection
    conn.autoCommit = false
    try {
        conn.prepareStatement(
            """INSERT INTO interpretation
              |(interpretation_id,rule_version,parameter_set_id,batch_id,created_at,trigger,status,case_id)
              |VALUES (?,?,?,?,?,?,?,?)""".trimMargin()
        ).use { ps ->
            ps.setString(1, interpretationId); ps.setString(2, ruleVersion)
            ps.setString(3, parameterSetId); ps.setString(4, batchId)
            ps.setString(5, createdAt); ps.setString(6, trigger)
            ps.setString(7, "active"); ps.setString(8, caseId)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            """INSERT OR REPLACE INTO geometric_fact
              |(fact_id,frame_key,subject_key,interpretation_id,field_name,raw_value,unit,basis,numeric_json,valid)
              |VALUES (?,?,?,?,?,?,?,?,?,?)""".trimMargin()
        ).use { ps ->
            facts.forEach { f ->
                ps.setString(1, f.factId); ps.setString(2, f.frameKey); ps.setString(3, f.subjectKey)
                ps.setString(4, interpretationId); ps.setString(5, f.fieldName)
                ps.setString(6, f.rawValue); ps.setString(7, f.unit); ps.setString(8, f.basis)
                ps.setString(9, f.numeric?.toString()); ps.setInt(10, if (f.valid) 1 else 0)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            """INSERT OR REPLACE INTO diagnostic
              |(diagnostic_id,interpretation_id,subject_key,frame_key,code,severity,message,raw_value,location_tag)
              |VALUES (?,?,?,?,?,?,?,?,?)""".trimMargin()
        ).use { ps ->
            diagnostics.forEach { d ->
                ps.setString(1, d.diagnosticId); ps.setString(2, interpretationId)
                ps.setString(3, d.subjectKey); ps.setString(4, d.frameKey)
                ps.setString(5, d.code); ps.setString(6, d.severity); ps.setString(7, d.message)
                ps.setString(8, d.rawValue); ps.setString(9, d.locationTag)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            """INSERT OR REPLACE INTO dependency
              |(dependency_id,interpretation_id,dep_type,dep_key,scope_json)
              |VALUES (?,?,?,?,?)""".trimMargin()
        ).use { ps ->
            dependencies.forEach { d ->
                ps.setString(1, d.dependencyId); ps.setString(2, d.interpretationId)
                ps.setString(3, d.depType); ps.setString(4, d.depKey); ps.setString(5, d.scopeJson)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            """INSERT OR REPLACE INTO invalidation
              |(invalidation_id,old_interpretation_id,new_interpretation_id,reason,dep_type,dep_key,scope_json,created_at)
              |VALUES (?,?,?,?,?,?,?,?)""".trimMargin()
        ).use { ps ->
            invalidations.forEach { iv ->
                ps.setString(1, iv.invalidationId); ps.setString(2, iv.oldInterpretationId)
                ps.setString(3, iv.newInterpretationId); ps.setString(4, iv.reason)
                ps.setString(5, iv.depType); ps.setString(6, iv.depKey)
                ps.setString(7, iv.scopeJson); ps.setString(8, iv.createdAt)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        persistCandidates(conn, interpretationId, candidates)
        conn.commit()
    } catch (t: Throwable) {
        conn.rollback(); throw t
    } finally {
        conn.autoCommit = true
    }
}

private fun persistCandidates(conn: java.sql.Connection, interpretationId: String,
                              candidates: List<VolumeCandidate>) {
    conn.prepareStatement("DELETE FROM volume_candidate WHERE interpretation_id=?").use {
        it.setString(1, interpretationId); it.executeUpdate()
    }
    conn.prepareStatement(
        """INSERT INTO volume_candidate
          |(candidate_id,interpretation_id,volume_key,frame_of_reference_uid,orientation_hash,
          | status,reason,member_count,ordered,geometry_json,accepted)
          |VALUES (?,?,?,?,?,?,?,?,?,?,0)""".trimMargin()
    ).use { ps ->
        candidates.forEach { c ->
            ps.setString(1, c.candidateId); ps.setString(2, interpretationId)
            ps.setString(3, c.volumeKey); ps.setString(4, c.frameOfReferenceUID)
            ps.setString(5, c.orientationHash); ps.setString(6, c.status); ps.setString(7, c.reason)
            ps.setInt(8, c.memberCount); ps.setInt(9, if (c.ordered) 1 else 0)
            ps.setString(10, JsonPrimitive("").let { "" }); ps.addBatch()
        }
        ps.executeBatch()
    }
    conn.prepareStatement(
        """INSERT INTO candidate_member
          |(candidate_id,ord,frame_key,spatial_coord,spacing_residual_mm,raw_instance_number,instance_number_matches_spatial)
          |VALUES (?,?,?,?,?,?,?)""".trimMargin()
    ).use { ps ->
        candidates.forEach { c ->
            c.members.forEach { m ->
                ps.setString(1, c.candidateId); ps.setInt(2, m.ord)
                ps.setString(3, m.frameKey)
                ps.setObject(4, m.spatialCoordinateMm)
                ps.setObject(5, m.spacingResidualMm)
                ps.setString(6, m.instanceNumber)
                ps.setObject(7, m.instanceNumberMatchesSpatialOrder?.let { if (it) 1 else 0 })
                ps.addBatch()
            }
        }
        ps.executeBatch()
    }
}
