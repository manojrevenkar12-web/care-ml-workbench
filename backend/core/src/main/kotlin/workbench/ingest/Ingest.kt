package workbench.ingest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import workbench.annotation.Assignment
import workbench.annotation.Item
import workbench.annotation.Task
import workbench.annotation.TaskKind
import workbench.annotation.TaskStatus
import workbench.audit.Actions
import workbench.audit.AuditLog
import workbench.phi.Deidentifier
import workbench.phi.KnownIdentifiers
import workbench.phi.Person
import workbench.phi.PhiDetector
import workbench.phi.PhiKind
import workbench.phi.PhiVault
import workbench.phi.Region
import workbench.phi.Residency
import workbench.phi.ResidencyViolation
import workbench.store.Quarantined
import workbench.store.Store
import java.time.Instant
import java.time.LocalDate

/** A documentation record as exported by a customer facility (contains PHI). */
data class CareRecord(
    val recordId: String,
    val tenant: String,
    val projectId: String,
    val facilityId: String,
    val residentId: String,
    val resident: Person,
    val dateOfBirth: LocalDate,
    val insuranceNumber: String,
    val room: String,
    val phone: String?,
    val relatives: List<Person>,
    val staff: List<Person>,
    val recordedAt: Instant,
    val primaryModel: String,
    val asr: Map<String, String>, // speech-model outputs by model name
) {
    fun known() = KnownIdentifiers(listOf(resident) + relatives + staff, dateOfBirth, insuranceNumber, phone, room)

    companion object {
        class SchemaError(message: String) : RuntimeException(message)

        fun parse(e: JsonElement): CareRecord {
            val o = e as? JsonObject ?: throw SchemaError("record is not an object")
            fun s(k: String, from: JsonObject = o) = from[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw SchemaError("missing field '$k'")
            fun person(p: JsonElement) = p.jsonObject.let { Person(s("first_name", it), s("last_name", it)) }
            val r = o["resident"]?.jsonObject ?: throw SchemaError("missing field 'resident'")
            val asr = o["asr"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: throw SchemaError("missing field 'asr'")
            val primary = s("primary_model")
            if (primary !in asr) throw SchemaError("primary model '$primary' has no transcript")
            if (asr.values.any { it.length > 20_000 }) throw SchemaError("transcript too long")
            return CareRecord(
                recordId = s("record_id"),
                tenant = s("tenant"),
                projectId = s("project_id"),
                facilityId = s("facility_id"),
                residentId = s("resident_id"),
                resident = person(r),
                dateOfBirth = runCatching { LocalDate.parse(s("date_of_birth", r)) }.getOrElse { throw SchemaError("bad date_of_birth") },
                insuranceNumber = s("insurance_number", r),
                room = s("room", r),
                phone = r["phone"]?.jsonPrimitive?.contentOrNull,
                relatives = (o["relatives"] as? JsonArray).orEmpty().map(::person),
                staff = (o["staff"] as? JsonArray).orEmpty().map(::person),
                recordedAt = runCatching { Instant.parse(s("recorded_at")) }.getOrElse { throw SchemaError("bad recorded_at") },
                primaryModel = primary,
                asr = asr,
            )
        }
    }
}

data class IngestReport(
    val accepted: Int,
    val quarantined: Map<String, Int>,
    val phiFound: Map<PhiKind, Int>,
    val leaksCaught: Int,
    val tasksCreated: Int,
)

/**
 * The only path by which customer data enters the ML App. Each record is:
 *  1. parsed and validated against the export schema;
 *  2. checked for residency — this deployment's region, and a signed BAA for US data;
 *  3. de-identified (every model's transcript, with the same surrogates and date shift);
 *  4. re-scanned: if any known identifier is still present the record is quarantined, not loaded;
 *  5. sealed (original record, AES-GCM envelope) in the vault, keyed by the new item id;
 *  6. queued for annotation — once or twice, depending on the agreement sample.
 * Every accepted and every quarantined record is audited.
 */
class IngestPipeline(
    private val store: Store,
    private val vault: PhiVault,
    private val deidentifierFor: (String) -> Deidentifier,
    private val audit: AuditLog,
    private val region: Region,
    private val assignment: Assignment = Assignment(),
    private val clock: () -> Instant = Instant::now,
) {
    private val detector = PhiDetector()

    fun ingest(records: Sequence<Pair<String, JsonElement?>>, actor: String): IngestReport {
        var accepted = 0
        var leakCount = 0
        var tasks = 0
        val quarantined = sortedMapOf<String, Int>()
        val phi = sortedMapOf<PhiKind, Int>()
        fun reject(id: String, tenant: String, reason: String) {
            quarantined.merge(reason.substringBefore(':'), 1, Int::plus)
            store.quarantine(Quarantined(id, tenant, reason, clock()))
            audit.record(actor, Actions.QUARANTINE, tenant, "record:$id", mapOf("reason" to reason))
        }
        for ((raw, json) in records) {
            val rec = try {
                CareRecord.parse(json ?: throw CareRecord.Companion.SchemaError("not valid JSON"))
            } catch (e: Exception) {
                reject(idOf(json), tenantOf(json), "schema: ${e.message}")
                continue
            }
            val policy = store.tenants().firstOrNull { it.tenant == rec.tenant }
            if (policy == null) {
                reject(rec.recordId, rec.tenant, "unknown tenant: ${rec.tenant}")
                continue
            }
            try {
                Residency.check(policy, region)
            } catch (e: ResidencyViolation) {
                reject(rec.recordId, rec.tenant, "residency: ${e.message}")
                continue
            }
            if (store.project(rec.projectId)?.tenant != rec.tenant) {
                reject(rec.recordId, rec.tenant, "project: ${rec.projectId} does not belong to ${rec.tenant}")
                continue
            }
            val deid = deidentifierFor(rec.tenant)
            val subject = deid.subjectPseudonym(rec.tenant, rec.residentId)
            val reference = rec.recordedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()
            val known = rec.known()
            val outputs = rec.asr.mapValues { (_, text) -> deid.deidentify(text, known, rec.residentId, reference) }
            outputs.values.forEach { r -> r.found.forEach { (k, v) -> phi.merge(k, v, Int::plus) } }
            val leftovers = outputs.values.sumOf { r -> leaks(r.text, known) }
            if (leftovers > 0) {
                leakCount += leftovers
                reject(rec.recordId, rec.tenant, "leak: $leftovers identifiers remained after de-identification")
                continue
            }
            val itemId = itemIdFor(rec.tenant, rec.recordId)
            val primary = outputs.getValue(rec.primaryModel)
            val item = Item(
                id = itemId,
                tenant = rec.tenant,
                projectId = rec.projectId,
                facilityId = rec.facilityId,
                subject = subject,
                recordedAt = rec.recordedAt.plus(java.time.Duration.ofDays(deid.dateOffset(rec.residentId))),
                asrModel = rec.primaryModel,
                asrText = primary.text,
                surrogates = primary.surrogates,
                predictions = outputs.filterKeys { it != rec.primaryModel }.mapValues { it.value.text },
            )
            store.putItem(item)
            store.putSealed(itemId, vault.seal(rec.tenant, itemId, raw.toByteArray()))
            repeat(assignment.annotationsNeeded(item)) { k ->
                store.putTask(Task("tsk_${itemId.removePrefix("itm_")}_$k", itemId, rec.projectId, TaskKind.ANNOTATE, TaskStatus.OPEN, createdAt = clock()))
                tasks++
            }
            audit.record(actor, Actions.INGEST, rec.tenant, "item:$itemId", mapOf("record" to rec.recordId, "phi_replaced" to primary.surrogates.size.toString()))
            accepted++
        }
        return IngestReport(accepted, quarantined, phi, leakCount, tasks)
    }

    companion object {
        /** Item ids are derived from the record but reveal nothing about it. */
        fun itemIdFor(tenant: String, recordId: String) = "itm_" + Deidentifier.hex(java.security.MessageDigest.getInstance("SHA-256").digest("$tenant/$recordId".toByteArray())).take(20)
    }

    /** Identifiers of this record still present after de-identification (should always be 0). */
    fun leaks(text: String, known: KnownIdentifiers): Int {
        val realNames = known.people.flatMap { listOf(it.firstName, it.lastName, it.fullName) }.map { it.lowercase() }.toSet()
        return detector.detect(text, known).count { s ->
            when (s.kind) {
                PhiKind.DATE -> false // dates are shifted, not removed
                PhiKind.PERSON_NAME -> s.text.lowercase() in realNames
                else -> true
            }
        }
    }

    private fun idOf(json: JsonElement?) = ((json as? JsonObject)?.get("record_id") as? JsonPrimitive)?.contentOrNull ?: "unknown"

    private fun tenantOf(json: JsonElement?) = ((json as? JsonObject)?.get("tenant") as? JsonPrimitive)?.contentOrNull ?: "unknown"
}
