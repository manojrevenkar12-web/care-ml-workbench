package workbench.training

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ;

    val terminal get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

data class TrainingJob(
    val id: String,
    val projectId: String,
    val snapshotId: String,
    val snapshotSha256: String,
    val model: String, // e.g. "entity-tagger"
    val params: Map<String, String>,
    val status: JobStatus,
    val createdBy: String,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val metrics: Map<String, Double> = emptyMap(),
    val artifact: String? = null,
    val message: String? = null,
)

class IllegalTransition(message: String) : RuntimeException(message)

object JobStateMachine {
    private val allowed = mapOf(
        JobStatus.QUEUED to setOf(JobStatus.RUNNING, JobStatus.CANCELLED, JobStatus.FAILED),
        JobStatus.RUNNING to setOf(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED),
    )

    fun transition(job: TrainingJob, to: JobStatus, now: Instant, metrics: Map<String, Double> = emptyMap(), artifact: String? = null, message: String? = null): TrainingJob {
        if (to !in allowed[job.status].orEmpty()) throw IllegalTransition("job ${job.id}: ${job.status} → $to is not allowed")
        return job.copy(
            status = to,
            startedAt = if (to == JobStatus.RUNNING) now else job.startedAt,
            finishedAt = if (to.terminal) now else job.finishedAt,
            metrics = job.metrics + metrics,
            artifact = artifact ?: job.artifact,
            message = message ?: job.message,
        )
    }
}

/** Where a training job runs. Production: a Kubernetes Job on EKS; development: a local process. */
interface JobRunner {
    fun submit(job: TrainingJob, datasetPath: String, outputPath: String): String

    /** Current state; on success the runner returns the metrics and artifact location. */
    fun poll(handle: String): RunState

    fun cancel(handle: String)
}

data class RunState(val status: JobStatus, val metrics: Map<String, Double> = emptyMap(), val artifact: String? = null, val message: String? = null)

/**
 * Runs `python trainer/train.py` as a child process. The trainer writes metrics.json and
 * model.json into the output directory.
 */
class LocalProcessRunner(private val command: List<String>, private val workDir: Path, private val parseMetrics: (String) -> Map<String, Double>) : JobRunner {
    private val procs = ConcurrentHashMap<String, Pair<Process, Path>>()

    override fun submit(job: TrainingJob, datasetPath: String, outputPath: String): String {
        val out = Path.of(outputPath)
        Files.createDirectories(out)
        val args = command + listOf("--dataset", datasetPath, "--out", outputPath, "--job-id", job.id) + job.params.flatMap { (k, v) -> listOf("--$k", v) }
        val p = ProcessBuilder(args).directory(workDir.toFile()).redirectErrorStream(true).redirectOutput(out.resolve("train.log").toFile()).start()
        procs[job.id] = p to out
        return job.id
    }

    override fun poll(handle: String): RunState {
        val (p, out) = procs[handle] ?: return RunState(JobStatus.FAILED, message = "unknown job $handle")
        if (p.isAlive) return RunState(JobStatus.RUNNING)
        val log = runCatching { Files.readString(out.resolve("train.log")).takeLast(2000) }.getOrDefault("")
        if (p.exitValue() != 0) return RunState(JobStatus.FAILED, message = "trainer exited with ${p.exitValue()}: ${log.lines().lastOrNull { it.isNotBlank() }.orEmpty()}")
        val metrics = runCatching { parseMetrics(Files.readString(out.resolve("metrics.json"))) }.getOrDefault(emptyMap())
        return RunState(JobStatus.SUCCEEDED, metrics, out.resolve("model.json").toString())
    }

    override fun cancel(handle: String) {
        procs[handle]?.first?.let {
            it.destroy()
            it.waitFor(5, TimeUnit.SECONDS)
        }
    }
}

/**
 * The Kubernetes Job a training run becomes on EKS. Everything that matters for PHI safety is in
 * the manifest, so it is built (and unit-tested) here rather than templated by hand:
 * - the pod mounts only the artifacts volume (de-identified datasets, model artifacts); its
 *   service account has no cloud permissions at all, so it cannot reach the vault or raw exports;
 * - network policy label `egress: none` (no internet), non-root, read-only root filesystem,
 *   no privilege escalation, all capabilities dropped;
 * - pinned to the deployment's region node group, with a deadline and TTL for cleanup.
 */
object KubernetesJobManifest {
    fun build(job: TrainingJob, image: String, namespace: String, datasetUri: String, outputUri: String, region: String, serviceAccount: String = "trainer"): JsonObject {
        val name = "train-" + job.id.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(50)
        val labels = buildJsonObject {
            put("app.kubernetes.io/name", "trainer")
            put("workbench/job-id", job.id)
            put("workbench/project", job.projectId)
            put("egress", "none")
        }
        return buildJsonObject {
            put("apiVersion", "batch/v1")
            put("kind", "Job")
            putJsonObject("metadata") {
                put("name", name)
                put("namespace", namespace)
                put("labels", labels)
                putJsonObject("annotations") { put("workbench/snapshot-sha256", job.snapshotSha256) }
            }
            putJsonObject("spec") {
                put("backoffLimit", 1)
                put("activeDeadlineSeconds", 3600)
                put("ttlSecondsAfterFinished", 86400)
                putJsonObject("template") {
                    putJsonObject("metadata") { put("labels", labels) }
                    putJsonObject("spec") {
                        put("serviceAccountName", serviceAccount)
                        put("restartPolicy", "Never")
                        put("automountServiceAccountToken", true)
                        putJsonObject("nodeSelector") { put("topology.kubernetes.io/region", region) }
                        putJsonObject("securityContext") {
                            put("runAsNonRoot", true)
                            put("runAsUser", 10001)
                            putJsonObject("seccompProfile") { put("type", "RuntimeDefault") }
                        }
                        putJsonArray("containers") {
                            add(
                                buildJsonObject {
                                    put("name", "trainer")
                                    put("image", image)
                                    put("args", JsonArray((listOf("--dataset", datasetUri, "--out", outputUri, "--job-id", job.id) + job.params.flatMap { (k, v) -> listOf("--$k", v) }).map { JsonPrimitive(it) }))
                                    putJsonObject("resources") {
                                        putJsonObject("requests") { put("cpu", "2"); put("memory", "4Gi") }
                                        putJsonObject("limits") { put("cpu", "4"); put("memory", "8Gi") }
                                    }
                                    putJsonObject("securityContext") {
                                        put("allowPrivilegeEscalation", false)
                                        put("readOnlyRootFilesystem", true)
                                        putJsonObject("capabilities") { put("drop", buildJsonArray { add(JsonPrimitive("ALL")) }) }
                                    }
                                    put("terminationMessagePath", "/dev/termination-log") // the trainer writes its metrics here
                                    putJsonArray("volumeMounts") {
                                        add(buildJsonObject { put("name", "scratch"); put("mountPath", "/tmp") })
                                        add(buildJsonObject { put("name", "artifacts"); put("mountPath", "/artifacts") })
                                    }
                                },
                            )
                        }
                        putJsonArray("volumes") {
                            add(buildJsonObject { put("name", "scratch"); putJsonObject("emptyDir") { put("sizeLimit", "10Gi") } })
                            // shared EFS volume with de-identified datasets and model artifacts only
                            add(buildJsonObject { put("name", "artifacts"); putJsonObject("persistentVolumeClaim") { put("claimName", "ml-artifacts") } })
                        }
                    }
                }
            }
        }
    }
}
