package workbench.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import workbench.audit.AuditLog
import workbench.authz.Authorizer
import workbench.authz.LocalAuthorizer
import workbench.authz.OpenFgaAuthorizer
import workbench.authz.OpenFgaBootstrap
import workbench.etl.deidKeys
import workbench.etl.masterKey
import workbench.ingest.IngestPipeline
import workbench.json.Codec
import workbench.model.EntityTagger
import workbench.phi.LocalKeyProvider
import workbench.phi.PhiVault
import workbench.service.Workbench
import workbench.store.InMemoryStore
import workbench.store.Migrations
import workbench.store.PostgresStore
import workbench.store.RegisteredModel
import workbench.store.Store
import workbench.synthetic.DemoSeed
import workbench.synthetic.SyntheticExport
import workbench.training.JobRunner
import workbench.training.KubernetesRunner
import workbench.training.LocalProcessRunner
import java.nio.file.Files
import java.nio.file.Path

class App(val config: Config, val workbench: Workbench, val store: Store, val dataSource: HikariDataSource?)

object Wiring {
    private val log = LoggerFactory.getLogger(Wiring::class.java)

    fun metricsOf(json: String): Map<String, Double> = Codec.parseNumbers(Json.parseToJsonElement(json))

    fun loadTagger(m: RegisteredModel): EntityTagger? = runCatching {
        val o = Json.parseToJsonElement(Files.readString(Path.of(m.artifact))).jsonObject
        EntityTagger.fromBase64(
            m.name,
            o["labels"]!!.jsonArray.map { it.jsonPrimitive.content },
            o["buckets"]!!.jsonPrimitive.int,
            o["weights"]!!.jsonPrimitive.content,
            o["intercept"]!!.jsonPrimitive.content,
        )
    }.onFailure { log.warn("cannot load model {}: {}", m.name, it.toString()) }.getOrNull()

    fun build(cfg: Config): App {
        cfg.validate()
        val ds = cfg.databaseUrl?.let { url ->
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = cfg.databaseUser
                    password = cfg.databasePassword
                    maximumPoolSize = 10
                    poolName = "workbench"
                },
            ).also { Migrations.apply(it) }
        }
        val store: Store = ds?.let { PostgresStore(it) } ?: InMemoryStore()
        val local = LocalAuthorizer()
        val freshDemo = cfg.seedDemo && store.tenants().isEmpty()
        if (cfg.seedDemo) DemoSeed.setup(store, local) // idempotent: tenants, projects and the demo team's grants
        val authz: Authorizer = when {
            cfg.openFgaUrl == null -> local
            cfg.openFgaStoreId != null -> OpenFgaAuthorizer(cfg.openFgaUrl, cfg.openFgaStoreId, cfg.openFgaModelId)
            else -> {
                check(!cfg.production) { "OPENFGA_STORE_ID is required in production" }
                val model = Wiring::class.java.getResource("/fga-model.json")!!.readText()
                val (storeId, modelId) = OpenFgaBootstrap.ensure(cfg.openFgaUrl, "care-ml-workbench-dev", model)
                OpenFgaAuthorizer(cfg.openFgaUrl, storeId, modelId).also { fga ->
                    if (cfg.seedDemo) runCatching { fga.write(local.all().toList()) }.onFailure { log.info("demo tuples already present") }
                    log.info("OpenFGA store {} model {}", storeId, modelId)
                }
            }
        }
        val vault = PhiVault(LocalKeyProvider(masterKey()))
        val artifacts = Path.of(cfg.artifactDir).toAbsolutePath()
        Files.createDirectories(artifacts)
        val runner: JobRunner? = when (cfg.trainerMode) {
            "local" -> LocalProcessRunner(cfg.trainerCommand, Path.of("."), ::metricsOf)
            "kubernetes" -> KubernetesRunner(requireNotNull(cfg.trainerImage) { "TRAINER_IMAGE is required" }, cfg.namespace, regionLabel(cfg), parseMetrics = ::metricsOf)
            else -> null
        }
        val audit = AuditLog(store.audit)
        val wb = Workbench(store, authz, audit, vault, runner, artifacts.resolve("datasets"), ::loadTagger)
        if (freshDemo) {
            // the demo team works through the service with local grants (identical to the OpenFGA tuples)
            val demoWb = Workbench(store, local, audit, vault, null, artifacts.resolve("datasets"), ::loadTagger)
            val deid = deidKeys()
            val r = DemoSeed.run(demoWb, IngestPipeline(store, vault, deid, audit, cfg.region), SyntheticExport().generate(SyntheticExport.DEFAULT_TENANTS + SyntheticExport.US_TENANT, 300), deid, leaveForReview = 6)
            log.info("demo data seeded: {}", r)
        }
        return App(cfg, wb, store, ds)
    }

    private fun regionLabel(cfg: Config) = if (cfg.region == workbench.phi.Region.US) "us-east-1" else "eu-central-1"
}
