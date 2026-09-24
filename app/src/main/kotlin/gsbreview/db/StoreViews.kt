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

fun Store.loadInterpretation(interpretationId: String): InterpretationView {
    val meta = connection.prepareStatement(
        """SELECT interpretation_id,rule_version,parameter_set_id,batch_id,case_id,trigger,status
          |FROM interpretation WHERE interpretation_id=?""".trimMargin()
    ).use { ps ->
        ps.setString(1, interpretationId)
        ps.executeQuery().use { require(it.next()) { "no interpretation $interpretationId" }; it }
    }
    val facts = connection.prepareStatement(
        """SELECT fact_id,frame_key,subject_key,field_name,raw_value,unit,basis,numeric_json,valid
          |FROM geometric_fact WHERE interpretation_id=? ORDER BY frame_key, field_name""".trimMargin()
    ).use { ps ->
        ps.setString(1, interpretationId)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<GeomFact>()
            while (rs.next()) out += GeomFact(
                factId = rs.getString(1), frameKey = rs.getString(2), subjectKey = rs.getString(3),
                fieldName = rs.getString(4), rawValue = rs.getString(5), unit = rs.getString(6),
                basis = rs.getString(7),
                numeric = rs.getString(8)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
                valid = rs.getInt(9) == 1
            )
            out
        }
    }
    val diagnostics = connection.prepareStatement(
        """SELECT diagnostic_id,code,severity,message,subject_key,frame_key,raw_value,location_tag
          |FROM diagnostic WHERE interpretation_id=? ORDER BY code, frame_key""".trimMargin()
    ).use { ps ->
        ps.setString(1, interpretationId)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<Diagnostic>()
            while (rs.next()) out += Diagnostic(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)
            )
            out
        }
    }
    val candidateRows = connection.prepareStatement(
        """SELECT c.candidate_id,volume_key,frame_of_reference_uid,orientation_hash,status,reason,
          |member_count,ordered,accepted
          |FROM volume_candidate c WHERE interpretation_id=? ORDER BY c.rowid""".trimMargin()
    ).use { ps ->
        ps.setString(1, interpretationId)
        ps.executeQuery().use { rs ->
            data class CR(
                val id: String, val volumeKey: String, val forUid: String?, val ori: String?,
                val status: String, val reason: String, val count: Int, val ordered: Boolean,
                val accepted: Boolean, val members: MutableList<CandidateMember> = mutableListOf()
            )
            val out = mutableListOf<CR>()
            while (rs.next()) out += CR(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8) == 1, rs.getInt(9) == 1
            )
            out
        }
    }
    val candidates = candidateRows.map { cr ->
        val members = connection.prepareStatement(
            """SELECT cm.ord,cm.frame_key,di.subject_key,cm.raw_instance_number,
              |cm.spatial_coord,cm.spacing_residual_mm,cm.instance_number_matches_spatial,
              |gf.raw_value
              |FROM candidate_member cm
              |JOIN instance_frame f ON f.frame_key=cm.frame_key
              |JOIN dicom_instance di ON di.subject_key=f.subject_key
              |LEFT JOIN geometric_fact gf ON gf.frame_key=cm.frame_key AND gf.interpretation_id=?
              |AND gf.field_name='ImagePositionPatient'
              |WHERE cm.candidate_id=? ORDER BY cm.ord""".trimMargin()
        ).use { ps ->
            ps.setString(1, interpretationId); ps.setString(2, cr.id)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<CandidateMember>()
                while (rs.next()) {
                    val ipp = rs.getString(8)?.split('\\')?.mapNotNull { it.trim().toDoubleOrNull() }
                    out += CandidateMember(
                        ord = rs.getInt(1),
                        frameKey = rs.getString(2),
                        subjectKey = rs.getString(3),
                        instanceNumber = rs.getString(4),
                        position = ipp,
                        spatialCoordinateMm = rs.getObject(5) as Double?,
                        spacingResidualMm = rs.getObject(6) as Double?,
                        instanceNumberMatchesSpatialOrder = (rs.getObject(7) as Int?)?.let { it == 1 }
                    )
                }
                out
            }
        }
        val planes = members.mapNotNull { m ->
            val ipp = m.position ?: return@mapNotNull null
            val ori = factValue(interpretationId, m.frameKey, "ImageOrientationPatient")
                ?.split('\\')?.mapNotNull { it.trim().toDoubleOrNull() }
            val normal = factValue(interpretationId, m.frameKey, "DerivedNormal")
                ?.split('\\')?.mapNotNull { it.trim().toDoubleOrNull() }
            if (ori == null || ori.size != 6 || normal == null || normal.size != 3) null
            else SlicePlane(m.frameKey, ipp, ori.subList(0, 3), ori.subList(3, 6), normal)
        }
        val residuals = listOf(0.0) + members.drop(1).map { it.spacingResidualMm ?: 0.0 }
        VolumeCandidate(
            candidateId = cr.id, volumeKey = cr.volumeKey, frameOfReferenceUID = cr.forUid,
            orientationHash = cr.ori, status = cr.status, reason = cr.reason,
            ordered = cr.ordered, memberCount = cr.count, members = members, planes = planes,
            spacingResidualsMm = residuals, accepted = cr.accepted
        )
    }
    val invalidations = connection.prepareStatement(
        """SELECT old_interpretation_id,new_interpretation_id,reason,dep_type,dep_key,scope_json
          |FROM invalidation WHERE new_interpretation_id=? OR old_interpretation_id=?
          |ORDER BY rowid""".trimMargin()
    ).use { ps ->
        ps.setString(1, interpretationId); ps.setString(2, interpretationId)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<InvalidationView>()
            while (rs.next()) out += InvalidationView(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), runCatching { Json.parseToJsonElement(rs.getString(6)) as JsonObject }
                    .getOrDefault(JsonObject(emptyMap()))
            )
            out
        }
    }
    return InterpretationView(
        interpretationId = meta.getString(1), ruleVersion = meta.getString(2),
        parameterSetId = meta.getString(3), batchId = meta.getString(4),
        caseId = meta.getString(5), trigger = meta.getString(6), status = meta.getString(7),
        facts = facts, candidates = candidates, diagnostics = diagnostics,
        invalidations = invalidations
    )
}

fun Store.factValue(interpretationId: String, frameKey: String, fieldName: String): String? =
    connection.prepareStatement(
        "SELECT raw_value FROM geometric_fact WHERE interpretation_id=? AND frame_key=? AND field_name=?"
    ).use { ps ->
        ps.setString(1, interpretationId); ps.setString(2, frameKey); ps.setString(3, fieldName)
        ps.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

fun Store.allInterpretations(): List<String> =
    connection.createStatement().executeQuery(
        "SELECT interpretation_id FROM interpretation ORDER BY rowid"
    ).use { rs ->
        val out = mutableListOf<String>()
        while (rs.next()) out += rs.getString(1)
        out
    }

fun Store.rawTagsForSubject(subjectKey: String): List<RawTagView> =
    connection.prepareStatement(
        """SELECT rt.tag_path,rt.tag_name,rt.vr,rt.raw_repr,rt.kind,rt.ord,sf.source_id,sf.rel_path
          |FROM raw_tag rt
          |JOIN source_file sf ON sf.source_id=rt.source_id
          |WHERE rt.source_id=(SELECT current_source_id FROM dicom_instance WHERE subject_key=?)
          |ORDER BY rt.ord""".trimMargin()
    ).use { ps ->
        ps.setString(1, subjectKey)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<RawTagView>()
            while (rs.next()) out += RawTagView(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8)
            )
            out
        }
    }

data class RawTagView(
    val tagPath: String, val tagName: String?, val vr: String?, val rawRepr: String,
    val kind: String, val ord: Int, val sourceId: String, val relPath: String
)
