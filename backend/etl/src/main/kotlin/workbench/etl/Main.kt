package workbench.etl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import workbench.audit.AuditLog
import workbench.authz.LocalAuthorizer
import workbench.dataset.Export
import workbench.ingest.IngestPipeline
import workbench.json.Codec
import workbench.phi.Deidentifier
import workbench.phi.LocalKeyProvider
import workbench.phi.PhiVault
import workbench.phi.Region
import workbench.service.Workbench
import workbench.store.InMemoryStore
import workbench.store.Migrations
import workbench.store.PostgresStore
import workbench.store.Store
import workbench.synthetic.DemoSeed
import workbench.synthetic.SyntheticExport
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * ETL entry point (runs as a Kubernetes CronJob in production):
 *   generate --out DIR [--records N]          synthetic customer exports (records.jsonl)
 *   ingest   --input FILE [--jdbc URL]        validate, check residency, de-identify, seal, queue
 *   demo     --out DIR [--records N]          full in-memory workflow; writes the dataset and a report
 * Configuration that carries secrets comes from the environment, never from arguments:
 *   WORKBENCH_REGION (EU|US), WORKBENCH_MASTER_KEY (hex, ≥ 32 bytes; KMS in production),
 *   WORKBENCH_DEID_KEY_<TENANT> or WORKBENCH_DEID_KEY (hex), JDBC_USER / JDBC_PASSWORD.
 */
fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: usage()
    val opts = args.drop(1).chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    when (cmd) {
        "generate" -> generate(Path.of(opts["out"] ?: usage()), opts["records"]?.toInt() ?: 200)
        "ingest" -> ingest(Path.of(opts["input"] ?: usage()), opts["jdbc"])
        "demo" -> demo(Path.of(opts["out"] ?: usage()), opts["records"]?.toInt() ?: 300)
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("usage: etl generate --out DIR [--records N] | ingest --input FILE [--jdbc URL] | demo --out DIR [--records N]")
    exitProcess(2)
}

fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Per-tenant de-identification keys from the environment (dev default only outside production). */
fun deidKeys(env: Map<String, String> = System.getenv()): (String) -> Deidentifier = { tenant ->
    val k = env["WORKBENCH_DEID_KEY_" + tenant.uppercase().replace('-', '_')] ?: env["WORKBENCH_DEID_KEY"]
    val key = k?.let(::hex) ?: run {
        check(env["WORKBENCH_ENV"] != "production") { "no de-identification key configured for tenant $tenant" }
        "dev-only-key-$tenant-not-for-real-data".toByteArray()
    }
    Deidentifier(key)
}

fun masterKey(env: Map<String, String> = System.getenv()): ByteArray = env["WORKBENCH_MASTER_KEY"]?.let(::hex) ?: run {
    check(env["WORKBENCH_ENV"] != "production") { "WORKBENCH_MASTER_KEY is required in production" }
    ByteArray(32) { (it * 7 + 3).toByte() }
}

/** Minimal JDBC DataSource (the driver is on the runtime classpath). */
class JdbcDataSource(private val url: String, private val user: String?, private val password: String?) : DataSource {
    override fun getConnection(): Connection = DriverManager.getConnection(url, user, password)
    override fun getConnection(u: String?, p: String?): Connection = DriverManager.getConnection(url, u, p)
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) {}
    override fun setLoginTimeout(seconds: Int) {}
    override fun getLoginTimeout() = 0
    override fun getParentLogger(): Logger = Logger.getGlobal()
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw java.sql.SQLException("not a wrapper")
    override fun isWrapperFor(iface: Class<*>?) = false
}

private fun generate(out: Path, n: Int) {
    Files.createDirectories(out)
    val g = SyntheticExport().generate(SyntheticExport.DEFAULT_TENANTS + SyntheticExport.US_TENANT, n)
    Files.newBufferedWriter(out.resolve("records.jsonl")).use { w -> g.forEach { w.write(it.record.toString()); w.newLine() } }
    println("wrote ${g.size} records to ${out.resolve("records.jsonl")}")
}

private fun ingest(input: Path, jdbc: String?) {
    val store: Store = if (jdbc != null) {
        val ds = JdbcDataSource(jdbc, System.getenv("JDBC_USER"), System.getenv("JDBC_PASSWORD"))
        Migrations.apply(ds)
        PostgresStore(ds)
    } else {
        InMemoryStore()
    }
    if (store.tenants().isEmpty()) DemoSeed.setup(store, LocalAuthorizer())
    val region = Region.valueOf(System.getenv("WORKBENCH_REGION") ?: "EU")
    val pipeline = IngestPipeline(store, PhiVault(LocalKeyProvider(masterKey())), deidKeys(), AuditLog(store.audit), region)
    // streamed line by line: exports can be large
    val report = Files.newBufferedReader(input).useLines { lines ->
        pipeline.ingest(lines.filter { it.isNotBlank() }.map { l -> l to runCatching<JsonElement> { Json.parseToJsonElement(l) }.getOrNull() }, "service:etl")
    }
    println(report)
}

private fun demo(out: Path, n: Int) {
    val store = InMemoryStore()
    val authz = LocalAuthorizer()
    val audit = AuditLog(store.audit)
    val vault = PhiVault(LocalKeyProvider(masterKey()))
    val deid = deidKeys()
    val wb = Workbench(store, authz, audit, vault)
    DemoSeed.setup(store, authz)
    val generated = SyntheticExport().generate(SyntheticExport.DEFAULT_TENANTS + SyntheticExport.US_TENANT, n)
    val t0 = System.nanoTime()
    val seed = DemoSeed.run(wb, IngestPipeline(store, vault, deid, audit, Region.EU), generated, deid)
    val snap = wb.createSnapshot("user:rita", "nord-doku")
    Export.writeJsonl(snap, out.resolve("dataset.jsonl"))
    val cmp = wb.compareAsr("user:rita", snap.id, "asr-v1", "asr-v2")
    val stats = wb.stats("user:rita", "nord-doku")
    val report = buildJsonObject {
        put("records", generated.size)
        put("accepted", seed.ingest.accepted)
        put("quarantined", Codec.strings(seed.ingest.quarantined.mapValues { it.value.toString() }))
        put("phi_replaced", Codec.strings(seed.ingest.phiFound.mapKeys { it.key.name }.mapValues { it.value.toString() }))
        put("leaks_caught", seed.ingest.leaksCaught)
        put("annotations", seed.annotated)
        put("reviewed", seed.reviewed)
        put("stats", Codec.stats(stats))
        put("snapshot", Codec.snapshot(snap))
        put("asr_comparison", Codec.comparison(cmp, "asr-v1", "asr-v2"))
        put("audit_events", store.audit.all().size)
        put("audit_intact", audit.verify().intact)
        put("seconds", (System.nanoTime() - t0) / 1e9)
    }
    Files.writeString(out.resolve("demo_report.json"), report.toString())
    println(report)
}
