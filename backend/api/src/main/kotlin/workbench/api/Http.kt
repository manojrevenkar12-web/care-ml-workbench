package workbench.api

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Counter
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.event.Level
import workbench.annotation.InvalidAnnotation
import workbench.annotation.Label
import workbench.annotation.TaskKind
import workbench.authz.Forbidden
import workbench.authz.Tuple
import workbench.dataset.Export
import workbench.json.Codec
import workbench.service.Conflict
import workbench.service.Invalid
import workbench.service.NotFound
import workbench.service.PhiInInput
import workbench.training.IllegalTransition
import java.net.URI
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

private fun errorBody(code: String, message: String?, extra: Map<String, JsonElement> = emptyMap()) =
    JsonObject(mapOf("error" to JsonPrimitive(code), "message" to JsonPrimitive(message ?: code)) + extra)

/** The signed-in user as an OpenFGA subject ("user:<sub>"). */
private fun ApplicationCall.user(): String = "user:" + principal<JWTPrincipal>()!!.payload.subject

private fun ApplicationCall.p(name: String) = parameters[name] ?: throw Invalid("missing path parameter $name")

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: throw Invalid("missing field '$k'")

fun Application.module(app: App) {
    val cfg = app.config
    val wb = app.workbench
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val submitted = Counter.builder("workbench_annotations_submitted").description("annotations submitted").register(registry)
    val reveals = Counter.builder("workbench_phi_reveals").description("break-glass PHI reveals").register(registry)
    val rejected = Counter.builder("workbench_annotations_rejected").description("annotations rejected by validation (incl. PHI)").register(registry)
    registry.gauge("workbench_tasks_open", app.store) { s -> s.projects().sumOf { p -> s.tasks(p.id).count { it.status.name == "OPEN" } }.toDouble() }

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Cache-Control", "no-store") // responses may contain de-identified patient data
    }
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.length in 8..64 }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("request_id")
        filter { it.request.path().startsWith("/api") }
        // method, path and status only: request and response bodies may contain care data
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    install(MicrometerMetrics) { this.registry = registry }
    install(ContentNegotiation) { json() }
    install(CORS) {
        cfg.corsOrigins.forEach { o -> URI(o).let { allowHost(it.authority, listOf(it.scheme)) } }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Post)
    }
    install(StatusPages) {
        exception<Forbidden> { call, e -> call.respond(HttpStatusCode.Forbidden, errorBody("forbidden", e.message)) }
        exception<NotFound> { call, e -> call.respond(HttpStatusCode.NotFound, errorBody("not_found", e.message)) }
        exception<Conflict> { call, e -> call.respond(HttpStatusCode.Conflict, errorBody("conflict", e.message)) }
        exception<IllegalTransition> { call, e -> call.respond(HttpStatusCode.Conflict, errorBody("conflict", e.message)) }
        exception<InvalidAnnotation> { call, e ->
            rejected.increment()
            call.respond(HttpStatusCode.UnprocessableEntity, errorBody("invalid_annotation", e.message))
        }
        exception<PhiInInput> { call, e ->
            // report where and what kind — never echo the text back
            val spans = JsonArray(e.spans.map { buildJsonObject { put("start", it.start); put("end", it.end); put("kind", it.kind.name) } })
            call.respond(HttpStatusCode.UnprocessableEntity, errorBody("phi_in_input", e.message, mapOf("spans" to spans)))
        }
        exception<Invalid> { call, e -> call.respond(HttpStatusCode.UnprocessableEntity, errorBody("invalid", e.message)) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, errorBody("bad_request", e.message)) }
        exception<kotlinx.serialization.SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, errorBody("bad_request", "malformed JSON")) }
        exception<Throwable> { call, e ->
            call.application.environment.log.errorBody("unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, errorBody("internal", "internal error (request ${call.callId})"))
        }
    }
    install(Authentication) {
        jwt("user") {
            realm = "care-ml-workbench"
            if (cfg.oidcIssuer != null && cfg.oidcJwksUrl != null) {
                val jwks = JwkProviderBuilder(URI(cfg.oidcJwksUrl).toURL()).cached(10, 24, TimeUnit.HOURS).rateLimited(10, 1, TimeUnit.MINUTES).build()
                verifier(jwks, cfg.oidcIssuer) {
                    acceptLeeway(10)
                    cfg.oidcAudience?.let { withAudience(it) }
                }
            } else {
                verifier(JWT.require(Algorithm.HMAC256(cfg.devSecret)).withIssuer("dev").build())
            }
            validate { cred -> cred.payload.subject?.takeIf { Regex("[A-Za-z0-9._@-]{1,64}").matches(it) }?.let { JWTPrincipal(cred.payload) } }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, errorBody("unauthorized", "sign in required")) }
        }
    }

    // finished training jobs are picked up in the background
    launch(Dispatchers.IO) {
        while (isActive) {
            runCatching { wb.reconcileJobs() }.onFailure { environment.log.warn("reconcile failed: {}", it.toString()) }
            delay(2_000)
        }
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "ok") }) }
        get("/ready") {
            val ok = app.dataSource?.let { ds -> runCatching { ds.connection.use { it.isValid(2) } }.getOrDefault(false) } ?: true
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, buildJsonObject { put("database", ok) })
        }
        get("/metrics") { call.respondText(registry.scrape(), ContentType.parse("text/plain; version=0.0.4")) }

        if (cfg.devAuth && !cfg.production) {
            // development only: a token for any demo user (the server refuses DEV_AUTH in production)
            post("/dev/token") {
                val body = call.receive<JsonObject>()
                val sub = body.str("user")
                val token = JWT.create().withIssuer("dev").withSubject(sub).withExpiresAt(Date(System.currentTimeMillis() + 12 * 3600_000)).sign(Algorithm.HMAC256(cfg.devSecret))
                call.respond(buildJsonObject { put("token", token) })
            }
        }

        authenticate("user") {
            route("/api") {
                api(wb, onSubmit = { submitted.increment() }, onReveal = { reveals.increment() })
            }
        }
    }
}

private fun Route.api(wb: workbench.service.Workbench, onSubmit: () -> Unit, onReveal: () -> Unit) {
    get("/me") {
        val u = call.user()
        call.respond(buildJsonObject {
            put("user", u)
            put("projects", JsonArray(wb.projects(u).map { Codec.project(it, wb.permissions(u, it.id)) }))
        })
    }
    get("/labels") {
        call.respond(JsonArray(Label.entries.map { buildJsonObject { put("name", it.name); put("hotkey", it.hotkey.toString()); put("description", it.description) } }))
    }

    route("/projects/{project}") {
        get("/stats") { call.respond(Codec.stats(wb.stats(call.user(), call.p("project")))) }
        post("/tasks/next") {
            val kind = TaskKind.valueOf((call.request.queryParameters["kind"] ?: "ANNOTATE").uppercase())
            val v = wb.nextTask(call.user(), call.p("project"), kind)
            if (v == null) call.respond(HttpStatusCode.NoContent) else call.respond(Codec.taskView(v))
        }
        get("/items") {
            val q = call.request.queryParameters
            val (total, rows) = wb.explore(
                call.user(), call.p("project"), q["facility"], q["label"]?.let { Label.valueOf(it) }, q["status"], q["q"],
                q["limit"]?.toIntOrNull() ?: 50, q["offset"]?.toIntOrNull() ?: 0,
            )
            call.respond(buildJsonObject { put("total", total); put("rows", JsonArray(rows.map(Codec::exploreRow))) })
        }
        get("/snapshots") { call.respond(JsonArray(wb.snapshots(call.user(), call.p("project")).map { Codec.snapshot(it) })) }
        post("/snapshots") { call.respond(HttpStatusCode.Created, Codec.snapshot(wb.createSnapshot(call.user(), call.p("project")))) }
        get("/jobs") { call.respond(JsonArray(wb.jobs(call.user(), call.p("project")).map(Codec::job))) }
        post("/jobs") {
            val b = call.receive<JsonObject>()
            val params = (b["params"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
            val job = withContext(Dispatchers.IO) { wb.startTraining(call.user(), b.str("snapshot_id"), b["model"]?.jsonPrimitive?.contentOrNull ?: "entity-tagger", params, Export::writeJsonl) }
            call.respond(HttpStatusCode.Created, Codec.job(job))
        }
        get("/models") { call.respond(JsonArray(wb.models(call.user(), call.p("project")).map(Codec::model))) }
        post("/playground") {
            val b = call.receive<JsonObject>()
            call.respond(buildJsonObject { put("entities", Codec.spans(wb.predict(call.user(), call.p("project"), b.str("model"), b.str("text")))) })
        }
        post("/grants") {
            val b = call.receive<JsonObject>()
            wb.grant(call.user(), Tuple("user:" + b.str("user").removePrefix("user:"), b.str("relation"), "project:${call.p("project")}"))
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/tasks/{task}/submit") {
        val b = call.receive<JsonObject>()
        val a = wb.submit(call.user(), call.p("task"), b.str("transcript"), Codec.parseSpans(b["entities"]), b["seconds_spent"]?.jsonPrimitive?.intOrNull ?: 0)
        onSubmit()
        call.respond(HttpStatusCode.Created, Codec.annotation(a))
    }
    post("/tasks/{task}/adjudicate") {
        val b = call.receive<JsonObject>()
        call.respond(HttpStatusCode.Created, Codec.annotation(wb.adjudicate(call.user(), call.p("task"), b.str("transcript"), Codec.parseSpans(b["entities"]))))
    }
    post("/items/{item}/reveal") {
        val b = call.receive<JsonObject>()
        val r = wb.revealPhi(call.user(), call.p("item"), b.str("reason"))
        onReveal()
        call.respond(buildJsonObject { put("item_id", r.itemId); put("original", r.original); put("audit_seq", r.auditSeq) })
    }
    get("/snapshots/{snapshot}/compare") {
        val q = call.request.queryParameters
        val a = q["a"] ?: "asr-v1"
        val b = q["b"] ?: "asr-v2"
        call.respond(Codec.comparison(wb.compareAsr(call.user(), call.p("snapshot"), a, b), a, b))
    }
    get("/snapshots/{snapshot}/evaluate") {
        call.respond(Codec.entityEval(wb.evaluateTagger(call.user(), call.p("snapshot"), call.request.queryParameters["model"] ?: throw Invalid("missing ?model="))))
    }
    post("/jobs/{job}/cancel") { call.respond(Codec.job(wb.cancelJob(call.user(), call.p("job")))) }
    get("/tenants/{tenant}/audit") {
        val q = call.request.queryParameters
        val (v, events) = wb.auditLog(call.user(), call.p("tenant"), q["action"], q["limit"]?.toIntOrNull() ?: 100)
        call.respond(buildJsonObject { put("verification", Codec.verification(v)); put("events", JsonArray(events.map(Codec::audit))) })
    }
}
