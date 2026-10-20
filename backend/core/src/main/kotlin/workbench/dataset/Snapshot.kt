package workbench.dataset

import workbench.annotation.Annotation
import workbench.annotation.Assignment
import workbench.annotation.EntitySpan
import workbench.annotation.Item
import java.security.MessageDigest
import java.time.Instant

enum class Split { TRAIN, DEV, TEST }

data class Example(
    val itemId: String,
    val split: Split,
    val facilityId: String,
    val subject: String,
    val text: String,
    val entities: List<EntitySpan>,
    val asr: Map<String, String>, // speech-model outputs for WER evaluation, by model name
)

data class Snapshot(
    val id: String,
    val projectId: String,
    val version: Int,
    val createdBy: String,
    val createdAt: Instant,
    val manifestSha256: String,
    val counts: Map<Split, Int>,
    val labelCounts: Map<String, Int>,
    val excluded: Map<String, Int>,
    val examples: List<Example>,
)

/**
 * Builds an immutable training snapshot from reviewed annotations.
 *
 * - The reference for an item is the adjudicated annotation; a single annotation counts when the
 *   item was not sampled for double annotation; two identical annotations count; anything else
 *   waits for review and is excluded (and counted, so the gap is visible).
 * - Splits are assigned per resident (pseudonymous subject key), so no resident appears in both
 *   training and test data — otherwise test scores reward memorising one person's vocabulary.
 * - The manifest hash covers every example; the same inputs always give the same hash, and the
 *   training job records which hash it trained on.
 */
object SnapshotBuilder {
    fun build(
        id: String,
        projectId: String,
        version: Int,
        createdBy: String,
        now: Instant,
        items: List<Item>,
        annotations: List<Annotation>,
        assignment: Assignment = Assignment(),
    ): Snapshot {
        val byItem = annotations.groupBy { it.itemId }
        val examples = mutableListOf<Example>()
        val excluded = sortedMapOf<String, Int>()
        for (item in items.sortedBy { it.id }) {
            val anns = byItem[item.id].orEmpty()
            val ref = reference(item, anns, assignment)
            if (ref == null) {
                val reason = when {
                    anns.isEmpty() -> "not annotated"
                    anns.size < assignment.annotationsNeeded(item) -> "waiting for second annotation"
                    else -> "annotators disagree, needs review"
                }
                excluded.merge(reason, 1, Int::plus)
                continue
            }
            examples += Example(item.id, splitOf(item.subject), item.facilityId, item.subject, ref.transcript, ref.entities.sortedBy { it.start }, mapOf(item.asrModel to item.asrText) + item.predictions)
        }
        val labelCounts = examples.flatMap { e -> e.entities.map { it.label.name } }.groupingBy { it }.eachCount().toSortedMap()
        return Snapshot(id, projectId, version, createdBy, now, manifestHash(examples), Split.entries.associateWith { s -> examples.count { it.split == s } }, labelCounts, excluded, examples)
    }

    fun reference(item: Item, anns: List<Annotation>, assignment: Assignment): Annotation? {
        anns.firstOrNull { it.final }?.let { return it }
        val needed = assignment.annotationsNeeded(item)
        if (anns.size == 1 && needed == 1) return anns[0]
        if (anns.size >= 2 && anns[0].transcript == anns[1].transcript && anns[0].entities.toSet() == anns[1].entities.toSet()) return anns[0]
        return null
    }

    fun splitOf(subject: String): Split {
        val u = Assignment.stableUnit("split:$subject")
        return when {
            u < 0.8 -> Split.TRAIN
            u < 0.9 -> Split.DEV
            else -> Split.TEST
        }
    }

    fun manifestHash(examples: List<Example>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (e in examples.sortedBy { it.itemId }) {
            md.update("${e.itemId}\u0000${e.split}\u0000${e.text}\u0000".toByteArray())
            e.entities.forEach { md.update("${it.start}:${it.end}:${it.label};".toByteArray()) }
            md.update("\n".toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
