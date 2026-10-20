package workbench

import org.junit.jupiter.api.Test
import workbench.annotation.EntitySpan
import workbench.annotation.Label
import workbench.annotation.TaskKind
import workbench.audit.Actions
import workbench.audit.AuditLog
import workbench.authz.Forbidden
import workbench.authz.LocalAuthorizer
import workbench.dataset.Split
import workbench.ingest.IngestPipeline
import workbench.phi.Deidentifier
import workbench.phi.LocalKeyProvider
import workbench.phi.PhiVault
import workbench.phi.Region
import workbench.service.Conflict
import workbench.service.Invalid
import workbench.service.Workbench
import workbench.store.InMemoryStore
import workbench.synthetic.DemoSeed
import workbench.synthetic.SyntheticExport
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkbenchTest {
    private val store = InMemoryStore()
    private val authz = LocalAuthorizer()
    private val audit = AuditLog(store.audit)
    private val vault = PhiVault(LocalKeyProvider(ByteArray(32) { 7 }))
    private val deid = { t: String -> Deidentifier("demo-key-$t-0123456789abcdef0123".toByteArray()) }
    private val wb = Workbench(store, authz, audit, vault)
    private val pipeline = IngestPipeline(store, vault, deid, audit, Region.EU)
    private val generated = SyntheticExport(seed = 11).generate(SyntheticExport.DEFAULT_TENANTS + SyntheticExport.US_TENANT, 120)

    private fun seeded(): DemoSeed.Report {
        DemoSeed.setup(store, authz)
        return DemoSeed.run(wb, pipeline, generated, deid)
    }

    @Test
    fun `ingest de-identifies everything and refuses US data without a BAA in the EU deployment`() {
        val r = seeded().ingest
        assertEquals(240, r.accepted)
        assertEquals(120, r.quarantined["residency"])
        assertEquals(0, r.leaksCaught)
        val names = generated.flatMap { g -> listOf("resident", "relatives", "staff").flatMap { k -> Regex("\"last_name\":\"(\\w+)\"").findAll(g.record[k].toString()).map { it.groupValues[1] } } }.toSet()
        for (item in store.items("nord-doku") + store.items("sued-doku")) {
            for (t in listOf(item.asrText) + item.predictions.values) {
                names.forEach { assertFalse(Regex("\\b$it\\b").containsMatchIn(t), "real name $it in ${item.id}: $t") }
                assertFalse(Regex("[A-Z]\\d{9}").containsMatchIn(t))
            }
        }
        assertTrue(store.audit.all().count { it.action == Actions.INGEST } == 240)
    }

    @Test
    fun `annotation, double annotation, review and snapshot work end to end`() {
        val r = seeded()
        assertTrue(r.annotated > 100, "annotated ${r.annotated}")
        assertTrue(r.reviewed > 0, "no disagreements were reviewed")
        val stats = wb.stats("user:rita", "nord-doku")
        assertTrue(stats.agreement.items > 5 && stats.agreement.spanF1 > 0.7, "agreement ${stats.agreement}")
        assertTrue(stats.done >= r.annotated / 2)
        val snap = wb.createSnapshot("user:rita", "nord-doku")
        assertTrue(snap.examples.isNotEmpty() && snap.counts.getValue(Split.TEST) > 0)
        assertEquals(snap.manifestSha256, wb.createSnapshot("user:rita", "nord-doku").manifestSha256) // nothing changed, same content
        val cmp = wb.compareAsr("user:rita", snap.id, "asr-v1", "asr-v2")
        assertTrue(cmp.werB < cmp.werA, "asr-v2 should be better: $cmp")
        assertTrue(cmp.ci95.second < 0)
    }

    @Test
    fun `roles are enforced across the workflow`() {
        seeded()
        assertFailsWith<Forbidden> { wb.nextTask("user:anna", "nord-doku", TaskKind.REVIEW) }
        assertFailsWith<Forbidden> { wb.createSnapshot("user:anna", "nord-doku") }
        assertFailsWith<Forbidden> { wb.explore("user:anna", "sued-doku") } // other tenant
        assertFailsWith<Forbidden> { wb.auditLog("user:rita", "pflege-nord", null, 10) }
        assertTrue(wb.projects("user:anna").map { it.id } == listOf("nord-doku"))
        val view = assertNotNull(wb.nextTask("user:anna", "nord-doku"))
        assertFailsWith<Conflict> { wb.submit("user:ben", view.task.id, view.item.asrText, emptyList(), 10) } // not ben's lease
    }

    @Test
    fun `PHI reveal is break-glass only, needs a reason and is audited`() {
        seeded()
        val item = store.items("nord-doku").first()
        assertFailsWith<Forbidden> { wb.revealPhi("user:olivia", item.id, "tenant admin wants to look") } // admin is not phi_viewer
        assertFailsWith<Invalid> { wb.revealPhi("user:dora", item.id, "because") }
        val r = wb.revealPhi("user:dora", item.id, "resident complaint #4711, checking transcript")
        assertContains(r.original, "\"last_name\"")
        val (verification, events) = wb.auditLog("user:olivia", "pflege-nord", Actions.REVEAL, 10)
        assertTrue(verification.intact)
        assertEquals("user:dora", events.first().actor)
        assertEquals("resident complaint #4711, checking transcript", events.first().detail["reason"])
    }

    @Test
    fun `explore filters and paginates`() {
        seeded()
        val (total, rows) = wb.explore("user:anna", "nord-doku", status = "done", limit = 5)
        assertTrue(total > 5 && rows.size == 5)
        assertTrue(rows.all { it.status == "done" && it.wer != null })
        val (_, pain) = wb.explore("user:anna", "nord-doku", label = Label.PAIN, limit = 500)
        assertTrue(pain.all { Label.PAIN in it.labels })
    }

    @Test
    fun `the playground refuses text that contains PHI`() {
        seeded()
        assertFailsWith<workbench.service.PhiInInput> { wb.predict("user:anna", "nord-doku", "any", "Herr Meyer, Tel 0171 2233445, klagt über Schmerzen") }
    }

    @Test
    fun `annotations that would reintroduce PHI are rejected`() {
        seeded()
        val view = assertNotNull(wb.nextTask("user:anna", "nord-doku"))
        assertFailsWith<workbench.annotation.InvalidAnnotation> {
            wb.submit("user:anna", view.task.id, view.item.asrText + " Rückruf unter 0171 2233445.", listOf(EntitySpan(0, 4, Label.MOOD)), 30)
        }
    }
}
