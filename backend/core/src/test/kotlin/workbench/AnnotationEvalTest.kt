package workbench

import org.junit.jupiter.api.Test
import workbench.annotation.Agreement
import workbench.annotation.Annotation
import workbench.annotation.AnnotationRules
import workbench.annotation.Assignment
import workbench.annotation.EntitySpan
import workbench.annotation.InvalidAnnotation
import workbench.annotation.Item
import workbench.annotation.Label
import workbench.annotation.Task
import workbench.annotation.TaskKind
import workbench.annotation.TaskStatus
import workbench.dataset.Split
import workbench.dataset.SnapshotBuilder
import workbench.eval.EntityScore
import workbench.eval.ModelComparison
import workbench.eval.Wer
import workbench.model.EntityTagger
import workbench.model.Features
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationEvalTest {
    private val item = Item("itm_1", "t1", "p1", "f1", "r_1", Instant.EPOCH, "asr-v1", "Frau Becker in [ZIMMER] hat Schmerzen im Knie", emptyList())

    @Test
    fun `well-formed annotations pass`() {
        AnnotationRules.validate(item, "Frau Becker in [ZIMMER] hat Schmerzen im Knie.", listOf(EntitySpan(27, 36, Label.PAIN)))
    }

    @Test
    fun `invalid annotations are rejected with a reason`() {
        assertFailsWith<InvalidAnnotation> { AnnotationRules.validate(item, item.asrText, listOf(EntitySpan(30, 999, Label.PAIN))) }
        assertFailsWith<InvalidAnnotation> { AnnotationRules.validate(item, item.asrText, listOf(EntitySpan(0, 10, Label.PAIN), EntitySpan(5, 12, Label.MOOD))) }
        assertFailsWith<InvalidAnnotation> { AnnotationRules.validate(item, "Frau Becker in Zimmer 214 hat Schmerzen", emptyList()) } // placeholder removed
        val leak = assertFailsWith<InvalidAnnotation> { AnnotationRules.validate(item, "Frau Becker in [ZIMMER] hat Schmerzen, Sohn Markus Lorenz ruft an", emptyList()) }
        assertTrue("person_name" in leak.message!!)
        assertFailsWith<InvalidAnnotation> { AnnotationRules.validate(item, item.asrText + " Tel 0171 9876543", emptyList()) }
    }

    @Test
    fun `assignment respects priority, lease expiry and never repeats an annotator`() {
        val now = Instant.ofEpochSecond(10_000)
        val items = mapOf("a" to item.copy(id = "a", priority = 0.1), "b" to item.copy(id = "b", priority = 0.9))
        val tasks = listOf(
            Task("t1", "a", "p1", TaskKind.ANNOTATE, TaskStatus.OPEN, createdAt = Instant.EPOCH),
            Task("t2", "b", "p1", TaskKind.ANNOTATE, TaskStatus.OPEN, createdAt = Instant.EPOCH),
            Task("t3", "a", "p1", TaskKind.ANNOTATE, TaskStatus.LEASED, "user:x", now.minusSeconds(1), Instant.EPOCH),
        )
        val a = Assignment(lease = Duration.ofMinutes(10))
        assertEquals("t2", a.next("user:u", tasks, items, emptyMap(), now)!!.id)
        assertEquals("t1", a.next("user:u", tasks.filter { it.id != "t2" }, items, emptyMap(), now)!!.id)
        assertEquals("t3", a.next("user:u", tasks.filter { it.id == "t3" }, items, emptyMap(), now)!!.id) // expired lease is reclaimed
        assertNull(a.next("user:u", tasks.filter { it.itemId == "a" }, items, mapOf("a" to setOf("user:u")), now))
        val rate = (1..4000).count { a.annotationsNeeded(item.copy(id = "i$it")) == 2 } / 4000.0
        assertTrue(abs(rate - 0.15) < 0.02, "double-annotation rate $rate")
    }

    private fun ann(text: String, vararg e: EntitySpan) = Annotation("x", "t", "i", "u", text, e.toList(), Instant.EPOCH, 1)

    @Test
    fun `agreement measures span F1 and token kappa`() {
        val t = "RR 130/80 Puls 72 Schmerzen im Knie"
        val a = ann(t, EntitySpan(0, 9, Label.VITAL_SIGN), EntitySpan(10, 17, Label.VITAL_SIGN), EntitySpan(18, 35, Label.PAIN))
        val same = Agreement.of(listOf(a to a))
        assertEquals(1.0, same.spanF1)
        assertEquals(1.0, same.tokenKappa)
        val b = ann(t, EntitySpan(0, 9, Label.VITAL_SIGN), EntitySpan(18, 27, Label.PAIN))
        val r = Agreement.of(listOf(a to b))
        assertEquals(2.0 * 1 / 5, r.spanF1, 1e-9)
        assertEquals(2.0 * 2 / 5, r.overlapF1, 1e-9)
        assertEquals((3.0 / 7 - 11.0 / 49) / (1 - 11.0 / 49), r.tokenKappa, 1e-9) // 3 of 7 tokens agree, chance 11/49
    }

    @Test
    fun `word error rate counts substitutions, deletions and insertions`() {
        val c = Wer.errors("Puls 72 regelmäßig, Blutdruck 130/80", "puls 27 regelmäßig blutdruck 130/80 heute")
        assertEquals(1, c.substitutions)
        assertEquals(0, c.deletions)
        assertEquals(1, c.insertions)
        assertEquals(5, c.referenceWords)
        assertEquals(0.4, c.rate, 1e-9)
        assertEquals(1.0, Wer.errors("a b", "").rate)
    }

    @Test
    fun `entity scores are exact-match precision, recall and F1`() {
        val g = listOf(listOf(EntitySpan(0, 3, Label.PAIN), EntitySpan(5, 9, Label.MOOD)))
        val p = listOf(listOf(EntitySpan(0, 3, Label.PAIN), EntitySpan(5, 8, Label.MOOD), EntitySpan(10, 12, Label.SLEEP)))
        val (micro, per) = EntityScore.score(g, p)
        assertEquals(1.0 / 3, micro.precision, 1e-9)
        assertEquals(0.5, micro.recall, 1e-9)
        assertEquals(1.0, per.getValue(Label.PAIN).f1)
    }

    @Test
    fun `model comparison gives a confidence interval that covers the true difference`() {
        val rnd = kotlin.random.Random(3)
        val refs = (1..300).map { (1..12).joinToString(" ") { "w${rnd.nextInt(50)}" } }
        fun corrupt(s: String, p: Double) = s.split(" ").joinToString(" ") { if (rnd.nextDouble() < p) "x" else it }
        val a = refs.map { corrupt(it, 0.20) }
        val b = refs.map { corrupt(it, 0.10) }
        val r = ModelComparison.compare(refs, a, b, refs.indices.map { if (it % 2 == 0) "f1" else "f2" })
        assertTrue(r.werA in 0.17..0.23 && r.werB in 0.08..0.12)
        assertTrue(r.ci95.first < -0.06 && r.ci95.second < 0, "ci ${r.ci95}")
        assertEquals(setOf("f1", "f2"), r.bySlice.keys)
    }

    @Test
    fun `snapshots split by resident, are reproducible and explain exclusions`() {
        val items = (1..400).map { item.copy(id = "itm_$it", subject = "r_${it % 40}") }
        val anns = items.flatMap { i ->
            val n = Assignment().annotationsNeeded(i)
            if (i.id.endsWith("7")) emptyList() else (1..n).map { k -> Annotation("a_${i.id}_$k", "t", i.id, "user:$k", i.asrText + if (k == 2 && i.id.endsWith("3")) "!" else "", emptyList(), Instant.EPOCH, 10) }
        }
        val s1 = SnapshotBuilder.build("s1", "p1", 1, "user:r", Instant.EPOCH, items, anns)
        val s2 = SnapshotBuilder.build("s2", "p1", 2, "user:r", Instant.EPOCH, items.reversed(), anns.reversed())
        assertEquals(s1.manifestSha256, s2.manifestSha256)
        val splitsPerSubject = s1.examples.groupBy { it.subject }.mapValues { (_, v) -> v.map { it.split }.toSet() }
        assertTrue(splitsPerSubject.values.all { it.size == 1 })
        assertTrue(s1.counts.getValue(Split.TEST) > 0 && s1.counts.getValue(Split.TRAIN) > s1.counts.getValue(Split.TEST))
        assertEquals(40, s1.excluded["not annotated"])
        assertTrue((s1.excluded["annotators disagree, needs review"] ?: 0) > 0)
    }

    @Test
    fun `BIO tags become spans and the feature hash matches the trainer`() {
        val toks = Features.tokens("RR 130/80 , Puls 72")
        assertEquals(listOf("RR", "130/80", ",", "Puls", "72"), toks.map { it.text })
        val spans = EntityTagger.spans(toks.zip(listOf("B-VITAL_SIGN", "I-VITAL_SIGN", "O", "I-VITAL_SIGN", "I-VITAL_SIGN")))
        assertEquals(listOf(EntitySpan(0, 9, Label.VITAL_SIGN), EntitySpan(12, 19, Label.VITAL_SIGN)), spans)
        assertEquals(Integer.remainderUnsigned(0xE40C292C.toInt(), 16384), Features.hash("a", 16384)) // FNV-1a("a") = 0xE40C292C
    }
}
