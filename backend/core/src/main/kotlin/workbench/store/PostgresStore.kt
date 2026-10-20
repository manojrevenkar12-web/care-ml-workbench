package workbench.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import workbench.annotation.Annotation
import workbench.annotation.Item
import workbench.annotation.Task
import workbench.annotation.TaskKind
import workbench.annotation.TaskStatus
import workbench.audit.AuditConflict
import workbench.audit.AuditEvent
import workbench.audit.AuditStore
import workbench.dataset.Snapshot
import workbench.dataset.Split
import workbench.json.Codec
import workbench.phi.Region
import workbench.phi.SealedRecord
import workbench.phi.TenantPolicy
import workbench.training.TrainingJob
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/** Applies `db/migration/V*.sql` in order, once, under an advisory lock (safe with several replicas). */
object Migrations {
    val FILES = listOf("V1__schema.sql", "V2__audit_append_only.sql")

    fun apply(ds: DataSource): List<String> = ds.connection.use { c ->
        c.createStatement().use { it.execute("SELECT pg_advisory_lock(7428301)") }
        try {
            c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())") }
            val done = c.createStatement().use { s -> s.executeQuery("SELECT version FROM schema_migrations").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } } }
            FILES.filter { it !in done }.onEach { f ->
                val sql = Migrations::class.java.getResource("/db/migration/$f")!!.readText()
                c.autoCommit = false
                c.createStatement().use { it.execute(sql) }
                c.prepareStatement("INSERT INTO schema_migrations (version) VALUES (?)").use { it.setString(1, f); it.executeUpdate() }
                c.commit()
                c.autoCommit = true
            }
        } finally {
            c.createStatement().use { it.execute("SELECT pg_advisory_unlock(7428301)") }
        }
    }
}

class PostgresStore(private val ds: DataSource) : Store {
    private fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = ds.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    private fun update(sql: String, vararg args: Any?) = ds.connection.use { c -> c.prepareStatement(sql).use { st -> bind(st, args); st.executeUpdate() } }

    private fun bind(st: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setObject(i + 1, null)
                is Instant -> st.setTimestamp(i + 1, Timestamp.from(a))
                is Int -> st.setInt(i + 1, a)
                is Long -> st.setLong(i + 1, a)
                is Double -> st.setDouble(i + 1, a)
                is Boolean -> st.setBoolean(i + 1, a)
                else -> st.setString(i + 1, a.toString())
            }
        }
    }

    private fun ResultSet.instant(c: String): Instant? = getTimestamp(c)?.toInstant()

    private fun obj(s: String?): JsonObject = Json.parseToJsonElement(s ?: "{}").jsonObject

    // ---------------------------------------------------------------- tenants and projects
    override fun tenants() = query("SELECT tenant, region, baa_signed FROM tenants ORDER BY tenant") { TenantPolicy(it.getString(1), Region.valueOf(it.getString(2)), it.getBoolean(3)) }

    override fun putTenant(t: TenantPolicy) {
        update("INSERT INTO tenants VALUES (?, ?, ?) ON CONFLICT (tenant) DO UPDATE SET region = EXCLUDED.region, baa_signed = EXCLUDED.baa_signed", t.tenant, t.region.name, t.baaSigned)
    }

    override fun projects() = query("SELECT id, tenant, name, description FROM projects ORDER BY id") { Project(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }

    override fun project(id: String) = projects().firstOrNull { it.id == id }

    override fun putProject(p: Project) {
        update("INSERT INTO projects VALUES (?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description", p.id, p.tenant, p.name, p.description)
    }

    // ---------------------------------------------------------------- items and vault
    private val itemCols = "id, tenant, project_id, facility_id, subject, recorded_at, asr_model, asr_text, surrogates, priority, reference_text, reference_entities, predictions"

    private fun item(rs: ResultSet) = Item(
        rs.getString("id"), rs.getString("tenant"), rs.getString("project_id"), rs.getString("facility_id"), rs.getString("subject"), rs.instant("recorded_at")!!,
        rs.getString("asr_model"), rs.getString("asr_text"), Codec.parseSurrogates(Json.parseToJsonElement(rs.getString("surrogates"))), rs.getDouble("priority"),
        rs.getString("reference_text"), rs.getString("reference_entities")?.let { Codec.parseSpans(Json.parseToJsonElement(it)) }, Codec.parseStrings(obj(rs.getString("predictions"))),
    )

    override fun putItem(item: Item) {
        update(
            """INSERT INTO items ($itemCols) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb)
               ON CONFLICT (id) DO UPDATE SET priority = EXCLUDED.priority, reference_text = EXCLUDED.reference_text,
               reference_entities = EXCLUDED.reference_entities, predictions = EXCLUDED.predictions""",
            item.id, item.tenant, item.projectId, item.facilityId, item.subject, item.recordedAt, item.asrModel, item.asrText,
            Codec.surrogates(item.surrogates).toString(), item.priority, item.referenceText, item.referenceEntities?.let { Codec.spans(it).toString() }, Codec.strings(item.predictions).toString(),
        )
    }

    override fun item(id: String) = query("SELECT $itemCols FROM items WHERE id = ?", id, map = ::item).firstOrNull()

    override fun items(projectId: String) = query("SELECT $itemCols FROM items WHERE project_id = ? ORDER BY id", projectId, map = ::item)

    override fun putSealed(itemId: String, sealed: SealedRecord) {
        update(
            "INSERT INTO sealed_records VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (item_id) DO NOTHING",
            itemId, sealed.tenant, sealed.recordId, sealed.keyId, sealed.wrappedKey, sealed.iv, sealed.ciphertext,
        )
    }

    override fun sealed(itemId: String) = query("SELECT tenant, record_id, key_id, wrapped_key, iv, ciphertext FROM sealed_records WHERE item_id = ?", itemId) {
        SealedRecord(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
    }.firstOrNull()

    // ---------------------------------------------------------------- tasks
    private fun task(rs: ResultSet) = Task(
        rs.getString("id"), rs.getString("item_id"), rs.getString("project_id"), TaskKind.valueOf(rs.getString("kind")), TaskStatus.valueOf(rs.getString("status")),
        rs.getString("assignee"), rs.instant("leased_until"), rs.instant("created_at")!!,
    )

    override fun putTask(task: Task) {
        update(
            """INSERT INTO tasks VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, assignee = EXCLUDED.assignee, leased_until = EXCLUDED.leased_until""",
            task.id, task.itemId, task.projectId, task.kind.name, task.status.name, task.assignee, task.leasedUntil, task.createdAt,
        )
    }

    override fun task(id: String) = query("SELECT * FROM tasks WHERE id = ?", id, map = ::task).firstOrNull()

    override fun tasks(projectId: String) = query("SELECT * FROM tasks WHERE project_id = ? ORDER BY id", projectId, map = ::task)

    /**
     * Same rules as [workbench.annotation.Assignment], in one transaction: resume the user's live
     * lease if there is one; otherwise lock the best open (or expired) task the user has not
     * already annotated, skipping rows other replicas are leasing right now.
     */
    override fun leaseNext(projectId: String, kind: TaskKind, user: String, now: Instant, lease: Duration): Task? = tx { c ->
        fun one(sql: String, vararg args: Any?): Task? = c.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> if (rs.next()) task(rs) else null }
        }
        one("SELECT * FROM tasks WHERE project_id = ? AND kind = ? AND assignee = ? AND status = 'LEASED' AND leased_until > ? LIMIT 1", projectId, kind.name, user, now)
            ?: one(
                """SELECT t.* FROM tasks t JOIN items i ON i.id = t.item_id
                   WHERE t.project_id = ? AND t.kind = ? AND (t.status = 'OPEN' OR (t.status = 'LEASED' AND t.leased_until < ?))
                     AND NOT EXISTS (SELECT 1 FROM annotations a WHERE a.item_id = t.item_id AND a.annotator = ?)
                   ORDER BY i.priority DESC, t.created_at, t.id
                   LIMIT 1 FOR UPDATE OF t SKIP LOCKED""",
                projectId, kind.name, now, user,
            )?.let { t ->
                val leased = t.copy(status = TaskStatus.LEASED, assignee = user, leasedUntil = now.plus(lease))
                c.prepareStatement("UPDATE tasks SET status = 'LEASED', assignee = ?, leased_until = ? WHERE id = ?").use { st -> bind(st, arrayOf(user, leased.leasedUntil, t.id)); st.executeUpdate() }
                leased
            }
    }

    // ---------------------------------------------------------------- annotations
    private fun annotation(rs: ResultSet) = Annotation(
        rs.getString("id"), rs.getString("task_id"), rs.getString("item_id"), rs.getString("annotator"), rs.getString("transcript"),
        Codec.parseSpans(Json.parseToJsonElement(rs.getString("entities"))), rs.instant("submitted_at")!!, rs.getInt("seconds_spent"), rs.getBoolean("final"),
    )

    override fun putAnnotation(a: Annotation) {
        update(
            "INSERT INTO annotations VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
            a.id, a.taskId, a.itemId, a.annotator, a.transcript, Codec.spans(a.entities).toString(), a.submittedAt, a.secondsSpent, a.final,
        )
    }

    override fun annotations(itemId: String) = query("SELECT * FROM annotations WHERE item_id = ? ORDER BY submitted_at, id", itemId, map = ::annotation)

    override fun projectAnnotations(projectId: String) =
        query("SELECT a.* FROM annotations a JOIN items i ON i.id = a.item_id WHERE i.project_id = ? ORDER BY a.submitted_at, a.id", projectId, map = ::annotation)

    // ---------------------------------------------------------------- snapshots, jobs, models
    override fun putSnapshot(s: Snapshot) = tx { c ->
        c.prepareStatement("INSERT INTO snapshots VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)").use { st ->
            val j = Codec.snapshot(s)
            bind(st, arrayOf(s.id, s.projectId, s.version, s.createdBy, s.createdAt, s.manifestSha256, j["counts"].toString(), j["label_counts"].toString(), j["excluded"].toString()))
            st.executeUpdate()
        }
        c.prepareStatement("INSERT INTO snapshot_examples VALUES (?, ?, ?::jsonb)").use { st ->
            for (e in s.examples) {
                bind(st, arrayOf(s.id, e.itemId, Codec.example(e).toString()))
                st.addBatch()
            }
            st.executeBatch()
        }
        Unit
    }

    private fun snapshotRow(rs: ResultSet, examples: Boolean): Snapshot {
        val id = rs.getString("id")
        val counts = Codec.parseNumbers(obj(rs.getString("counts"))).mapKeys { Split.valueOf(it.key.uppercase()) }.mapValues { it.value.toInt() }
        return Snapshot(
            id, rs.getString("project_id"), rs.getInt("version"), rs.getString("created_by"), rs.instant("created_at")!!, rs.getString("manifest_sha256"), counts,
            Codec.parseNumbers(obj(rs.getString("label_counts"))).mapValues { it.value.toInt() }, Codec.parseNumbers(obj(rs.getString("excluded"))).mapValues { it.value.toInt() },
            if (examples) query("SELECT body FROM snapshot_examples WHERE snapshot_id = ? ORDER BY item_id", id) { Codec.parseExample(obj(it.getString(1))) } else emptyList(),
        )
    }

    override fun snapshot(id: String) = query("SELECT * FROM snapshots WHERE id = ?", id) { snapshotRow(it, true) }.firstOrNull()

    override fun snapshots(projectId: String) = query("SELECT * FROM snapshots WHERE project_id = ? ORDER BY version DESC", projectId) { snapshotRow(it, false) }

    override fun putJob(j: TrainingJob) {
        update(
            "INSERT INTO jobs VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, body = EXCLUDED.body",
            j.id, j.projectId, j.status.name, j.createdAt, Codec.job(j).toString(),
        )
    }

    override fun job(id: String) = query("SELECT body FROM jobs WHERE id = ?", id) { Codec.parseJob(obj(it.getString(1))) }.firstOrNull()

    override fun jobs(projectId: String?) = if (projectId == null) {
        query("SELECT body FROM jobs ORDER BY created_at DESC") { Codec.parseJob(obj(it.getString(1))) }
    } else {
        query("SELECT body FROM jobs WHERE project_id = ? ORDER BY created_at DESC", projectId) { Codec.parseJob(obj(it.getString(1))) }
    }

    override fun putModel(m: RegisteredModel) {
        update("INSERT INTO models VALUES (?, ?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (name) DO NOTHING", m.name, m.projectId, m.jobId, m.artifact, m.snapshotSha256, Codec.numbers(m.metrics).toString(), m.createdAt)
    }

    override fun models(projectId: String) = query("SELECT * FROM models WHERE project_id = ? ORDER BY created_at DESC", projectId) {
        RegisteredModel(it.getString("name"), it.getString("project_id"), it.getString("job_id"), it.getString("artifact"), it.getString("snapshot_sha256"), Codec.parseNumbers(obj(it.getString("metrics"))), it.instant("created_at")!!)
    }

    override fun quarantine(q: Quarantined) {
        update("INSERT INTO quarantine (record_id, tenant, reason, at) VALUES (?, ?, ?, ?)", q.recordId, q.tenant, q.reason, q.at)
    }

    override fun quarantined(tenant: String?) = query("SELECT record_id, tenant, reason, at FROM quarantine WHERE ?::text IS NULL OR tenant = ? ORDER BY id", tenant, tenant) {
        Quarantined(it.getString(1), it.getString(2), it.getString(3), it.instant("at")!!)
    }

    // ---------------------------------------------------------------- audit
    override val audit: AuditStore = object : AuditStore {
        private fun event(rs: ResultSet) = AuditEvent(
            rs.getLong("seq"), rs.instant("at")!!, rs.getString("actor"), rs.getString("action"), rs.getString("tenant"), rs.getString("resource"),
            Codec.parseStrings(obj(rs.getString("detail"))).toSortedMap(), rs.getString("prev_hash"), rs.getString("hash"),
        )

        override fun last() = query("SELECT * FROM audit_events ORDER BY seq DESC LIMIT 1", map = ::event).firstOrNull()

        override fun append(event: AuditEvent) {
            try {
                update(
                    "INSERT INTO audit_events VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                    event.seq, event.at, event.actor, event.action, event.tenant, event.resource, Codec.strings(event.detail).toString(), event.prevHash, event.hash,
                )
            } catch (e: SQLException) {
                if (e.sqlState == "23505") throw AuditConflict() // unique_violation: another replica took this seq
                throw e
            }
        }

        override fun all() = query("SELECT * FROM audit_events ORDER BY seq", map = ::event)

        override fun query(tenant: String?, action: String?, limit: Int) = query(
            "SELECT * FROM audit_events WHERE (?::text IS NULL OR tenant = ?) AND (?::text IS NULL OR action = ?) ORDER BY seq DESC LIMIT ?",
            tenant, tenant, action, action, limit, map = ::event,
        )
    }
}
