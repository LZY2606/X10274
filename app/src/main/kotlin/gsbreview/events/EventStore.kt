package gsbreview.events

import gsbreview.db.EventRow
import gsbreview.db.Store
import gsbreview.imports.Ids
import java.time.Instant

data class BranchState(
    val branchId: String,
    val name: String,
    val parentBranchId: String?,
    val forkedAfterEventSeq: Long?,
    val events: List<EventRow>,
    val headSeq: Long
)

data class AppendResult(val ok: Boolean, val event: EventRow?, val conflict: ConflictView?)

data class ConflictView(
    val attemptedBaseSeq: Long,
    val currentHeadSeq: Long,
    val localEvents: List<EventRow>,
    val remoteEvents: List<EventRow>,
    val message: String
)

class VersionConflict(message: String, val view: ConflictView) : RuntimeException(message)

class EventStore(private val store: Store) {
    val conn = store.connection
    val mainBranchId: String

    init {
        mainBranchId = ensureBranch(branchId = "br_main", name = "main", parent = null, forkAfter = null)
    }

    fun ensureBranch(branchId: String, name: String, parent: String?, forkAfter: Long?): String {
        conn.prepareStatement("INSERT OR IGNORE INTO branch(branch_id,parent_branch_id,forked_after_event_seq,name,created_at) VALUES (?,?,?,?,?)")
            .use { ps ->
                ps.setString(1, branchId); ps.setString(2, parent)
                ps.setObject(3, forkAfter); ps.setString(4, name)
                ps.setString(5, Instant.now().toString()); ps.executeUpdate()
            }
        conn.prepareStatement("INSERT OR IGNORE INTO head(branch_id,head_event_seq) VALUES (?,0)").use {
            it.setString(1, branchId); it.setLong(2, 0); it.executeUpdate()
        }
        return branchId
    }

    fun createBranch(name: String, parent: String, forkAfter: Long): String {
        val now = Instant.now().toString()
        val id = Ids.branchId(name, parent, forkAfter, now)
        ensureBranch(id, name, parent, forkAfter)
        return id
    }

    fun branchState(branchId: String, atOrBeforeSeq: Long? = null): BranchState {
        val own = eventsFor(branchId, atOrBeforeSeq)
        val meta = conn.prepareStatement(
            "SELECT name,parent_branch_id,forked_after_event_seq FROM branch WHERE branch_id=?"
        ).use { ps ->
            ps.setString(1, branchId)
            ps.executeQuery().use {
                require(it.next()) { "unknown branch $branchId" }
                Triple(it.getString(1), it.getString(2), it.getObject(3) as Long?)
            }
        }
        var events = own
        var parent = meta.second
        var forkAfter = meta.third
        val parentSeen = LinkedHashSet<String>()
        while (parent != null && parent !in parentSeen) {
            parentSeen += parent
            val parentEvents = eventsFor(parent, forkAfter)
            events = parentEvents + events
            val parentMeta = conn.prepareStatement(
                "SELECT parent_branch_id,forked_after_event_seq FROM branch WHERE branch_id=?"
            ).use { ps ->
                ps.setString(1, parent)
                ps.executeQuery().use {
                    if (!it.next()) null to null else it.getString(1) to (it.getObject(2) as Long?)
                }
            }
            parent = parentMeta.first
            forkAfter = parentMeta.second
        }
        val head = headSeq(branchId)
        return BranchState(branchId, meta.first, meta.second, meta.third,
            events.sortedBy { it.eventSeq }, head)
    }

    private fun eventsFor(branchId: String, atOrBeforeSeq: Long?): List<EventRow> {
        val sql = "SELECT event_seq,event_id,branch_id,event_type,payload_json,actor,created_at,basis_event_seq " +
            "FROM event WHERE branch_id=?" +
            (if (atOrBeforeSeq != null) " AND event_seq<=?" else "") +
            " ORDER BY event_seq"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, branchId)
            if (atOrBeforeSeq != null) ps.setLong(2, atOrBeforeSeq)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<EventRow>()
                while (rs.next()) out += EventRow(
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8) as Long?
                )
                return out
            }
        }
    }

    fun headSeq(branchId: String): Long =
        conn.prepareStatement("SELECT head_event_seq FROM head WHERE branch_id=?").use { ps ->
            ps.setString(1, branchId)
            ps.executeQuery().use { require(it.next()); it.getLong(1) }
        }

    @Synchronized
    fun append(
        branchId: String,
        eventType: String,
        payloadJson: String,
        actor: String,
        baseSeq: Long,
        basisEventSeq: Long? = null
    ): AppendResult {
        conn.autoCommit = false
        try {
            val current = headSeq(branchId)
            if (baseSeq != current) {
                val conflict = ConflictView(
                    attemptedBaseSeq = baseSeq,
                    currentHeadSeq = current,
                    localEvents = listOf(),
                    remoteEvents = eventsFor(branchId, null).filter { it.eventSeq > baseSeq },
                    message = "version conflict: your view was based on seq $baseSeq but branch head is $current"
                )
                conn.rollback()
                return AppendResult(false, null, conflict)
            }
            val now = Instant.now().toString()
            val eventId = Ids.eventId(branchId, eventType, payloadJson, now, (current + 1).toInt())
            conn.prepareStatement(
                """INSERT INTO event
                  |(event_id,branch_id,event_type,payload_json,actor,created_at,basis_event_seq)
                  |VALUES (?,?,?,?,?,?,?)""".trimMargin()
            ).use { ps ->
                ps.setString(1, eventId); ps.setString(2, branchId); ps.setString(3, eventType)
                ps.setString(4, payloadJson); ps.setString(5, actor); ps.setString(6, now)
                ps.setObject(7, basisEventSeq)
                ps.executeUpdate()
            }
            val seq = conn.createStatement().executeQuery("SELECT last_insert_rowid()").use {
                it.next(); it.getLong(1)
            }
            conn.prepareStatement("UPDATE head SET head_event_seq=? WHERE branch_id=?").use {
                it.setLong(1, seq); it.setString(2, branchId); it.executeUpdate()
            }
            conn.commit()
            val event = EventRow(seq, eventId, branchId, eventType, payloadJson, actor, now, basisEventSeq)
            return AppendResult(true, event, null)
        } catch (t: Throwable) {
            conn.rollback(); throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun undo(branchId: String, actor: String, baseSeq: Long, targetEventSeq: Long): AppendResult {
        val target = eventsFor(branchId, null).firstOrNull { it.eventSeq == targetEventSeq }
            ?: return AppendResult(false, null,
                ConflictView(baseSeq, headSeq(branchId), emptyList(), emptyList(),
                    "cannot undo: event $targetEventSeq not found on branch $branchId"))
        val undoType = when (target.eventType) {
            "candidate_accepted" -> "candidate_accept_revoked"
            "candidate_rejected" -> "candidate_reject_revoked"
            "parameter_revision" -> "parameter_revision_reverted"
            else -> "undone"
        }
        return append(branchId, undoType, target.payloadJson, actor, baseSeq, targetEventSeq)
    }

    fun allBranches(): List<BranchSummary> =
        conn.createStatement().executeQuery(
            "SELECT branch_id,name,parent_branch_id,forked_after_event_seq FROM branch ORDER BY rowid"
        ).use { rs ->
            val out = mutableListOf<BranchSummary>()
            while (rs.next()) out += BranchSummary(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4) as Long?
            )
            out
        }

    fun eventBySeq(seq: Long): EventRow? =
        conn.prepareStatement(
            "SELECT event_seq,event_id,branch_id,event_type,payload_json,actor,created_at,basis_event_seq " +
                "FROM event WHERE event_seq=?"
        ).use { ps ->
            ps.setLong(1, seq)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else EventRow(
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8) as Long?
                )
            }
        }
}

data class BranchSummary(
    val branchId: String, val name: String, val parent: String?, val forkedAfter: Long?
)
