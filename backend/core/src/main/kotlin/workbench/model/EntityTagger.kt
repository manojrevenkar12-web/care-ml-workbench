package workbench.model

import workbench.annotation.EntitySpan
import workbench.annotation.Label
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Token-level entity tagger served by the API (model playground, pre-annotation suggestions).
 *
 * The model is trained by the Python training job (`trainer/train.py`): multinomial logistic
 * regression over hashed token features with BIO labels. Tokenisation and features are defined
 * identically here and in Python (FNV-1a hashing, same feature strings); `trainer/parity.json`,
 * produced by the trainer, is checked by a Kotlin test so the two can never drift apart silently.
 */
object Features {
    const val VERSION = 1
    val TOKEN = Regex("[A-Za-zÄÖÜäöüß0-9]+(?:[.,/][0-9]+)*|\\[[A-ZÄÖÜ]+]|\\S")

    data class Token(val text: String, val start: Int, val end: Int)

    fun tokens(text: String): List<Token> = TOKEN.findAll(text).map { Token(it.value, it.range.first, it.range.last + 1) }.toList()

    fun shape(w: String): String {
        val sb = StringBuilder()
        for (c in w) {
            val s = when {
                c.isUpperCase() -> 'X'
                c.isLowerCase() -> 'x'
                c.isDigit() -> 'd'
                else -> c
            }
            if (sb.isEmpty() || sb.last() != s) sb.append(s)
        }
        return sb.toString()
    }

    fun of(tokens: List<Token>, i: Int): List<String> {
        val w = tokens[i].text.lowercase()
        val prev = if (i > 0) tokens[i - 1].text.lowercase() else "<s>"
        val next = if (i + 1 < tokens.size) tokens[i + 1].text.lowercase() else "</s>"
        return listOf("b", "w=$w", "s3=${w.takeLast(3)}", "p3=${w.take(3)}", "shape=${shape(tokens[i].text)}", "pw=$prev", "nw=$next", "pw|w=$prev|$w")
    }

    /** FNV-1a, 32 bit, over UTF-8 bytes — implemented identically in trainer/features.py. */
    fun hash(s: String, buckets: Int): Int {
        var h = 0x811C9DC5.toInt()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xff)
            h *= 0x01000193
        }
        return Integer.remainderUnsigned(h, buckets)
    }
}

class EntityTagger(val name: String, private val labels: List<String>, private val buckets: Int, private val weights: FloatArray, private val intercept: FloatArray) {
    init {
        require(weights.size == labels.size * buckets) { "weights do not match labels × buckets" }
    }

    fun predictTokens(text: String): List<Pair<Features.Token, String>> {
        val toks = Features.tokens(text)
        return toks.indices.map { i ->
            val active = Features.of(toks, i).map { Features.hash(it, buckets) }
            var best = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for (l in labels.indices) {
                var s = intercept[l].toDouble()
                val base = l * buckets
                for (f in active) s += weights[base + f]
                if (s > bestScore) { bestScore = s; best = l }
            }
            toks[i] to labels[best]
        }
    }

    fun predict(text: String): List<EntitySpan> = spans(predictTokens(text))

    companion object {
        /** BIO tags to spans; an I- tag without a matching B- starts a new span. */
        fun spans(tagged: List<Pair<Features.Token, String>>): List<EntitySpan> {
            val out = mutableListOf<EntitySpan>()
            var cur: Triple<Int, Int, String>? = null
            for ((tok, tag) in tagged) {
                val type = tag.substringAfter('-', "")
                val continues = tag.startsWith("I-") && cur?.third == type
                if (continues) {
                    cur = Triple(cur!!.first, tok.end, type)
                    continue
                }
                cur?.let { out += EntitySpan(it.first, it.second, Label.valueOf(it.third)) }
                cur = if (tag == "O") null else Triple(tok.start, tok.end, type)
            }
            cur?.let { out += EntitySpan(it.first, it.second, Label.valueOf(it.third)) }
            return out
        }

        /** Load the trainer's model.json (parsed by the caller into these fields). */
        fun fromBase64(name: String, labels: List<String>, buckets: Int, weightsB64: String, interceptB64: String): EntityTagger {
            fun floats(b64: String): FloatArray {
                val buf = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
            return EntityTagger(name, labels, buckets, floats(weightsB64), floats(interceptB64))
        }
    }
}
