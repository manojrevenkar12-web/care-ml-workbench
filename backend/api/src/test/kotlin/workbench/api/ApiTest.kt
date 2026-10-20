package workbench.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private val cfg = Config.fromEnv(mapOf("DEV_AUTH" to "true", "SEED_DEMO" to "true", "ARTIFACT_DIR" to "build/test-artifacts"))

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val app = Wiring.build(cfg)
        application { module(app) }
        block()
    }

    private suspend fun ApplicationTestBuilder.token(user: String): String {
        val r = client.post("/dev/token") { contentType(ContentType.Application.Json); setBody("""{"user":"$user"}""") }
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `requests without a token are rejected`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `an annotator sees only their projects and can work the queue`() = withApp {
        val t = token("anna")
        val me = Json.parseToJsonElement(client.get("/api/me") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText()).jsonObject
        assertEquals(listOf("nord-doku"), me["projects"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val next = client.post("/api/projects/nord-doku/tasks/next") { header(HttpHeaders.Authorization, "Bearer $t") }
        assertEquals(HttpStatusCode.OK, next.status)
        val view = Json.parseToJsonElement(next.bodyAsText()).jsonObject
        val taskId = view["task"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val text = view["item"]!!.jsonObject["asr_text"]!!.jsonPrimitive.content
        val ok = client.post("/api/tasks/$taskId/submit") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("transcript" to kotlinx.serialization.json.JsonPrimitive(text), "entities" to kotlinx.serialization.json.JsonArray(emptyList()))).toString())
        }
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
    }

    @Test
    fun `roles, PHI rules and errors map to the right status codes`() = withApp {
        val anna = token("anna")
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/projects/nord-doku/tasks/next?kind=REVIEW") { header(HttpHeaders.Authorization, "Bearer $anna") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/projects/sued-doku/items") { header(HttpHeaders.Authorization, "Bearer $anna") }.status)
        val phi = client.post("/api/projects/nord-doku/playground") {
            header(HttpHeaders.Authorization, "Bearer $anna")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"x","text":"Herr Meyer, Tel 0171 2233445"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, phi.status)
        assertTrue("Meyer" !in phi.bodyAsText(), "PHI must not be echoed back")
        val bad = client.post("/api/projects/nord-doku/snapshots") { header(HttpHeaders.Authorization, "Bearer ${token("rita")}") }
        assertEquals(HttpStatusCode.Created, bad.status)
        val audit = client.get("/api/tenants/pflege-nord/audit?limit=5") { header(HttpHeaders.Authorization, "Bearer ${token("olivia")}") }
        assertEquals(HttpStatusCode.OK, audit.status)
        assertTrue(Json.parseToJsonElement(audit.bodyAsText()).jsonObject["verification"]!!.jsonObject["intact"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `metrics are exposed for Prometheus`() = withApp {
        val m = client.get("/metrics").bodyAsText()
        assertTrue("workbench_tasks_open" in m)
        assertTrue("ktor_http_server_requests" in m)
    }

    @Test
    fun `production refuses development settings`() {
        val e = runCatching { Config.fromEnv(mapOf("WORKBENCH_ENV" to "production", "DEV_AUTH" to "true")).validate() }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && "DEV_AUTH" in e.message!!)
    }
}
