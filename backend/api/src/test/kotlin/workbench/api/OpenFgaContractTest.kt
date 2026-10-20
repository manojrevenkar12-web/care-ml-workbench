package workbench.api

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import workbench.authz.LocalAuthorizer
import workbench.authz.OpenFgaAuthorizer
import workbench.authz.OpenFgaBootstrap
import workbench.authz.Tuple
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** The in-process evaluator and the real OpenFGA server must agree on every check. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenFgaContractTest {
    private val fga = GenericContainer("openfga/openfga:v1.21.0").withCommand("run").withExposedPorts(8080).waitingFor(Wait.forHttp("/healthz").forPort(8080))

    @BeforeAll
    fun start() = fga.start()

    @AfterAll
    fun stop() = fga.stop()

    private val tuples = listOf(
        Tuple("tenant:t1", "tenant", "project:p1"),
        Tuple("tenant:t2", "tenant", "project:p2"),
        Tuple("user:boss", "admin", "tenant:t1"),
        Tuple("user:rita", "reviewer", "project:p1"),
        Tuple("user:anna", "annotator", "project:p1"),
        Tuple("user:mia", "member", "tenant:t1"),
        Tuple("user:dora", "phi_viewer", "project:p1"),
        Tuple("user:dora", "member", "tenant:t1"),
        Tuple("user:eve", "phi_viewer", "project:p1"),
        Tuple("project:p1", "project", "dataset:d1"),
    )

    @Test
    fun `local evaluation matches OpenFGA`() {
        val url = "http://${fga.host}:${fga.getMappedPort(8080)}"
        val model = Files.readString(Path.of("../../fga/model.json"))
        assertEquals(model.trim(), javaClass.getResource("/fga-model.json")!!.readText().trim(), "fga-model.json must be a copy of fga/model.json")
        val (store, modelId) = OpenFgaBootstrap.ensure(url, "contract", model)
        val remote = OpenFgaAuthorizer(url, store, modelId)
        remote.write(tuples)
        val local = LocalAuthorizer(tuples)
        val users = listOf("boss", "rita", "anna", "mia", "dora", "eve", "nobody").map { "user:$it" }
        var cases = 0
        for (u in users) {
            for (rel in listOf("admin", "reviewer", "annotator", "viewer", "phi_viewer")) for (obj in listOf("project:p1", "project:p2")) {
                assertEquals(local.check(u, rel, obj), remote.check(u, rel, obj), "$u $rel $obj"); cases++
            }
            for (rel in listOf("can_read", "can_train")) {
                assertEquals(local.check(u, rel, "dataset:d1"), remote.check(u, rel, "dataset:d1"), "$u $rel dataset:d1"); cases++
            }
            for (rel in listOf("admin", "member")) {
                assertEquals(local.check(u, rel, "tenant:t1"), remote.check(u, rel, "tenant:t1"), "$u $rel tenant:t1"); cases++
            }
        }
        assertEquals(126, cases)
    }
}
