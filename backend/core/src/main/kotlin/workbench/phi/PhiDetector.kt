package workbench.phi

/**
 * Finds protected health information in German care documentation.
 *
 * Three detectors, combined:
 * 1. Known identifiers from the structured record (resident, relatives, staff, date of birth,
 *    insurance number, room) — exact and case-insensitive, including surname-only mentions.
 * 2. Patterns: health-insurance numbers (KVNR), phone numbers, e-mail addresses, dates, street
 *    addresses, room numbers and ages over 89 (a HIPAA Safe Harbor identifier).
 * 3. Context: a capitalised word after an honorific or kinship word ("Frau Keller",
 *    "Tochter Anna") is a person name even when the record does not list it.
 *
 * Overlapping findings are resolved in favour of the longer span, then the more specific kind.
 * Recall matters more than precision here: a missed name reaches an annotator, an extra
 * placeholder costs a word of context.
 */
class PhiDetector {
    fun detect(text: String, known: KnownIdentifiers = KnownIdentifiers()): List<PhiSpan> {
        val found = mutableListOf<PhiSpan>()
        found += knownMatches(text, known)
        for ((kind, regex) in PATTERNS) {
            regex.findAll(text).forEach { m ->
                val g = if ("(?<v>" in regex.pattern) m.groups["v"]!! else m.groups[0]!!
                found += PhiSpan(g.range.first, g.range.last + 1, kind, g.value)
            }
        }
        CONTEXT_NAME.findAll(text).forEach { m ->
            val g = m.groups["name"]!!
            if (g.value.lowercase() !in NOT_NAMES) found += PhiSpan(g.range.first, g.range.last + 1, PhiKind.PERSON_NAME, g.value)
        }
        return resolve(found)
    }

    private fun knownMatches(text: String, known: KnownIdentifiers): List<PhiSpan> {
        val out = mutableListOf<PhiSpan>()
        fun add(value: String?, kind: PhiKind) {
            if (value.isNullOrBlank() || value.length < 2) return
            Regex("(?<![\\p{L}\\d])" + Regex.escape(value) + "(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
                .findAll(text)
                .forEach { out += PhiSpan(it.range.first, it.range.last + 1, kind, it.value) }
        }
        for (p in known.people) {
            add(p.fullName, PhiKind.PERSON_NAME)
            add(p.lastName, PhiKind.PERSON_NAME)
            if (p.firstName.length >= 3) add(p.firstName, PhiKind.PERSON_NAME)
        }
        add(known.insuranceNumber, PhiKind.INSURANCE_NUMBER)
        add(known.phone, PhiKind.PHONE)
        known.dateOfBirth?.let { dob ->
            for (f in DateText.formats(dob)) add(f, PhiKind.DATE_OF_BIRTH)
        }
        // room numbers are matched only in context ("Zimmer 12"): a bare "12" is usually a time or a dose
        return out
    }

    companion object {
        private const val UPPER = "A-ZÄÖÜ"
        private const val LOWER = "a-zäöüß"

        val PATTERNS: List<Pair<PhiKind, Regex>> = listOf(
            PhiKind.INSURANCE_NUMBER to Regex("(?<![A-Za-z0-9])[A-Z]\\d{9}(?![A-Za-z0-9])"),
            PhiKind.EMAIL to Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"),
            PhiKind.PHONE to Regex("(?<![\\d])(?:\\+49|0049|0)[1-9][\\d /-]{6,14}\\d(?![\\d])"),
            PhiKind.DATE to Regex("(?<!\\d)\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?(?:\\d{4}|\\d{2})(?!\\d)"),
            PhiKind.DATE to Regex(
                "(?<!\\d)\\d{1,2}\\.\\s?(?:Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)(?:\\s\\d{4})?",
            ),
            PhiKind.ADDRESS to Regex("[$UPPER][$LOWER]+(?:straße|strasse|str\\.|weg|platz|allee|gasse|ring)\\s\\d{1,4}[a-z]?"),
            PhiKind.ROOM to Regex("(?:Zimmer|Zi\\.|Raum)\\s?(?<v>\\d{1,4}[a-z]?)"),
            PhiKind.AGE_OVER_89 to Regex("(?<v>(?:9\\d|1[01]\\d))(?:\\s?Jahre|-jährig|\\s?J\\.)"),
        )

        /** Honorific or relation followed by one or two capitalised words. */
        val CONTEXT_NAME = Regex(
            "(?:Frau|Herr|Herrn|Fr\\.|Hr\\.|Dr\\.|Schwester|Pfleger|Tochter|Sohn|Ehemann|Ehefrau|Enkelin|Enkel|Nichte|Neffe|Betreuerin|Betreuer)\\s+" +
                "(?<name>[$UPPER][$LOWER]+(?:[\\s-][$UPPER][$LOWER]+)?)",
        )

        /** Capitalised words that follow those triggers but are not names (care vocabulary). */
        val NOT_NAMES = setOf("bewohnerin", "bewohner", "patientin", "patient", "doktor", "hausarzt", "hausärztin", "pflegekraft", "angehörige", "angehörigen")

        private val PRIORITY = PhiKind.entries.associateWith { it.ordinal }

        fun resolve(spans: List<PhiSpan>): List<PhiSpan> {
            val sorted = spans.sortedWith(compareBy<PhiSpan> { it.start }.thenByDescending { it.end - it.start }.thenBy { PRIORITY[it.kind] })
            val out = mutableListOf<PhiSpan>()
            for (s in sorted) {
                val last = out.lastOrNull()
                if (last != null && s.start < last.end) {
                    if (s.end - s.start > last.end - last.start) out[out.size - 1] = s
                    continue
                }
                out += s
            }
            return out
        }
    }
}

enum class PhiKind {
    PERSON_NAME,
    DATE_OF_BIRTH,
    INSURANCE_NUMBER,
    PHONE,
    EMAIL,
    ADDRESS,
    ROOM,
    AGE_OVER_89,
    DATE,
}

data class PhiSpan(val start: Int, val end: Int, val kind: PhiKind, val text: String)

data class Person(val firstName: String, val lastName: String) {
    val fullName get() = "$firstName $lastName"
}

/** Identifiers known from the structured part of a record. */
data class KnownIdentifiers(
    val people: List<Person> = emptyList(),
    val dateOfBirth: java.time.LocalDate? = null,
    val insuranceNumber: String? = null,
    val phone: String? = null,
    val room: String? = null,
)

object DateText {
    private val MONTHS = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

    /** The ways a date is commonly written in German documentation. */
    fun formats(d: java.time.LocalDate): List<String> = listOf(
        "%02d.%02d.%04d".format(d.dayOfMonth, d.monthValue, d.year),
        "%d.%d.%04d".format(d.dayOfMonth, d.monthValue, d.year),
        "%02d.%02d.%02d".format(d.dayOfMonth, d.monthValue, d.year % 100),
        "${d.dayOfMonth}. ${MONTHS[d.monthValue - 1]} ${d.year}",
    )

    fun parse(text: String, reference: java.time.LocalDate): java.time.LocalDate? {
        Regex("(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{2,4})").matchEntire(text.trim())?.let { m ->
            val (d, mo, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            return runCatching { java.time.LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()
        }
        Regex("(\\d{1,2})\\.\\s?(\\p{L}+)(?:\\s(\\d{4}))?").matchEntire(text.trim())?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[2]) + 1
            if (month == 0) return null
            val year = m.groupValues[3].ifEmpty { reference.year.toString() }.toInt()
            return runCatching { java.time.LocalDate.of(year, month, m.groupValues[1].toInt()) }.getOrNull()
        }
        return null
    }

    /** Write a date back in the same style it was found in. */
    fun formatLike(original: String, d: java.time.LocalDate): String {
        val t = original.trim()
        return when {
            Regex("\\d{2}\\.\\d{2}\\.\\d{4}").matches(t) -> "%02d.%02d.%04d".format(d.dayOfMonth, d.monthValue, d.year)
            Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{4}").matches(t) -> "%d.%d.%04d".format(d.dayOfMonth, d.monthValue, d.year)
            Regex("\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2}").matches(t) -> "%02d.%02d.%02d".format(d.dayOfMonth, d.monthValue, d.year % 100)
            Regex(".*\\d{4}").matches(t) -> "${d.dayOfMonth}. ${MONTHS[d.monthValue - 1]} ${d.year}"
            else -> "${d.dayOfMonth}. ${MONTHS[d.monthValue - 1]}"
        }
    }
}
