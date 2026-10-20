package workbench

import org.junit.jupiter.api.Test
import workbench.audit.AuditLog
import workbench.authz.LocalAuthorizer
import workbench.authz.Tuple
import workbench.store.InMemoryStore
import workbench.training.IllegalTransition
import workbench.training.JobStateMachine
import workbench.training.JobStatus
import workbench.training.KubernetesJobManifest
import workbench.training.TrainingJob
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GovernanceTest {
    @Test
    fun `audit chain verifies and pinpoints the first tampered event`() {
        val store = InMemoryStore()
        val log = AuditLog(store.audit)
        repeat(5) { log.record("user:a", "phi.reveal", "t1", "item:$it", mapOf("reason" to "care quality check $it")) }
        assertTrue(log.verify().intact)
        store.tamperAudit(3) { it.copy(detail = mapOf("reason" to "nothing to see")) }
        val v = log.verify()
        assertFalse(v.intact)
        assertEquals(3L, v.firstBrokenSeq)
    }

    @Test
    fun `audit chain detects a re-hashed edit through the broken link to the next event`() {
        val store = InMemoryStore()
        val log = AuditLog(store.audit)
        repeat(4) { log.record("user:a", "phi.ingest", "t1", "item:$it") }
        store.tamperAudit(2) { e ->
            val detail = mapOf("forged" to "yes")
            e.copy(detail = detail, hash = AuditLog.hash(e.seq, e.at, e.actor, e.action, e.tenant, e.resource, detail, e.prevHash))
        }
        assertEquals(3L, log.verify().firstBrokenSeq)
    }

    private val authz = LocalAuthorizer(
        listOf(
            Tuple("tenant:t1", "tenant", "project:p1"),
            Tuple("tenant:t2", "tenant", "project:p2"),
            Tuple("user:boss", "admin", "tenant:t1"),
            Tuple("user:rita", "reviewer", "project:p1"),
            Tuple("user:anna", "annotator", "project:p1"),
            Tuple("user:mia", "member", "tenant:t1"),
            Tuple("user:dora", "phi_viewer", "project:p1"),
            Tuple("user:dora", "member", "tenant:t1"),
            Tuple("user:eve", "phi_viewer", "project:p1"), // granted, but not a member of the tenant
            Tuple("project:p1", "project", "dataset:d1"),
        ),
    )

    @Test
    fun `roles inherit as in the OpenFGA model`() {
        assertTrue(authz.check("user:boss", "admin", "project:p1"))
        assertTrue(authz.check("user:boss", "annotator", "project:p1"))
        assertTrue(authz.check("user:rita", "annotator", "project:p1"))
        assertFalse(authz.check("user:anna", "reviewer", "project:p1"))
        assertTrue(authz.check("user:mia", "viewer", "project:p1"))
        assertFalse(authz.check("user:mia", "annotator", "project:p1"))
        assertTrue(authz.check("user:rita", "can_train", "dataset:d1"))
        assertTrue(authz.check("user:anna", "can_read", "dataset:d1"))
        assertFalse(authz.check("user:anna", "can_train", "dataset:d1"))
    }

    @Test
    fun `nobody crosses tenants and PHI access needs both the grant and membership`() {
        assertFalse(authz.check("user:boss", "viewer", "project:p2"))
        assertFalse(authz.check("user:boss", "phi_viewer", "project:p1")) // admins do not get PHI implicitly
        assertTrue(authz.check("user:dora", "phi_viewer", "project:p1"))
        assertFalse(authz.check("user:eve", "phi_viewer", "project:p1"))
    }

    private fun job(status: JobStatus = JobStatus.QUEUED) =
        TrainingJob("job_1", "p1", "snap_1", "abc", "entity-tagger", mapOf("epochs" to "5"), status, "user:rita", Instant.EPOCH)

    @Test
    fun `training jobs follow the state machine`() {
        val r = JobStateMachine.transition(job(), JobStatus.RUNNING, Instant.ofEpochSecond(5))
        val s = JobStateMachine.transition(r, JobStatus.SUCCEEDED, Instant.ofEpochSecond(9), mapOf("f1" to 0.9))
        assertEquals(Instant.ofEpochSecond(5), s.startedAt)
        assertEquals(0.9, s.metrics["f1"])
        assertFailsWith<IllegalTransition> { JobStateMachine.transition(s, JobStatus.RUNNING, Instant.EPOCH) }
        assertFailsWith<IllegalTransition> { JobStateMachine.transition(job(), JobStatus.SUCCEEDED, Instant.EPOCH) }
    }

    @Test
    fun `the Kubernetes job is locked down`() {
        val m = KubernetesJobManifest.build(job(), "registry/trainer:1.0", "ml", "/artifacts/datasets/snap_1.jsonl", "/artifacts/runs/job_1", "eu-central-1").toString()
        for (must in listOf("\"runAsNonRoot\":true", "\"readOnlyRootFilesystem\":true", "\"allowPrivilegeEscalation\":false", "\"drop\":[\"ALL\"]", "\"egress\":\"none\"", "\"activeDeadlineSeconds\":3600", "eu-central-1", "\"serviceAccountName\":\"trainer\"")) {
            assertTrue(must in m, "missing $must")
        }
        assertFalse("raw-phi" in m)
    }
}
