package workbench.annotation

import workbench.phi.KnownIdentifiers
import workbench.phi.PhiDetector
import workbench.phi.PhiKind
import workbench.phi.SurrogateSpan
import java.time.Duration
import java.time.Instant

/** Care-documentation entities annotators mark in a transcript. */
enum class Label(val hotkey: Char, val description: String) {
    VITAL_SIGN('1', "blood pressure, pulse, temperature, oxygen, blood sugar"),
    MEDICATION('2', "drug names"),
    DOSAGE('3', "dose and schedule"),
    WOUND('4', "wounds, pressure ulcers, skin"),
    PAIN('5', "pain and its intensity"),
    MOBILITY('6', "mobility, transfers, falls"),
    NUTRITION('7', "food and drink intake"),
    EXCRETION('8', "continence, stool, urine"),
    MOOD('9', "mood, behaviour, orientation"),
    SLEEP('0', "sleep and rest"),
}

data class EntitySpan(val start: Int, val end: Int, val label: Label)

/** One de-identified documentation record, ready for annotation. */
data class Item(
    val id: String,
    val tenant: String,
    val projectId: String,
    val facilityId: String,
    val subject: String, // pseudonymous resident key (never the resident's id)
    val recordedAt: Instant,
    val asrModel: String,
    val asrText: String, // what the speech model heard, de-identified
    val surrogates: List<SurrogateSpan>, // positions of PHI surrogates in asrText
    val priority: Double = 0.0, // e.g. model uncertainty, for active learning
    val referenceText: String? = null, // corrected transcript, once adjudicated
    val referenceEntities: List<EntitySpan>? = null,
    val predictions: Map<String, String> = emptyMap(), // other ASR models' outputs, for evaluation
)

enum class TaskStatus { OPEN, LEASED, SUBMITTED, EXPIRED }

enum class TaskKind { ANNOTATE, REVIEW }

data class Task(
    val id: String,
    val itemId: String,
    val projectId: String,
    val kind: TaskKind,
    val status: TaskStatus,
    val assignee: String? = null,
    val leasedUntil: Instant? = null,
    val createdAt: Instant,
)

data class Annotation(
    val id: String,
    val taskId: String,
    val itemId: String,
    val annotator: String,
    val transcript: String,
    val entities: List<EntitySpan>,
    val submittedAt: Instant,
    val secondsSpent: Int,
    val final: Boolean = false, // adjudicated / accepted as the reference
)

class InvalidAnnotation(message: String) : RuntimeException(message)

object AnnotationRules {
    private val detector = PhiDetector()

    /**
     * An annotation is accepted only if its spans are well-formed and it does not put PHI back:
     * surrogate placeholders must survive the edit, and no new name, date of birth, phone number
     * or insurance number may appear in the corrected transcript (annotators sometimes "fix" a
     * surrogate name back to what they heard).
     */
    fun validate(item: Item, transcript: String, entities: List<EntitySpan>) {
        if (transcript.isBlank()) throw InvalidAnnotation("transcript is empty")
        if (transcript.length > 20_000) throw InvalidAnnotation("transcript too long")
        val sorted = entities.sortedBy { it.start }
        sorted.forEachIndexed { i, e ->
            if (e.start < 0 || e.end > transcript.length || e.start >= e.end) throw InvalidAnnotation("span ${e.start}-${e.end} is outside the transcript")
            if (transcript.substring(e.start, e.end).isBlank()) throw InvalidAnnotation("span ${e.start}-${e.end} covers only whitespace")
            if (i > 0 && e.start < sorted[i - 1].end) throw InvalidAnnotation("spans ${sorted[i - 1].start}-${sorted[i - 1].end} and ${e.start}-${e.end} overlap")
        }
        val placeholders = PLACEHOLDER.findAll(item.asrText).map { it.value }.toList()
        for (p in placeholders.distinct()) {
            if (PLACEHOLDER.findAll(transcript).count { it.value == p } < placeholders.count { it == p }) {
                throw InvalidAnnotation("placeholder $p was removed; keep surrogates as they are")
            }
        }
        val leaked = detector.detect(transcript, KnownIdentifiers())
            .filter { it.kind in BLOCKING }
            .filterNot { s -> PLACEHOLDER.containsMatchIn(s.text) }
            .filterNot { s -> s.kind == PhiKind.PERSON_NAME && item.asrText.contains(s.text) } // surrogate names already in the item
        if (leaked.isNotEmpty()) {
            throw InvalidAnnotation("possible PHI in the transcript (${leaked.joinToString { it.kind.name.lowercase() }}); use the placeholder instead")
        }
    }

    val PLACEHOLDER = Regex("\\[[A-ZÄÖÜ]+]")
    private val BLOCKING = setOf(PhiKind.PERSON_NAME, PhiKind.INSURANCE_NUMBER, PhiKind.PHONE, PhiKind.EMAIL, PhiKind.ADDRESS, PhiKind.DATE_OF_BIRTH)
}

/**
 * Who gets which task next.
 *
 * - An annotator never sees the same item twice, and a reviewer never reviews their own work.
 * - A share of items (deterministic by item id) is annotated twice to measure agreement.
 * - Leases expire, so abandoned tasks return to the queue.
 * - Higher priority first (model uncertainty), then oldest.
 */
class Assignment(
    private val doubleAnnotationRate: Double = 0.15,
    private val lease: Duration = Duration.ofMinutes(20),
) {
    fun annotationsNeeded(item: Item): Int = if (stableUnit(item.id) < doubleAnnotationRate) 2 else 1

    fun next(user: String, open: List<Task>, items: Map<String, Item>, doneBy: Map<String, Set<String>>, now: Instant): Task? {
        return open.asSequence()
            .filter { t -> t.status == TaskStatus.OPEN || (t.status == TaskStatus.LEASED && t.leasedUntil?.isBefore(now) == true) }
            .filter { t -> user !in (doneBy[t.itemId] ?: emptySet()) }
            .sortedWith(compareByDescending<Task> { items[it.itemId]?.priority ?: 0.0 }.thenBy { it.createdAt }.thenBy { it.id })
            .firstOrNull()
            ?.copy(status = TaskStatus.LEASED, assignee = user, leasedUntil = now.plus(lease))
    }

    companion object {
        fun stableUnit(id: String): Double {
            val h = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            val v = ((h[0].toLong() and 0xff) shl 16) or ((h[1].toLong() and 0xff) shl 8) or (h[2].toLong() and 0xff)
            return v / 16_777_216.0
        }
    }
}

/** Inter-annotator agreement on doubly annotated items. */
object Agreement {
    data class Result(val items: Int, val spanF1: Double, val overlapF1: Double, val tokenKappa: Double, val transcriptWerBetween: Double)

    fun of(pairs: List<Pair<Annotation, Annotation>>): Result {
        if (pairs.isEmpty()) return Result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        var tp = 0
        var na = 0
        var nb = 0
        var otp = 0
        val tokA = mutableListOf<String>()
        val tokB = mutableListOf<String>()
        var werErr = 0
        var werRef = 0
        for ((a, b) in pairs) {
            na += a.entities.size
            nb += b.entities.size
            tp += a.entities.count { x -> b.entities.any { it == x } }
            otp += a.entities.count { x -> b.entities.any { it.label == x.label && it.start < x.end && x.start < it.end } }
            val w = workbench.eval.Wer.errors(a.transcript, b.transcript)
            werErr += w.errors
            werRef += w.referenceWords
            if (a.transcript == b.transcript) {
                tokA += tokenLabels(a)
                tokB += tokenLabels(b)
            }
        }
        val f1 = if (na + nb == 0) 1.0 else 2.0 * tp / (na + nb)
        val of1 = if (na + nb == 0) 1.0 else 2.0 * otp / (na + nb)
        return Result(pairs.size, f1, of1, cohensKappa(tokA, tokB), if (werRef == 0) 0.0 else werErr.toDouble() / werRef)
    }

    /** Label of each whitespace token ("O" outside any span). */
    fun tokenLabels(a: Annotation): List<String> {
        val out = mutableListOf<String>()
        Regex("\\S+").findAll(a.transcript).forEach { m ->
            out += a.entities.firstOrNull { m.range.first >= it.start && m.range.first < it.end }?.label?.name ?: "O"
        }
        return out
    }

    fun cohensKappa(a: List<String>, b: List<String>): Double {
        require(a.size == b.size)
        if (a.isEmpty()) return Double.NaN
        val n = a.size.toDouble()
        val po = a.indices.count { a[it] == b[it] } / n
        val cats = (a + b).toSet()
        val pe = cats.sumOf { c -> (a.count { it == c } / n) * (b.count { it == c } / n) }
        return if (pe >= 1.0) 1.0 else (po - pe) / (1 - pe)
    }
}
