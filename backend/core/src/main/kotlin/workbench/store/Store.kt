package workbench.store

import workbench.annotation.Annotation
import workbench.annotation.Assignment
import workbench.annotation.Item
import workbench.annotation.Task
import workbench.annotation.TaskKind
import workbench.annotation.TaskStatus
import workbench.audit.AuditEvent
import workbench.audit.AuditStore
import workbench.dataset.Snapshot
import workbench.phi.SealedRecord
import workbench.phi.TenantPolicy
import workbench.training.TrainingJob
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class Project(val id: String, val tenant: String, val name: String, val description: String)

data class RegisteredModel(val name: String, val projectId: String, val jobId: String, val artifact: String, val snapshotSha256: String, val metrics: Map<String, Double>, val createdAt: Instant)

data class Quarantined(val recordId: String, val tenant: String, val reason: String, val at: Instant)

/** Persistence for the ML App. Implemented in memory (tests, local dev) and on PostgreSQL. */
interface Store {
    fun tenants(): List<TenantPolicy>
    fun putTenant(t: TenantPolicy)
    fun projects(): List<Project>
    fun project(id: String): Project?
    fun putProject(p: Project)

    fun putItem(item: Item)
    fun item(id: String): Item?
    fun items(projectId: String): List<Item>

    fun putSealed(itemId: String, sealed: SealedRecord)
    fun sealed(itemId: String): SealedRecord?

    fun putTask(task: Task)
    fun task(id: String): Task?
    fun tasks(projectId: String): List<Task>

    /** Atomically lease the next task of [kind] for [user] (see [Assignment] for the rules). */
    fun leaseNext(projectId: String, kind: TaskKind, user: String, now: Instant, lease: Duration): Task?

    fun putAnnotation(a: Annotation)
    fun annotations(itemId: String): List<Annotation>
    fun projectAnnotations(projectId: String): List<Annotation>

    fun putSnapshot(s: Snapshot)
    fun snapshot(id: String): Snapshot?
    fun snapshots(projectId: String): List<Snapshot>

    fun putJob(j: TrainingJob)
    fun job(id: String): TrainingJob?
    fun jobs(projectId: String?): List<TrainingJob>

    fun putModel(m: RegisteredModel)
    fun models(projectId: String): List<RegisteredModel>

    fun quarantine(q: Quarantined)
    fun quarantined(tenant: String?): List<Quarantined>

    val audit: AuditStore
}

class InMemoryStore : Store {
    private val tenants = ConcurrentHashMap<String, TenantPolicy>()
    private val projects = ConcurrentHashMap<String, Project>()
    private val items = ConcurrentHashMap<String, Item>()
    private val sealed = ConcurrentHashMap<String, SealedRecord>()
    private val tasks = ConcurrentHashMap<String, Task>()
    private val annotations = ConcurrentHashMap<String, Annotation>()
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val jobs = ConcurrentHashMap<String, TrainingJob>()
    private val models = ConcurrentHashMap<String, RegisteredModel>()
    private val quarantine = java.util.concurrent.CopyOnWriteArrayList<Quarantined>()
    private val leaseLock = Any()

    override fun tenants() = tenants.values.sortedBy { it.tenant }
    override fun putTenant(t: TenantPolicy) { tenants[t.tenant] = t }
    override fun projects() = projects.values.sortedBy { it.id }
    override fun project(id: String) = projects[id]
    override fun putProject(p: Project) { projects[p.id] = p }

    override fun putItem(item: Item) { items[item.id] = item }
    override fun item(id: String) = items[id]
    override fun items(projectId: String) = items.values.filter { it.projectId == projectId }.sortedBy { it.id }

    override fun putSealed(itemId: String, sealed: SealedRecord) { this.sealed[itemId] = sealed }
    override fun sealed(itemId: String) = sealed[itemId]

    override fun putTask(task: Task) { tasks[task.id] = task }
    override fun task(id: String) = tasks[id]
    override fun tasks(projectId: String) = tasks.values.filter { it.projectId == projectId }.sortedBy { it.id }

    override fun leaseNext(projectId: String, kind: TaskKind, user: String, now: Instant, lease: Duration): Task? = synchronized(leaseLock) {
        val open = tasks.values.filter { it.projectId == projectId && it.kind == kind }
        val doneBy = annotations.values.groupBy { it.itemId }.mapValues { (_, v) -> v.map { it.annotator }.toSet() }
        // the same user may hold at most one live lease per item
        val mine = tasks.values.filter { it.assignee == user && it.status == TaskStatus.LEASED && it.leasedUntil?.isAfter(now) == true }
        mine.firstOrNull { it.projectId == projectId && it.kind == kind }?.let { return it }
        val busyItems = tasks.values.filter { it.status == TaskStatus.LEASED && it.leasedUntil?.isAfter(now) == true && it.assignee == user }.map { it.itemId }.toSet()
        val pick = Assignment(lease = lease).next(user, open.filter { it.itemId !in busyItems }, items, doneBy, now) ?: return null
        tasks[pick.id] = pick
        pick
    }

    override fun putAnnotation(a: Annotation) { annotations[a.id] = a }
    override fun annotations(itemId: String) = annotations.values.filter { it.itemId == itemId }.sortedBy { it.submittedAt }
    override fun projectAnnotations(projectId: String): List<Annotation> {
        val ids = items.values.filter { it.projectId == projectId }.map { it.id }.toSet()
        return annotations.values.filter { it.itemId in ids }.sortedBy { it.submittedAt }
    }

    override fun putSnapshot(s: Snapshot) { snapshots[s.id] = s }
    override fun snapshot(id: String) = snapshots[id]
    override fun snapshots(projectId: String) = snapshots.values.filter { it.projectId == projectId }.sortedByDescending { it.version }

    override fun putJob(j: TrainingJob) { jobs[j.id] = j }
    override fun job(id: String) = jobs[id]
    override fun jobs(projectId: String?) = jobs.values.filter { projectId == null || it.projectId == projectId }.sortedByDescending { it.createdAt }

    override fun putModel(m: RegisteredModel) { models[m.name] = m }
    override fun models(projectId: String) = models.values.filter { it.projectId == projectId }.sortedByDescending { it.createdAt }

    override fun quarantine(q: Quarantined) { quarantine += q }
    override fun quarantined(tenant: String?) = quarantine.filter { tenant == null || it.tenant == tenant }

    override val audit: AuditStore = object : AuditStore {
        private val events = java.util.concurrent.CopyOnWriteArrayList<AuditEvent>()
        override fun last() = events.lastOrNull()
        override fun append(event: AuditEvent) { events += event }
        override fun all() = events.toList()
        override fun query(tenant: String?, action: String?, limit: Int) =
            events.filter { (tenant == null || it.tenant == tenant) && (action == null || it.action == action) }.takeLast(limit).reversed()
    }

    /** Test hook: overwrite an audit event in place, as an attacker with database access would. */
    fun tamperAudit(seq: Long, change: (AuditEvent) -> AuditEvent) {
        val list = (audit.all())
        val field = audit.javaClass.getDeclaredField("events").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val events = field.get(audit) as java.util.concurrent.CopyOnWriteArrayList<AuditEvent>
        val idx = list.indexOfFirst { it.seq == seq }
        events[idx] = change(events[idx])
    }
}
