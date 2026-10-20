package workbench.service

import workbench.annotation.Agreement
import workbench.annotation.Annotation
import workbench.annotation.AnnotationRules
import workbench.annotation.Assignment
import workbench.annotation.EntitySpan
import workbench.annotation.Item
import workbench.annotation.Label
import workbench.annotation.Task
import workbench.annotation.TaskKind
import workbench.annotation.TaskStatus
import workbench.audit.Actions
import workbench.audit.AuditEvent
import workbench.audit.AuditLog
import workbench.audit.Verification
import workbench.authz.Authorizer
import workbench.authz.Forbidden
import workbench.authz.LocalAuthorizer
import workbench.authz.Tuple
import workbench.authz.require
import workbench.dataset.Snapshot
import workbench.dataset.SnapshotBuilder
import workbench.dataset.Split
import workbench.eval.EntityScore
import workbench.eval.ModelComparison
import workbench.eval.Wer
import workbench.model.EntityTagger
import workbench.phi.KnownIdentifiers
import workbench.phi.PhiDetector
import workbench.phi.PhiVault
import workbench.store.Project
import workbench.store.RegisteredModel
import workbench.store.Store
import workbench.training.JobRunner
import workbench.training.JobStateMachine
import workbench.training.JobStatus
import workbench.training.TrainingJob
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NotFound(message: String) : RuntimeException(message)

class Invalid(message: String) : RuntimeException(message)

class Conflict(message: String) : RuntimeException(message)

class PhiInInput(val spans: List<workbench.phi.PhiSpan>) : RuntimeException("the text contains possible PHI; remove it before using the playground")

data class TaskView(val task: Task, val item: Item, val existing: List<Annotation>, val suggestions: List<EntitySpan>)

data class ExploreRow(
    val item: Item,
    val annotations: Int,
    val status: String, // "unlabelled" | "in progress" | "needs review" | "done"
    val wer: Double?, // primary ASR vs reference, once a reference exists
    val labels: Set<Label>,
    val reference: Annotation? = null, // the transcript and labels that go into training
)

data class ProjectStats(
    val items: Int,
    val tasksOpen: Int,
    val tasksLeased: Int,
    val annotations: Int,
    val done: Int,
    val needsReview: Int,
    val agreement: Agreement.Result,
    val labelCounts: Map<Label, Int>,
    val medianSecondsPerItem: Double?,
)

data class PhiReveal(val itemId: String, val original: String, val auditSeq: Long)

/**
 * The ML App's application service: every operation checks authorisation first, validates its
 * input, writes through the store and audits what must be audited. The HTTP layer only maps
 * requests to these calls, so the rules live in one place and are unit-tested here.
 */
class Workbench(
    val store: Store,
    private val authz: Authorizer,
    val audit: AuditLog,
    private val vault: PhiVault,
    private val runner: JobRunner? = null,
    private val exportDir: java.nio.file.Path = java.nio.file.Files.createTempDirectory("workbench-datasets"),
    private val loadModel: (RegisteredModel) -> EntityTagger? = { null },
    private val clock: () -> Instant = Instant::now,
    private val ids: (String) -> String = { prefix -> prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(16) },
    private val assignment: Assignment = Assignment(),
    private val lease: Duration = Duration.ofMinutes(20),
) {
    private val detector = PhiDetector()
    private val taggers = ConcurrentHashMap<String, EntityTagger>()

    private fun projectOrThrow(id: String) = store.project(id) ?: throw NotFound("project $id")

    private fun itemOrThrow(id: String) = store.item(id) ?: throw NotFound("item $id")

    // ---------------------------------------------------------------- projects
    fun projects(user: String): List<Project> = store.projects().filter { authz.check(user, "viewer", "project:${it.id}") }

    fun permissions(user: String, projectId: String): Map<String, Boolean> =
        listOf("viewer", "annotator", "reviewer", "admin", "phi_viewer").associateWith { authz.check(user, it, "project:$projectId") }

    // ---------------------------------------------------------------- annotation
    fun nextTask(user: String, projectId: String, kind: TaskKind = TaskKind.ANNOTATE): TaskView? {
        projectOrThrow(projectId)
        authz.require(user, if (kind == TaskKind.REVIEW) "reviewer" else "annotator", "project:$projectId")
        val t = store.leaseNext(projectId, kind, user, clock(), lease) ?: return null
        val item = itemOrThrow(t.itemId)
        val existing = if (kind == TaskKind.REVIEW) store.annotations(item.id) else emptyList()
        return TaskView(t, item, existing, suggestions(projectId, item.asrText))
    }

    private fun ownLease(user: String, taskId: String): Task {
        val t = store.task(taskId) ?: throw NotFound("task $taskId")
        if (t.assignee != user || t.status != TaskStatus.LEASED) throw Conflict("task $taskId is not leased to you")
        if (t.leasedUntil?.isBefore(clock()) == true) throw Conflict("your lease on task $taskId expired; fetch the next task")
        return t
    }

    fun submit(user: String, taskId: String, transcript: String, entities: List<EntitySpan>, secondsSpent: Int): Annotation {
        val t = ownLease(user, taskId)
        if (t.kind != TaskKind.ANNOTATE) throw Invalid("task $taskId is a review task; use adjudicate")
        authz.require(user, "annotator", "project:${t.projectId}")
        val item = itemOrThrow(t.itemId)
        AnnotationRules.validate(item, transcript, entities)
        val a = Annotation(ids("ann"), t.id, item.id, user, transcript, entities.sortedBy { it.start }, clock(), secondsSpent.coerceIn(0, 86_400))
        store.putAnnotation(a)
        store.putTask(t.copy(status = TaskStatus.SUBMITTED))
        val all = store.annotations(item.id)
        if (all.size >= assignment.annotationsNeeded(item) && SnapshotBuilder.reference(item, all, assignment) == null &&
            store.tasks(t.projectId).none { it.itemId == item.id && it.kind == TaskKind.REVIEW }
        ) {
            store.putTask(Task(ids("tsk"), item.id, t.projectId, TaskKind.REVIEW, TaskStatus.OPEN, createdAt = clock()))
        }
        return a
    }

    fun adjudicate(user: String, taskId: String, transcript: String, entities: List<EntitySpan>): Annotation {
        val t = ownLease(user, taskId)
        if (t.kind != TaskKind.REVIEW) throw Invalid("task $taskId is not a review task")
        authz.require(user, "reviewer", "project:${t.projectId}")
        val item = itemOrThrow(t.itemId)
        AnnotationRules.validate(item, transcript, entities)
        val a = Annotation(ids("ann"), t.id, item.id, user, transcript, entities.sortedBy { it.start }, clock(), 0, final = true)
        store.putAnnotation(a)
        store.putTask(t.copy(status = TaskStatus.SUBMITTED))
        store.putItem(item.copy(referenceText = transcript, referenceEntities = a.entities))
        return a
    }

    // ---------------------------------------------------------------- data exploration
    fun explore(user: String, projectId: String, facility: String? = null, label: Label? = null, status: String? = null, query: String? = null, limit: Int = 50, offset: Int = 0): Pair<Int, List<ExploreRow>> {
        authz.require(user, "viewer", "project:$projectId")
        val anns = store.projectAnnotations(projectId).groupBy { it.itemId }
        val rows = store.items(projectId).map { item ->
            val a = anns[item.id].orEmpty()
            val ref = SnapshotBuilder.reference(item, a, assignment)
            val st = when {
                ref != null -> "done"
                a.isEmpty() -> "unlabelled"
                a.size >= assignment.annotationsNeeded(item) -> "needs review"
                else -> "in progress"
            }
            ExploreRow(item, a.size, st, ref?.let { Wer.errors(it.transcript, item.asrText).rate }, (ref?.entities ?: a.flatMap { it.entities }).map { it.label }.toSet(), ref)
        }.filter { r ->
            (facility == null || r.item.facilityId == facility) && (label == null || label in r.labels) && (status == null || r.status == status) &&
                (query.isNullOrBlank() || r.item.asrText.contains(query, ignoreCase = true))
        }
        return rows.size to rows.drop(offset).take(limit.coerceIn(1, 500))
    }

    fun stats(user: String, projectId: String): ProjectStats {
        authz.require(user, "viewer", "project:$projectId")
        val items = store.items(projectId)
        val tasks = store.tasks(projectId)
        val anns = store.projectAnnotations(projectId)
        val byItem = anns.groupBy { it.itemId }
        val pairs = byItem.values.filter { it.count { a -> !a.final } >= 2 }.map { l -> l.filter { !it.final }.let { it[0] to it[1] } }
        val refs = items.mapNotNull { SnapshotBuilder.reference(it, byItem[it.id].orEmpty(), assignment) }
        val secs = anns.filter { !it.final && it.secondsSpent > 0 }.map { it.secondsSpent }.sorted()
        val now = clock()
        return ProjectStats(
            items = items.size,
            tasksOpen = tasks.count { it.status == TaskStatus.OPEN || (it.status == TaskStatus.LEASED && it.leasedUntil?.isBefore(now) == true) },
            tasksLeased = tasks.count { it.status == TaskStatus.LEASED && it.leasedUntil?.isAfter(now) == true },
            annotations = anns.size,
            done = refs.size,
            needsReview = tasks.count { it.kind == TaskKind.REVIEW && it.status != TaskStatus.SUBMITTED },
            agreement = Agreement.of(pairs),
            labelCounts = refs.flatMap { r -> r.entities.map { it.label } }.groupingBy { it }.eachCount().toSortedMap(),
            medianSecondsPerItem = if (secs.isEmpty()) null else secs[secs.size / 2].toDouble(),
        )
    }

    // ---------------------------------------------------------------- datasets
    fun createSnapshot(user: String, projectId: String): Snapshot {
        val p = projectOrThrow(projectId)
        authz.require(user, "reviewer", "project:$projectId")
        val version = (store.snapshots(projectId).maxOfOrNull { it.version } ?: 0) + 1
        val s = SnapshotBuilder.build(ids("snap"), projectId, version, user, clock(), store.items(projectId), store.projectAnnotations(projectId), assignment)
        if (s.examples.isEmpty()) throw Invalid("no reviewed examples yet; annotate and review some items first")
        store.putSnapshot(s)
        audit.record(user, Actions.SNAPSHOT, p.tenant, "dataset:${s.id}", mapOf("version" to version.toString(), "sha256" to s.manifestSha256, "examples" to s.examples.size.toString()))
        return s
    }

    fun snapshots(user: String, projectId: String): List<Snapshot> {
        authz.require(user, "viewer", "project:$projectId")
        return store.snapshots(projectId)
    }

    // ---------------------------------------------------------------- training
    fun startTraining(user: String, snapshotId: String, model: String, params: Map<String, String>, writeDataset: (Snapshot, java.nio.file.Path) -> Unit): TrainingJob {
        val snap = store.snapshot(snapshotId) ?: throw NotFound("snapshot $snapshotId")
        val p = projectOrThrow(snap.projectId)
        authz.require(user, "reviewer", "project:${p.id}")
        val r = runner ?: throw Invalid("no training runner is configured in this deployment")
        val allowed = setOf("epochs", "c", "buckets")
        params.keys.firstOrNull { it !in allowed }?.let { throw Invalid("unknown parameter '$it' (allowed: ${allowed.joinToString()})") }
        params.forEach { (k, v) -> if (!Regex("[0-9.]{1,12}").matches(v)) throw Invalid("parameter $k must be a number") }
        val job = TrainingJob(ids("job"), p.id, snap.id, snap.manifestSha256, model, params, JobStatus.QUEUED, user, clock())
        store.putJob(job)
        val dataset = exportDir.resolve("${snap.id}.jsonl")
        if (!java.nio.file.Files.exists(dataset)) writeDataset(snap, dataset)
        val out = exportDir.resolve("runs").resolve(job.id)
        r.submit(job, dataset.toString(), out.toString())
        val running = JobStateMachine.transition(job, JobStatus.RUNNING, clock())
        store.putJob(running)
        audit.record(user, Actions.TRAIN, p.tenant, "job:${job.id}", mapOf("dataset" to snap.id, "sha256" to snap.manifestSha256, "model" to model))
        return running
    }

    /** Poll running jobs; register successful models. Called by a scheduler in the API process. */
    fun reconcileJobs(): List<TrainingJob> {
        val r = runner ?: return emptyList()
        return store.jobs(null).filter { it.status == JobStatus.RUNNING }.mapNotNull { job ->
            val st = r.poll(job.id)
            if (st.status == JobStatus.RUNNING) return@mapNotNull null
            val done = JobStateMachine.transition(job, st.status, clock(), st.metrics, st.artifact, st.message)
            store.putJob(done)
            if (done.status == JobStatus.SUCCEEDED && done.artifact != null) {
                val name = "${done.model}-v${store.models(done.projectId).count { it.name.startsWith(done.model) } + 1}"
                store.putModel(RegisteredModel(name, done.projectId, done.id, done.artifact!!, done.snapshotSha256, done.metrics, clock()))
            }
            done
        }
    }

    fun cancelJob(user: String, jobId: String): TrainingJob {
        val job = store.job(jobId) ?: throw NotFound("job $jobId")
        authz.require(user, "reviewer", "project:${job.projectId}")
        runner?.cancel(job.id)
        val c = JobStateMachine.transition(job, JobStatus.CANCELLED, clock(), message = "cancelled by $user")
        store.putJob(c)
        return c
    }

    fun jobs(user: String, projectId: String): List<TrainingJob> {
        authz.require(user, "viewer", "project:$projectId")
        return store.jobs(projectId)
    }

    fun models(user: String, projectId: String): List<RegisteredModel> {
        authz.require(user, "viewer", "project:$projectId")
        return store.models(projectId)
    }

    // ---------------------------------------------------------------- evaluation
    data class EntityEval(val model: String, val examples: Int, val micro: EntityScore.Prf, val perLabel: Map<Label, EntityScore.Prf>, val errors: List<Triple<String, List<EntitySpan>, List<EntitySpan>>>)

    /** Score a registered tagger on a snapshot's test split. */
    fun evaluateTagger(user: String, snapshotId: String, modelName: String): EntityEval {
        val snap = store.snapshot(snapshotId) ?: throw NotFound("snapshot $snapshotId")
        authz.require(user, "viewer", "project:${snap.projectId}")
        val tagger = tagger(snap.projectId, modelName)
        val test = snap.examples.filter { it.split == Split.TEST }
        if (test.isEmpty()) throw Invalid("snapshot ${snap.id} has no test examples")
        val pred = test.map { tagger.predict(it.text) }
        val (micro, per) = EntityScore.score(test.map { it.entities }, pred)
        val errors = test.indices.filter { test[it].entities.toSet() != pred[it].toSet() }.take(10).map { Triple(test[it].text, test[it].entities, pred[it]) }
        return EntityEval(modelName, test.size, micro, per, errors)
    }

    /**
     * Compare two speech models on the snapshot's reviewed transcripts. The speech models are not
     * trained on these splits, so every reviewed example is a fair test item.
     */
    fun compareAsr(user: String, snapshotId: String, modelA: String, modelB: String): ModelComparison.Result {
        val snap = store.snapshot(snapshotId) ?: throw NotFound("snapshot $snapshotId")
        authz.require(user, "viewer", "project:${snap.projectId}")
        val ex = snap.examples.filter { modelA in it.asr && modelB in it.asr }
        if (ex.size < 10) throw Invalid("fewer than 10 reviewed examples have outputs from both $modelA and $modelB")
        return ModelComparison.compare(ex.map { it.text }, ex.map { it.asr.getValue(modelA) }, ex.map { it.asr.getValue(modelB) }, ex.map { it.facilityId })
    }

    // ---------------------------------------------------------------- playground
    fun predict(user: String, projectId: String, modelName: String, text: String): List<EntitySpan> {
        authz.require(user, "viewer", "project:$projectId")
        if (text.length > 5_000) throw Invalid("text too long (max 5000 characters)")
        val phi = detector.detect(text, KnownIdentifiers()).filter { it.kind != workbench.phi.PhiKind.DATE }
        if (phi.isNotEmpty()) throw PhiInInput(phi) // the playground is outside the PHI zone: refuse, never store
        return tagger(projectId, modelName).predict(text)
    }

    private fun suggestions(projectId: String, text: String): List<EntitySpan> {
        val latest = store.models(projectId).firstOrNull() ?: return emptyList()
        return runCatching { tagger(projectId, latest.name).predict(text) }.getOrDefault(emptyList())
    }

    private fun tagger(projectId: String, modelName: String): EntityTagger {
        val m = store.models(projectId).firstOrNull { it.name == modelName } ?: throw NotFound("model $modelName")
        return taggers.getOrPut(m.name) { loadModel(m) ?: throw Invalid("model ${m.name} cannot be loaded") }
    }

    // ---------------------------------------------------------------- PHI access (break-glass)
    fun revealPhi(user: String, itemId: String, reason: String): PhiReveal {
        val item = itemOrThrow(itemId)
        authz.require(user, "phi_viewer", "project:${item.projectId}")
        if (reason.trim().length < 15) throw Invalid("state a reason of at least 15 characters; it is recorded in the audit log")
        val sealed = store.sealed(itemId) ?: throw NotFound("no sealed record for $itemId")
        val e = audit.record(user, Actions.REVEAL, item.tenant, "item:$itemId", mapOf("reason" to reason.trim()))
        return PhiReveal(itemId, String(vault.open(sealed)), e.seq)
    }

    // ---------------------------------------------------------------- administration
    fun grant(admin: String, tuple: Tuple) {
        val project = tuple.obj.removePrefix("project:")
        val p = projectOrThrow(project)
        authz.require(admin, "admin", tuple.obj)
        if (tuple.relation !in setOf("annotator", "reviewer", "viewer", "phi_viewer", "admin")) throw Invalid("unknown relation ${tuple.relation}")
        (authz as? LocalAuthorizer)?.write(tuple) ?: throw Invalid("grants are managed in OpenFGA in this deployment")
        audit.record(admin, Actions.GRANT, p.tenant, tuple.obj, mapOf("user" to tuple.user, "relation" to tuple.relation))
    }

    fun auditLog(user: String, tenant: String, action: String?, limit: Int): Pair<Verification, List<AuditEvent>> {
        authz.require(user, "admin", "tenant:$tenant")
        return audit.verify() to store.audit.query(tenant, action, limit.coerceIn(1, 1000))
    }

    fun checkAccess(user: String, relation: String, obj: String) {
        if (!authz.check(user, relation, obj)) throw Forbidden("$user is not $relation of $obj")
    }
}
