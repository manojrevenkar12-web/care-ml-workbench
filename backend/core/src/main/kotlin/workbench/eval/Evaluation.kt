package workbench.eval

import workbench.annotation.EntitySpan
import workbench.annotation.Label
import kotlin.random.Random

/** Word error rate: word-level edit distance after normalisation (case, punctuation). */
object Wer {
    data class Counts(val substitutions: Int, val deletions: Int, val insertions: Int, val referenceWords: Int) {
        val errors get() = substitutions + deletions + insertions
        val rate get() = if (referenceWords == 0) 0.0 else errors.toDouble() / referenceWords
    }

    fun normalise(s: String): List<String> = s.lowercase()
        .replace(Regex("[\\p{P}&&[^\\[\\]+%/]]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    fun errors(reference: String, hypothesis: String): Counts {
        val r = normalise(reference)
        val h = normalise(hypothesis)
        // dp over (i, j) keeping (cost, subs, dels, ins) for the minimum-cost alignment
        val cost = Array(r.size + 1) { IntArray(h.size + 1) }
        val op = Array(r.size + 1) { ByteArray(h.size + 1) } // 0 match, 1 sub, 2 del, 3 ins
        for (i in 1..r.size) { cost[i][0] = i; op[i][0] = 2 }
        for (j in 1..h.size) { cost[0][j] = j; op[0][j] = 3 }
        for (i in 1..r.size) {
            for (j in 1..h.size) {
                val same = r[i - 1] == h[j - 1]
                val diag = cost[i - 1][j - 1] + if (same) 0 else 1
                val del = cost[i - 1][j] + 1
                val ins = cost[i][j - 1] + 1
                if (diag <= del && diag <= ins) {
                    cost[i][j] = diag
                    op[i][j] = if (same) 0 else 1
                } else if (del <= ins) {
                    cost[i][j] = del
                    op[i][j] = 2
                } else {
                    cost[i][j] = ins
                    op[i][j] = 3
                }
            }
        }
        var i = r.size
        var j = h.size
        var s = 0
        var d = 0
        var n = 0
        while (i > 0 || j > 0) {
            when (op[i][j].toInt()) {
                0 -> { i--; j-- }
                1 -> { s++; i--; j-- }
                2 -> { d++; i-- }
                else -> { n++; j-- }
            }
        }
        return Counts(s, d, n, r.size)
    }
}

/** Entity spans scored by exact match (start, end, label): micro and per-label precision/recall/F1. */
object EntityScore {
    data class Prf(val precision: Double, val recall: Double, val f1: Double, val support: Int)

    fun score(gold: List<List<EntitySpan>>, predicted: List<List<EntitySpan>>): Pair<Prf, Map<Label, Prf>> {
        require(gold.size == predicted.size)
        val tp = mutableMapOf<Label, Int>()
        val fp = mutableMapOf<Label, Int>()
        val fn = mutableMapOf<Label, Int>()
        for (k in gold.indices) {
            val g = gold[k].toSet()
            val p = predicted[k].toSet()
            for (x in p) if (x in g) tp.merge(x.label, 1, Int::plus) else fp.merge(x.label, 1, Int::plus)
            for (x in g) if (x !in p) fn.merge(x.label, 1, Int::plus)
        }
        fun prf(t: Int, f: Int, n: Int): Prf {
            val p = if (t + f == 0) 0.0 else t.toDouble() / (t + f)
            val r = if (t + n == 0) 0.0 else t.toDouble() / (t + n)
            return Prf(p, r, if (p + r == 0.0) 0.0 else 2 * p * r / (p + r), t + n)
        }
        val per = Label.entries.associateWith { prf(tp[it] ?: 0, fp[it] ?: 0, fn[it] ?: 0) }.filterValues { it.support > 0 }
        return prf(tp.values.sum(), fp.values.sum(), fn.values.sum()) to per
    }
}

/**
 * Compare two speech models on the same items: corpus WER of each, and a paired bootstrap over
 * items for the difference, so "model B is better" comes with a confidence interval rather than
 * a single number on a few hundred utterances.
 */
object ModelComparison {
    data class Result(
        val items: Int,
        val werA: Double,
        val werB: Double,
        val difference: Double,
        val ci95: Pair<Double, Double>,
        val bySlice: Map<String, Pair<Double, Double>>,
    )

    fun compare(references: List<String>, a: List<String>, b: List<String>, slices: List<String>, resamples: Int = 2000, seed: Int = 7): Result {
        val n = references.size
        require(a.size == n && b.size == n && slices.size == n && n > 0)
        val ea = IntArray(n)
        val eb = IntArray(n)
        val words = IntArray(n)
        for (k in 0 until n) {
            val ca = Wer.errors(references[k], a[k])
            val cb = Wer.errors(references[k], b[k])
            ea[k] = ca.errors
            eb[k] = cb.errors
            words[k] = ca.referenceWords
        }
        fun rate(e: IntArray, idx: IntArray) = idx.sumOf { e[it] }.toDouble() / maxOf(1, idx.sumOf { words[it] })
        val all = IntArray(n) { it }
        val rnd = Random(seed)
        val diffs = DoubleArray(resamples) {
            val idx = IntArray(n) { rnd.nextInt(n) }
            rate(eb, idx) - rate(ea, idx)
        }
        diffs.sort()
        val by = slices.indices.groupBy { slices[it] }.mapValues { (_, ix) -> rate(ea, ix.toIntArray()) to rate(eb, ix.toIntArray()) }.toSortedMap()
        return Result(n, rate(ea, all), rate(eb, all), rate(eb, all) - rate(ea, all), diffs[(0.025 * resamples).toInt()] to diffs[(0.975 * resamples).toInt() - 1], by)
    }
}
