package workbench.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import workbench.annotation.TaskKind
import workbench.audit.AuditLog
import workbench.authz.LocalAuthorizer
import workbench.ingest.IngestPipeline
import workbench.phi.Deidentifier
import workbench.phi.LocalKeyProvider
import workbench.phi.PhiVault
import workbench.phi.Region
import workbench.service.Workbench
import workbench.store.Migrations
import workbench.store.PostgresStore
import workbench.synthetic.DemoSeed
import workbench.synthetic.SyntheticExport
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresStoreTest {
    private val pg = PostgreSQLContainer("postgres:16-alpine")
    private lateinit var ds: HikariDataSource
    private lateinit var store: PostgresStore

    @BeforeAll
    fun start() {
        pg.start()
        ds = HikariDataSource(HikariConfig().apply { jdbcUrl = pg.jdbcUrl; username = pg.username; password = pg.password; maximumPoolSize = 8 })
        Migrations.apply(ds)
        Migrations.apply(ds) // idempotent
        store = PostgresStore(ds)
        val authz = LocalAuthorizer()
        DemoSeed.setup(store, authz)
        val audit = AuditLog(store.audit)
        val vault = PhiVault(LocalKeyProvider(ByteArray(32) { 1 }))
        val deid = { t: String -> Deidentifier("test-$t-key-0123456789abcdef01234".toByteArray()) }
        val wb = Workbench(store, authz, audit, vault)
        DemoSeed.run(wb, IngestPipeline(store, vault, deid, audit, Region.EU), SyntheticExport(seed = 3).generate(SyntheticExport.DEFAULT_TENANTS, 60), deid, annotateShare = 0.5)
    }

    @AfterAll
    fun stop() {
        ds.close()
        pg.stop()
    }

    @Test
    fun `concurrent annotators never receive the same task`() {
        val pool = Executors.newFixedThreadPool(8)
        val now = Instant.now()
        val leased = (1..8).map { k -> pool.submit(Callable { store.leaseNext("sued-doku", TaskKind.ANNOTATE, "user:t$k", now, Duration.ofMinutes(5)) }) }.mapNotNull { it.get() }
        pool.shutdown()
        assertEquals(8, leased.size)
        assertEquals(8, leased.map { it.id }.toSet().size)
    }

    @Test
    fun `the audit table is append-only and the chain verifies`() {
        assertTrue(AuditLog(store.audit).verify().intact)
        assertFailsWith<java.sql.SQLException> { ds.connection.use { it.createStatement().execute("UPDATE audit_events SET actor = 'x' WHERE seq = 1") } }
        assertFailsWith<java.sql.SQLException> { ds.connection.use { it.createStatement().execute("DELETE FROM audit_events WHERE seq = 1") } }
    }

    @Test
    fun `data round-trips through PostgreSQL`() {
        val items = store.items("nord-doku")
        assertEquals(60, items.size)
        val one = items.first()
        assertEquals(one, store.item(one.id))
        assertTrue(store.sealed(one.id) != null)
        assertTrue(store.projectAnnotations("nord-doku").isNotEmpty())
    }
}
