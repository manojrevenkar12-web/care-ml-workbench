package workbench.synthetic

import workbench.annotation.TaskKind
import workbench.authz.LocalAuthorizer
import workbench.authz.Tuple
import workbench.ingest.CareRecord
import workbench.ingest.IngestPipeline
import workbench.ingest.IngestReport
import workbench.phi.Deidentifier
import workbench.phi.Region
import workbench.phi.TenantPolicy
import workbench.service.Workbench
import workbench.store.Project
import workbench.store.Store
import kotlin.random.Random

/**
 * Populates a workbench the way a real rollout would look after a few weeks: two EU tenants with
 * projects and a labelling team, a US tenant without a BAA (its export is refused), records
 * ingested, most items annotated by simulated annotators through the real service API, and the
 * disagreements adjudicated by a reviewer.
 */
object DemoSeed {
    data class Report(val ingest: IngestReport, val annotated: Int, val reviewed: Int, val rejectedByValidation: Int)

    fun setup(store: Store, authz: LocalAuthorizer) {
        store.putTenant(TenantPolicy("pflege-nord", Region.EU, baaSigned = false))
        store.putTenant(TenantPolicy("pflege-sued", Region.EU, baaSigned = false))
        store.putTenant(TenantPolicy("carehome-us", Region.US, baaSigned = false))
        store.putProject(Project("nord-doku", "pflege-nord", "Pflegedokumentation Nord", "Transcript correction and care-entity labelling for the north region"))
        store.putProject(Project("sued-doku", "pflege-sued", "Pflegedokumentation Süd", "Transcript correction and care-entity labelling for the south region"))
        store.putProject(Project("us-docs", "carehome-us", "US care notes", "Blocked until a BAA is signed"))
        for ((p, t) in listOf("nord-doku" to "pflege-nord", "sued-doku" to "pflege-sued", "us-docs" to "carehome-us")) authz.write(Tuple("tenant:$t", "tenant", "project:$p"))
        authz.write(Tuple("user:olivia", "admin", "tenant:pflege-nord"))
        authz.write(Tuple("user:rita", "reviewer", "project:nord-doku"))
        for (u in listOf("anna", "ben", "chris")) authz.write(Tuple("user:$u", "annotator", "project:nord-doku"))
        authz.write(Tuple("user:sam", "admin", "tenant:pflege-sued"))
        authz.write(Tuple("user:dora", "member", "tenant:pflege-nord"))
        authz.write(Tuple("user:dora", "phi_viewer", "project:nord-doku"))
    }

    fun run(
        wb: Workbench,
        pipeline: IngestPipeline,
        generated: List<SyntheticExport.Generated>,
        deidFor: (String) -> Deidentifier,
        projectId: String = "nord-doku",
        annotateShare: Double = 0.85,
        seed: Int = 5,
        leaveForReview: Int = 0, // disagreements left open so the review screen has work in a demo
    ): Report {
        val report = pipeline.ingest(generated.asSequence().map { it.record.toString() to it.record }, "service:etl")
        val gold = generated.associateBy { IngestPipeline.itemIdFor(it.gold.tenant, it.gold.recordId) }
        val team = SyntheticAnnotator.team(seed)
        val rnd = Random(seed)
        val items = wb.store.items(projectId)
        val target = (items.size * annotateShare).toInt()
        val touched = mutableSetOf<String>()
        var annotated = 0
        var rejected = 0
        var idle = 0
        var turn = 0
        while (idle < team.size) {
            val a = team[turn++ % team.size]
            val view = wb.nextTask(a.user, projectId)
            if (view == null || (view.item.id !in touched && touched.size >= target)) {
                idle++
                continue
            }
            idle = 0
            touched += view.item.id
            val (deidGold, entities) = deidentifiedGold(gold.getValue(view.item.id), deidFor)
            val (text, spans) = a.annotate(deidGold, entities)
            try {
                wb.submit(a.user, view.task.id, text, spans, rnd.nextInt(25, 190))
                annotated++
            } catch (e: workbench.annotation.InvalidAnnotation) {
                rejected++
                wb.store.putTask(view.task.copy(status = workbench.annotation.TaskStatus.SUBMITTED)) // drop it from the demo queue
            }
        }
        var reviewed = 0
        val toReview = wb.store.tasks(projectId).count { it.kind == TaskKind.REVIEW && it.status == workbench.annotation.TaskStatus.OPEN } - leaveForReview
        while (reviewed < toReview) {
            val view = wb.nextTask("user:rita", projectId, TaskKind.REVIEW) ?: break
            val (deidGold, entities) = deidentifiedGold(gold.getValue(view.item.id), deidFor)
            wb.adjudicate("user:rita", view.task.id, deidGold, entities)
            reviewed++
        }
        return Report(report, annotated, reviewed, rejected)
    }

    /** The gold transcript as an annotator would see it: de-identified with the record's identifiers. */
    fun deidentifiedGold(g: SyntheticExport.Generated, deidFor: (String) -> Deidentifier): Pair<String, List<workbench.annotation.EntitySpan>> {
        val rec = CareRecord.parse(g.record)
        val d = deidFor(rec.tenant).deidentify(g.gold.text, rec.known(), rec.residentId, rec.recordedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate())
        return d.text to SyntheticAnnotator.relocate(g.gold.text, g.gold.entities, d.text)
    }
}
