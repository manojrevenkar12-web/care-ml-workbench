package workbench.synthetic

import workbench.annotation.EntitySpan
import workbench.annotation.Label
import kotlin.random.Random

/**
 * Simulated annotators for the demo and for end-to-end tests: they see the de-identified
 * transcript and produce the de-identified gold with typical human errors — a missed entity, a
 * wrong label, a boundary off by a word, a typo. Error rates differ per annotator, so agreement
 * and review numbers behave like a real labelling team's.
 */
class SyntheticAnnotator(val user: String, private val missRate: Double, private val labelErrorRate: Double, private val boundaryRate: Double, private val typoRate: Double, seed: Int) {
    private val r = Random(seed)

    fun annotate(deidGold: String, gold: List<EntitySpan>): Pair<String, List<EntitySpan>> {
        val out = mutableListOf<EntitySpan>()
        for (e in gold) {
            if (r.nextDouble() < missRate) continue
            var span = e
            if (r.nextDouble() < labelErrorRate) span = span.copy(label = Label.entries[(e.label.ordinal + 1 + r.nextInt(Label.entries.size - 1)) % Label.entries.size])
            if (r.nextDouble() < boundaryRate) {
                val lastSpace = deidGold.lastIndexOf(' ', span.end - 1)
                if (lastSpace > span.start) span = span.copy(end = lastSpace)
            }
            out += span
        }
        var text = deidGold
        if (r.nextDouble() < typoRate) {
            // a typo at the very end keeps every span offset valid
            text = deidGold.trimEnd('.') + ".."
        }
        return text to out
    }

    companion object {
        /** Locate gold entities (written against the original text) in the de-identified text. */
        fun relocate(original: String, entities: List<EntitySpan>, deidentified: String): List<EntitySpan> {
            var from = 0
            return entities.mapNotNull { e ->
                val surface = original.substring(e.start, e.end)
                val at = deidentified.indexOf(surface, from)
                if (at < 0) null else EntitySpan(at, at + surface.length, e.label).also { from = at + surface.length }
            }
        }

        fun team(seed: Int = 1) = listOf(
            SyntheticAnnotator("user:anna", 0.03, 0.02, 0.03, 0.02, seed),
            SyntheticAnnotator("user:ben", 0.06, 0.03, 0.05, 0.04, seed + 1),
            SyntheticAnnotator("user:chris", 0.10, 0.05, 0.08, 0.05, seed + 2),
        )
    }
}
