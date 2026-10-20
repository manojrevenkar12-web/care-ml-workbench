package workbench.phi

import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Replaces PHI with surrogates before any text leaves the PHI zone.
 *
 * - Names become realistic surrogate names, chosen by a keyed hash so the same person gets the
 *   same surrogate everywhere in a tenant's data (models still learn that "Frau X" recurs), while
 *   different tenants map the same name differently.
 * - Dates move by a per-resident offset, so intervals ("seit drei Tagen", "am 12.03. Sturz") stay
 *   meaningful for training; the date of birth is removed entirely.
 * - Identifiers that carry no signal for the model (insurance number, phone, e-mail, address,
 *   room) become typed placeholders; ages over 89 become "90+" (HIPAA Safe Harbor).
 *
 * The key is per tenant and lives in the PHI zone; without it surrogates cannot be reversed.
 */
class Deidentifier(private val tenantKey: ByteArray, private val detector: PhiDetector = PhiDetector()) {
    fun deidentify(text: String, known: KnownIdentifiers, subjectKey: String, reference: LocalDate): DeidResult {
        val spans = detector.detect(text, known)
        val avoid = known.people.flatMap { listOf(it.firstName.lowercase(), it.lastName.lowercase()) }.toSet()
        val out = StringBuilder()
        val surrogates = mutableListOf<SurrogateSpan>()
        var pos = 0
        for (s in spans) {
            out.append(text, pos, s.start)
            val replacement = surrogate(s, subjectKey, reference, avoid)
            val start = out.length
            out.append(replacement)
            surrogates += SurrogateSpan(start, out.length, s.kind)
            pos = s.end
        }
        out.append(text, pos, text.length)
        return DeidResult(out.toString(), surrogates, spans.groupingBy { it.kind }.eachCount())
    }

    private fun surrogate(s: PhiSpan, subjectKey: String, reference: LocalDate, avoid: Set<String>): String = when (s.kind) {
        PhiKind.PERSON_NAME -> surrogateName(s.text, avoid)
        PhiKind.DATE -> DateText.parse(s.text, reference)?.let { DateText.formatLike(s.text, it.plusDays(dateOffset(subjectKey))) } ?: "[DATUM]"
        PhiKind.DATE_OF_BIRTH -> "[GEBURTSDATUM]"
        PhiKind.INSURANCE_NUMBER -> "[VERSICHERTENNR]"
        PhiKind.PHONE -> "[TELEFON]"
        PhiKind.EMAIL -> "[EMAIL]"
        PhiKind.ADDRESS -> "[ADRESSE]"
        PhiKind.ROOM -> "[ZIMMER]"
        PhiKind.AGE_OVER_89 -> "90+"
    }

    private fun surrogateName(original: String, avoid: Set<String>): String {
        val parts = original.split(Regex("[\\s-]+")).filter { it.isNotBlank() }
        return parts.mapIndexed { i, p ->
            val pool = if (parts.size > 1 && i == 0) FIRST_NAMES else LAST_NAMES
            var k = (hmac("name:" + p.lowercase()).toUInt() % pool.size.toUInt()).toInt()
            // never hand back a real name of anyone in the record: skip to the next candidate
            while (pool[k].equals(p, ignoreCase = true) || pool[k].lowercase() in avoid) k = (k + 1) % pool.size
            pool[k]
        }.joinToString(" ")
    }

    /** Days to shift every date of one resident: ±30..365, never zero, stable per tenant. */
    fun dateOffset(subjectKey: String): Long {
        val h = hmac("date:$subjectKey").toUInt()
        val magnitude = 30 + (h % 336u).toLong()
        return if ((h shr 31) == 1u) magnitude else -magnitude
    }

    /** Stable pseudonymous key for a resident, for grouping and leakage-free splits. */
    fun subjectPseudonym(tenant: String, residentId: String): String = "r_" + hex(hmacBytes("subject:$tenant:$residentId")).take(16)

    private fun hmac(s: String): Int {
        val b = hmacBytes(s)
        return (b[0].toInt() and 0xff shl 24) or (b[1].toInt() and 0xff shl 16) or (b[2].toInt() and 0xff shl 8) or (b[3].toInt() and 0xff)
    }

    private fun hmacBytes(s: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(tenantKey, "HmacSHA256"))
        return mac.doFinal(s.toByteArray())
    }

    companion object {
        val FIRST_NAMES = listOf("Anna", "Maria", "Helga", "Ursula", "Ingrid", "Renate", "Karl", "Werner", "Heinz", "Günter", "Dieter", "Brigitte", "Monika", "Klaus", "Erika", "Hans")
        val LAST_NAMES = listOf(
            "Becker", "Hoffmann", "Schäfer", "Koch", "Richter", "Klein", "Wolf", "Neumann", "Schwarz", "Zimmermann",
            "Braun", "Krüger", "Hofmann", "Hartmann", "Lange", "Werner", "Krause", "Lehmann", "Köhler", "Maier",
        )

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

data class SurrogateSpan(val start: Int, val end: Int, val kind: PhiKind)

data class DeidResult(val text: String, val surrogates: List<SurrogateSpan>, val found: Map<PhiKind, Int>)
