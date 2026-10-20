package workbench.authz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Authorisation through OpenFGA (the "FGA proxy"). Checks are cached for a few seconds — a
 * revoked grant takes effect within the TTL — and the service fails closed: if OpenFGA cannot be
 * reached, the answer is "no".
 */
class OpenFgaAuthorizer(
    private val baseUrl: String,
    private val storeId: String,
    private val modelId: String?,
    private val token: String? = null,
    private val ttl: Duration = Duration.ofSeconds(5),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
) : Authorizer {
    private val cache = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    private fun post(path: String, body: String): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("$baseUrl/stores/$storeId/$path")).timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }

    override fun check(user: String, relation: String, obj: String): Boolean {
        val key = "$user|$relation|$obj"
        val now = System.nanoTime()
        cache[key]?.let { (v, at) -> if (now - at < ttl.toNanos()) return v }
        val body = buildJsonObject {
            putJsonObject("tuple_key") { put("user", user); put("relation", relation); put("object", obj) }
            modelId?.let { put("authorization_model_id", it) }
        }
        val allowed = try {
            val r = post("check", body.toString())
            r.statusCode() == 200 && Json.parseToJsonElement(r.body()).jsonObject["allowed"]?.jsonPrimitive?.boolean == true
        } catch (e: Exception) {
            false // fail closed
        }
        cache[key] = allowed to now
        return allowed
    }

    fun write(tuples: List<Tuple>) {
        val body = buildJsonObject {
            putJsonObject("writes") {
                putJsonArray("tuple_keys") { tuples.forEach { t -> add(buildJsonObject { put("user", t.user); put("relation", t.relation); put("object", t.obj) }) } }
            }
            modelId?.let { put("authorization_model_id", it) }
        }
        val r = post("write", body.toString())
        check(r.statusCode() == 200) { "OpenFGA write failed: ${r.statusCode()} ${r.body().take(200)}" }
        cache.clear()
    }
}

/** Development helper: create (or find) an OpenFGA store and upload the authorisation model. */
object OpenFgaBootstrap {
    private val http = HttpClient.newHttpClient()

    private fun send(method: String, url: String, body: String?): String {
        val b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
        val r = http.send(b.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        check(r.statusCode() in 200..299) { "$method $url failed: ${r.statusCode()} ${r.body().take(300)}" }
        return r.body()
    }

    /** Returns (storeId, modelId). */
    fun ensure(baseUrl: String, storeName: String, modelJson: String): Pair<String, String> {
        val stores = Json.parseToJsonElement(send("GET", "$baseUrl/stores", null)).jsonObject["stores"]
        val existing = (stores as? kotlinx.serialization.json.JsonArray).orEmpty().firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == storeName }
        val storeId = existing?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: Json.parseToJsonElement(send("POST", "$baseUrl/stores", buildJsonObject { put("name", storeName) }.toString())).jsonObject["id"]!!.jsonPrimitive.content
        val modelId = Json.parseToJsonElement(send("POST", "$baseUrl/stores/$storeId/authorization-models", modelJson)).jsonObject["authorization_model_id"]!!.jsonPrimitive.content
        return storeId to modelId
    }
}
