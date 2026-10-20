package workbench.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import workbench.annotation.Agreement
import workbench.annotation.Annotation
import workbench.annotation.EntitySpan
import workbench.annotation.Item
import workbench.annotation.Label
import workbench.annotation.Task
import workbench.annotation.TaskKind
import workbench.annotation.TaskStatus
import workbench.audit.AuditEvent
import workbench.audit.Verification
import workbench.dataset.Example
import workbench.dataset.Snapshot
import workbench.dataset.Split
import workbench.eval.EntityScore
import workbench.eval.ModelComparison
import workbench.phi.PhiKind
import workbench.phi.SurrogateSpan
import workbench.service.ExploreRow
import workbench.service.ProjectStats
import workbench.service.TaskView
import workbench.service.Workbench
import workbench.store.Project
import workbench.store.RegisteredModel
import workbench.training.JobStatus
import workbench.training.TrainingJob
import java.time.Instant

/**
 * JSON for the API and for the JSON columns in PostgreSQL. Hand-written over JsonElement so the
 * domain stays free of serialisation annotations; field names are snake_case, the API contract
 * the frontend's `api.ts` types mirror.
 */
object Codec {
    private fun num(d: Double?): JsonElement = if (d == null || d.isNaN()) JsonNull else JsonPrimitive(Math.round(d * 10_000) / 10_000.0)

    private fun str(s: String?): JsonElement = s?.let { JsonPrimitive(it) } ?: JsonNull

    fun strings(m: Map<String, String>) = JsonObject(m.mapValues { JsonPrimitive(it.value) })

    fun numbers(m: Map<String, Double>) = JsonObject(m.mapValues { num(it.value) })

    fun spans(xs: List<EntitySpan>) = JsonArray(xs.map { buildJsonObject { put("start", it.start); put("end", it.end); put("label", it.label.name) } })

    fun parseSpans(e: JsonElement?): List<EntitySpan> = (e as? JsonArray).orEmpty().map {
        val o = it.jsonObject
        val label = o["label"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("span without label")
        EntitySpan(o["start"]!!.jsonPrimitive.int, o["end"]!!.jsonPrimitive.int, runCatching { Label.valueOf(label) }.getOrElse { throw IllegalArgumentException("unknown label $label") })
    }

    fun surrogates(xs: List<SurrogateSpan>) = JsonArray(xs.map { buildJsonObject { put("start", it.start); put("end", it.end); put("kind", it.kind.name) } })

    fun parseSurrogates(e: JsonElement?): List<SurrogateSpan> = (e as? JsonArray).orEmpty().map {
        val o = it.jsonObject
        SurrogateSpan(o["start"]!!.jsonPrimitive.int, o["end"]!!.jsonPrimitive.int, PhiKind.valueOf(o["kind"]!!.jsonPrimitive.content))
    }

    fun parseStrings(e: JsonElement?): Map<String, String> = (e as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }

    fun parseNumbers(e: JsonElement?): Map<String, Double> = (e as? JsonObject).orEmpty().mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }.toMap()

    fun project(p: Project, permissions: Map<String, Boolean>? = null) = buildJsonObject {
        put("id", p.id)
        put("tenant", p.tenant)
        put("name", p.name)
        put("description", p.description)
        permissions?.let { put("permissions", JsonObject(it.mapValues { (_, v) -> JsonPrimitive(v) })) }
    }

    fun item(i: Item) = buildJsonObject {
        put("id", i.id)
        put("project_id", i.projectId)
        put("facility_id", i.facilityId)
        put("subject", i.subject)
        put("recorded_at", i.recordedAt.toString())
        put("asr_model", i.asrModel)
        put("asr_text", i.asrText)
        put("surrogates", surrogates(i.surrogates))
        put("priority", i.priority)
        put("reference_text", str(i.referenceText))
        put("reference_entities", i.referenceEntities?.let { spans(it) } ?: JsonNull)
        put("predictions", strings(i.predictions))
    }

    fun task(t: Task) = buildJsonObject {
        put("id", t.id)
        put("item_id", t.itemId)
        put("project_id", t.projectId)
        put("kind", t.kind.name)
        put("status", t.status.name)
        put("assignee", str(t.assignee))
        put("leased_until", str(t.leasedUntil?.toString()))
        put("created_at", t.createdAt.toString())
    }

    fun parseTask(o: JsonObject) = Task(
        o["id"]!!.jsonPrimitive.content, o["item_id"]!!.jsonPrimitive.content, o["project_id"]!!.jsonPrimitive.content,
        TaskKind.valueOf(o["kind"]!!.jsonPrimitive.content), TaskStatus.valueOf(o["status"]!!.jsonPrimitive.content),
        o["assignee"]?.jsonPrimitive?.contentOrNull, o["leased_until"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse), Instant.parse(o["created_at"]!!.jsonPrimitive.content),
    )

    fun annotation(a: Annotation) = buildJsonObject {
        put("id", a.id)
        put("task_id", a.taskId)
        put("item_id", a.itemId)
        put("annotator", a.annotator)
        put("transcript", a.transcript)
        put("entities", spans(a.entities))
        put("submitted_at", a.submittedAt.toString())
        put("seconds_spent", a.secondsSpent)
        put("final", a.final)
    }

    fun taskView(v: TaskView) = buildJsonObject {
        put("task", task(v.task))
        put("item", item(v.item))
        put("existing", JsonArray(v.existing.map(::annotation)))
        put("suggestions", spans(v.suggestions))
    }

    fun exploreRow(r: ExploreRow) = buildJsonObject {
        put("item", item(r.item))
        put("annotations", r.annotations)
        put("status", r.status)
        put("wer", num(r.wer))
        put("labels", JsonArray(r.labels.sorted().map { JsonPrimitive(it.name) }))
    }

    fun agreement(a: Agreement.Result) = buildJsonObject {
        put("items", a.items)
        put("span_f1", num(a.spanF1))
        put("overlap_f1", num(a.overlapF1))
        put("token_kappa", num(a.tokenKappa))
        put("transcript_wer_between", num(a.transcriptWerBetween))
    }

    fun stats(s: ProjectStats) = buildJsonObject {
        put("items", s.items)
        put("tasks_open", s.tasksOpen)
        put("tasks_leased", s.tasksLeased)
        put("annotations", s.annotations)
        put("done", s.done)
        put("needs_review", s.needsReview)
        put("agreement", agreement(s.agreement))
        put("label_counts", JsonObject(s.labelCounts.mapKeys { it.key.name }.mapValues { JsonPrimitive(it.value) }))
        put("median_seconds_per_item", num(s.medianSecondsPerItem))
    }

    fun snapshot(s: Snapshot, withExamples: Boolean = false) = buildJsonObject {
        put("id", s.id)
        put("project_id", s.projectId)
        put("version", s.version)
        put("created_by", s.createdBy)
        put("created_at", s.createdAt.toString())
        put("manifest_sha256", s.manifestSha256)
        put("counts", JsonObject(s.counts.mapKeys { it.key.name.lowercase() }.mapValues { JsonPrimitive(it.value) }))
        put("label_counts", JsonObject(s.labelCounts.mapValues { JsonPrimitive(it.value) }))
        put("excluded", JsonObject(s.excluded.mapValues { JsonPrimitive(it.value) }))
        if (withExamples) put("examples", JsonArray(s.examples.map(::example)))
    }

    fun example(e: Example) = buildJsonObject {
        put("item_id", e.itemId)
        put("split", e.split.name.lowercase())
        put("facility_id", e.facilityId)
        put("subject", e.subject)
        put("text", e.text)
        put("entities", spans(e.entities))
        put("asr", strings(e.asr))
    }

    fun parseExample(o: JsonObject) = Example(
        o["item_id"]!!.jsonPrimitive.content, Split.valueOf(o["split"]!!.jsonPrimitive.content.uppercase()), o["facility_id"]!!.jsonPrimitive.content,
        o["subject"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content, parseSpans(o["entities"]), parseStrings(o["asr"]),
    )

    fun job(j: TrainingJob) = buildJsonObject {
        put("id", j.id)
        put("project_id", j.projectId)
        put("snapshot_id", j.snapshotId)
        put("snapshot_sha256", j.snapshotSha256)
        put("model", j.model)
        put("params", strings(j.params))
        put("status", j.status.name)
        put("created_by", j.createdBy)
        put("created_at", j.createdAt.toString())
        put("started_at", str(j.startedAt?.toString()))
        put("finished_at", str(j.finishedAt?.toString()))
        put("metrics", numbers(j.metrics))
        put("artifact", str(j.artifact))
        put("message", str(j.message))
    }

    fun parseJob(o: JsonObject) = TrainingJob(
        o["id"]!!.jsonPrimitive.content, o["project_id"]!!.jsonPrimitive.content, o["snapshot_id"]!!.jsonPrimitive.content, o["snapshot_sha256"]!!.jsonPrimitive.content,
        o["model"]!!.jsonPrimitive.content, parseStrings(o["params"]), JobStatus.valueOf(o["status"]!!.jsonPrimitive.content), o["created_by"]!!.jsonPrimitive.content,
        Instant.parse(o["created_at"]!!.jsonPrimitive.content), o["started_at"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
        o["finished_at"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse), parseNumbers(o["metrics"]), o["artifact"]?.jsonPrimitive?.contentOrNull, o["message"]?.jsonPrimitive?.contentOrNull,
    )

    fun model(m: RegisteredModel) = buildJsonObject {
        put("name", m.name)
        put("project_id", m.projectId)
        put("job_id", m.jobId)
        put("snapshot_sha256", m.snapshotSha256)
        put("metrics", numbers(m.metrics))
        put("created_at", m.createdAt.toString())
    }

    fun prf(p: EntityScore.Prf) = buildJsonObject {
        put("precision", num(p.precision))
        put("recall", num(p.recall))
        put("f1", num(p.f1))
        put("support", p.support)
    }

    fun entityEval(e: Workbench.EntityEval) = buildJsonObject {
        put("model", e.model)
        put("examples", e.examples)
        put("micro", prf(e.micro))
        put("per_label", JsonObject(e.perLabel.mapKeys { it.key.name }.mapValues { prf(it.value) }))
        put("errors", JsonArray(e.errors.map { (t, g, p) -> buildJsonObject { put("text", t); put("gold", spans(g)); put("predicted", spans(p)) } }))
    }

    fun comparison(c: ModelComparison.Result, a: String, b: String) = buildJsonObject {
        put("model_a", a)
        put("model_b", b)
        put("items", c.items)
        put("wer_a", num(c.werA))
        put("wer_b", num(c.werB))
        put("difference", num(c.difference))
        put("ci95", buildJsonArray { add(num(c.ci95.first)); add(num(c.ci95.second)) })
        put("by_slice", JsonObject(c.bySlice.mapValues { (_, v) -> buildJsonObject { put("wer_a", num(v.first)); put("wer_b", num(v.second)) } }))
    }

    fun audit(e: AuditEvent) = buildJsonObject {
        put("seq", e.seq)
        put("at", e.at.toString())
        put("actor", e.actor)
        put("action", e.action)
        put("tenant", e.tenant)
        put("resource", e.resource)
        put("detail", strings(e.detail))
        put("hash", e.hash)
    }

    fun verification(v: Verification) = buildJsonObject {
        put("intact", v.intact)
        put("events", v.events)
        put("first_broken_seq", v.firstBrokenSeq?.let { JsonPrimitive(it) } ?: JsonNull)
        put("reason", str(v.reason))
    }
}
