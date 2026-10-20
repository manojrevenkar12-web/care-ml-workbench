package workbench.training

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Runs training as Kubernetes Jobs (EKS in production) through the in-cluster API with the pod's
 * service-account token. The API's service account may only create, get and delete Jobs and read
 * pods in its own namespace (see deploy/k8s/rbac.yaml). Datasets and artifacts live on a shared
 * volume (EFS) mounted at /artifacts; the trainer reports its metrics in the container's
 * termination message, so no extra storage access is needed to read them.
 */
class KubernetesRunner(
    private val image: String,
    private val namespace: String,
    private val region: String,
    private val apiServer: String = "https://kubernetes.default.svc",
    private val saDir: Path = Path.of("/var/run/secrets/kubernetes.io/serviceaccount"),
    private val parseMetrics: (String) -> Map<String, Double>,
) : JobRunner {
    private val http: HttpClient by lazy {
        val ca = CertificateFactory.getInstance("X.509").generateCertificate(Files.newInputStream(saDir.resolve("ca.crt")))
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("k8s", ca) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        HttpClient.newBuilder().sslContext(SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }).build()
    }

    private fun req(path: String) = HttpRequest.newBuilder(URI.create("$apiServer$path"))
        .header("Authorization", "Bearer " + Files.readString(saDir.resolve("token")).trim())
        .header("Content-Type", "application/json")

    private fun name(handle: String) = "train-" + handle.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(50)

    override fun submit(job: TrainingJob, datasetPath: String, outputPath: String): String {
        val manifest = KubernetesJobManifest.build(job, image, namespace, datasetPath, outputPath, region)
        val r = http.send(req("/apis/batch/v1/namespaces/$namespace/jobs").POST(HttpRequest.BodyPublishers.ofString(manifest.toString())).build(), HttpResponse.BodyHandlers.ofString())
        check(r.statusCode() in 200..299) { "creating job failed: ${r.statusCode()} ${r.body().take(300)}" }
        return job.id
    }

    override fun poll(handle: String): RunState {
        val r = http.send(req("/apis/batch/v1/namespaces/$namespace/jobs/${name(handle)}").GET().build(), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() == 404) return RunState(JobStatus.FAILED, message = "job disappeared")
        val status = Json.parseToJsonElement(r.body()).jsonObject["status"]?.jsonObject ?: return RunState(JobStatus.RUNNING)
        if ((status["failed"]?.jsonPrimitive?.int ?: 0) > 0) return RunState(JobStatus.FAILED, message = terminationMessage(handle) ?: "trainer failed")
        if ((status["succeeded"]?.jsonPrimitive?.int ?: 0) > 0) {
            val msg = terminationMessage(handle) ?: return RunState(JobStatus.FAILED, message = "no metrics reported")
            return RunState(JobStatus.SUCCEEDED, parseMetrics(msg), "/artifacts/runs/$handle/model.json")
        }
        return RunState(JobStatus.RUNNING)
    }

    private fun terminationMessage(handle: String): String? {
        val sel = java.net.URLEncoder.encode("workbench/job-id=$handle", Charsets.UTF_8)
        val r = http.send(req("/api/v1/namespaces/$namespace/pods?labelSelector=$sel").GET().build(), HttpResponse.BodyHandlers.ofString())
        val pods = (Json.parseToJsonElement(r.body()).jsonObject["items"]?.jsonArray).orEmpty()
        return pods.firstNotNullOfOrNull { p ->
            (p.jsonObject["status"]?.jsonObject?.get("containerStatuses")?.jsonArray).orEmpty().firstNotNullOfOrNull { cs ->
                (cs.jsonObject["state"]?.jsonObject?.get("terminated") as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            }
        }
    }

    override fun cancel(handle: String) {
        http.send(req("/apis/batch/v1/namespaces/$namespace/jobs/${name(handle)}?propagationPolicy=Background").DELETE().build(), HttpResponse.BodyHandlers.discarding())
    }
}
