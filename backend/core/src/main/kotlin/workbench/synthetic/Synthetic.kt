package workbench.synthetic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import workbench.annotation.EntitySpan
import workbench.annotation.Label
import workbench.phi.Person
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

/**
 * Synthetic care-documentation exports with ground truth, for development, tests and the demo.
 *
 * Each record is what a nurse dictated (the gold transcript, with known entity spans and planted
 * PHI: resident and relative names, date of birth, insurance number, phone, room, age, dates) and
 * what two speech models heard. `asr-v1` makes more errors than `asr-v2`, and much more in one
 * facility (a regional accent), so model comparison and per-slice evaluation have a known answer.
 * Nothing here is real data; names come from small fixed pools.
 */
class SyntheticExport(private val seed: Int = 42) {
    data class Gold(val recordId: String, val tenant: String, val residentId: String, val text: String, val entities: List<EntitySpan>)

    data class Generated(val record: JsonObject, val gold: Gold)

    data class TenantSpec(val tenant: String, val projectId: String, val facilities: List<String>)

    private val firstNames = listOf("Gertrud", "Erna", "Hildegard", "Elfriede", "Irmgard", "Liselotte", "Walter", "Friedrich", "Wilhelm", "Herbert", "Rudolf", "Gerhard", "Waltraud", "Margarete")
    private val lastNames = listOf("Hansen", "Petersen", "Lorenz", "Vogt", "Brandt", "Seidel", "Kuhn", "Engel", "Busch", "Winkler", "Dietrich", "Ludwig", "Albrecht", "Frank", "Simon", "Haas")
    private val relationFirst = listOf("Lena", "Jonas", "Sabine", "Thomas", "Petra", "Markus", "Claudia", "Stefan")
    private val staffNames = listOf(Person("Tobias", "Wiegand"), Person("Aylin", "Demir"), Person("Jana", "Kowalski"), Person("Marek", "Nowak"), Person("Sophie", "Graf"))

    private class Builder {
        val sb = StringBuilder()
        val entities = mutableListOf<EntitySpan>()

        fun text(s: String) = apply { sb.append(s) }

        fun entity(s: String, label: Label) = apply {
            entities += EntitySpan(sb.length, sb.length + s.length, label)
            sb.append(s)
        }
    }

    private fun Random.pick(xs: List<String>) = xs[nextInt(xs.size)]

    private val bodyParts = listOf("Knie", "Rücken", "Hüfte", "Schulter", "Bauch", "Kopf")
    private val meds = listOf("Metamizol" to "500 mg", "Ibuprofen" to "400 mg", "Ramipril" to "5 mg", "Metformin" to "1000 mg", "Pantoprazol" to "40 mg", "Insulin" to "8 Einheiten")
    private val schedules = listOf("morgens", "abends", "zweimal täglich", "bei Bedarf")

    private fun clinical(r: Random, b: Builder) {
        when (r.nextInt(10)) {
            0 -> b.text("Vitalzeichen: ").entity("RR ${r.nextInt(105, 165)}/${r.nextInt(60, 95)}", Label.VITAL_SIGN).text(", ").entity("Puls ${r.nextInt(55, 105)}", Label.VITAL_SIGN).text(". ")
            1 -> b.text("Gemessen wurde eine ").entity("Temperatur von ${r.nextInt(36, 39)},${r.nextInt(10)} Grad", Label.VITAL_SIGN).text(". ")
            2 -> b.text("Der ").entity("Blutzucker lag bei ${r.nextInt(80, 260)} mg/dl", Label.VITAL_SIGN).text(". ")
            3 -> {
                val (m, d) = meds[r.nextInt(meds.size)]
                b.entity(m, Label.MEDICATION).text(" ").entity("$d ${r.pick(schedules)}", Label.DOSAGE).text(" verabreicht. ")
            }
            4 -> b.text("Klagt über ").entity("Schmerzen im ${r.pick(bodyParts)}", Label.PAIN).text(", ").entity("Stärke ${r.nextInt(2, 9)} von 10", Label.PAIN).text(". ")
            5 -> b.entity("Dekubitus am ${r.pick(listOf("Steiß", "Fersenbereich", "Gesäß"))} Grad ${r.nextInt(1, 4)}", Label.WOUND).text(", Verband gewechselt. ")
            6 -> b.text(r.pick(listOf("Heute ", "Vormittags "))).entity(r.pick(listOf("mit Rollator auf dem Flur mobilisiert", "Transfer in den Rollstuhl mit Hilfe", "im Bett umgelagert")), Label.MOBILITY).text(". ")
            7 -> b.entity(r.pick(listOf("Frühstück vollständig gegessen", "Mittagessen zur Hälfte gegessen", "Trinkmenge ${r.nextInt(6, 18)}00 ml")), Label.NUTRITION).text(". ")
            8 -> b.entity(r.pick(listOf("Stuhlgang regelmäßig", "Inkontinenzmaterial gewechselt", "Urin konzentriert")), Label.EXCRETION).text(". ")
            else -> b.text("Wirkt ").entity(r.pick(listOf("unruhig", "zufrieden", "zeitlich desorientiert", "niedergeschlagen")), Label.MOOD).text(", ").entity(r.pick(listOf("hat gut geschlafen", "nachts mehrfach wach")), Label.SLEEP).text(". ")
        }
    }

    fun generate(tenants: List<TenantSpec>, recordsPerTenant: Int, start: Instant = Instant.parse("2025-03-01T06:00:00Z")): List<Generated> {
        val r = Random(seed)
        val out = mutableListOf<Generated>()
        for (t in tenants) {
            val residents = (0 until 60).map { k ->
                val dob = LocalDate.of(r.nextInt(1925, 1946), r.nextInt(1, 13), r.nextInt(1, 29))
                Triple("${t.tenant}-res-$k", Person(r.pick(firstNames), r.pick(lastNames)), dob)
            }
            val ins = residents.associate { it.first to "${('A'..'Z').random(r)}${r.nextInt(100_000_000, 999_999_999)}" }
            val room = residents.associate { it.first to "${r.nextInt(1, 4)}${r.nextInt(10, 40)}" }
            val relative = residents.associate { it.first to Person(r.pick(relationFirst), it.second.lastName) }
            val phone = residents.associate { it.first to "01${r.nextInt(51, 80)} ${r.nextInt(1_000_000, 9_999_999)}" }
            repeat(recordsPerTenant) { n ->
                val (rid, person, dob) = residents[r.nextInt(residents.size)]
                val facility = t.facilities[residents.indexOfFirst { it.first == rid } % t.facilities.size]
                val staff = staffNames[r.nextInt(staffNames.size)]
                val at = start.plusSeconds(n * 5_400L + r.nextLong(3_600))
                val day = at.atZone(java.time.ZoneOffset.UTC).toLocalDate()
                val b = Builder()
                b.text(r.pick(listOf("Bei Frau ", "Frau ", "Bewohnerin Frau ")) + person.lastName + r.pick(listOf(": ", ", ")))
                repeat(r.nextInt(2, 5)) { clinical(r, b) }
                when (r.nextInt(7)) {
                    0 -> b.text("Tochter ${relative.getValue(rid).fullName} informiert unter ${phone.getValue(rid)}. ")
                    1 -> day.minusDays(r.nextLong(1, 9)).let { d -> b.text("Sturz am %02d.%02d.%04d, seitdem engmaschige Kontrolle. ".format(d.dayOfMonth, d.monthValue, d.year)) }
                    2 -> b.text("Laut Akte geboren am %02d.%02d.%04d. ".format(dob.dayOfMonth, dob.monthValue, dob.year))
                    3 -> b.text("Krankenkasse bittet um Rückruf, Versichertennummer ${ins.getValue(rid)}. ")
                    4 -> b.text("Umzug in Zimmer ${room.getValue(rid)} besprochen. ")
                    5 -> if (day.year - dob.year >= 90) b.text("Die ${day.year - dob.year} Jahre alte Bewohnerin ist zufrieden. ") else b.text("Pfleger ${staff.lastName} hat die Angehörigen informiert. ")
                    else -> {}
                }
                val gold = b.sb.toString().trim()
                val asr1 = noisy(gold, if (facility == t.facilities.last()) 0.22 else 0.09, r)
                val asr2 = noisy(gold, 0.06, r)
                val record = buildJsonObject {
                    put("record_id", "${t.tenant}-rec-$n")
                    put("tenant", t.tenant)
                    put("project_id", t.projectId)
                    put("facility_id", facility)
                    put("resident_id", rid)
                    putJsonObject("resident") {
                        put("first_name", person.firstName)
                        put("last_name", person.lastName)
                        put("date_of_birth", dob.toString())
                        put("insurance_number", ins.getValue(rid))
                        put("room", room.getValue(rid))
                        put("phone", phone.getValue(rid))
                    }
                    putJsonArray("relatives") { add(buildJsonObject { put("first_name", relative.getValue(rid).firstName); put("last_name", relative.getValue(rid).lastName) }) }
                    putJsonArray("staff") { add(buildJsonObject { put("first_name", staff.firstName); put("last_name", staff.lastName) }) }
                    put("recorded_at", at.toString())
                    put("primary_model", "asr-v1")
                    putJsonObject("asr") {
                        put("asr-v1", asr1)
                        put("asr-v2", asr2)
                    }
                }
                out += Generated(record, Gold("${t.tenant}-rec-$n", t.tenant, rid, gold, b.entities.toList()))
            }
        }
        return out
    }

    private val protectedWords by lazy {
        (firstNames + lastNames + relationFirst + staffNames.flatMap { listOf(it.firstName, it.lastName) } + listOf("Frau", "Herr", "Tochter", "Pfleger", "Zimmer", "Versichertennummer", "geboren", "Sturz", "Jahre")).toSet()
    }

    private val confusions = mapOf(
        "Puls" to "Pulse", "Grad" to "grad", "Dekubitus" to "Dekubitusse", "Rollator" to "Rolator", "Metamizol" to "Metamizon", "Ramipril" to "Ramipil",
        "Pantoprazol" to "Panto Prazol", "Blutzucker" to "Blutdrucker", "Schmerzen" to "Schmerz", "verabreicht" to "verreicht", "Stärke" to "Stärken",
        "zufrieden" to "zu frieden", "Trinkmenge" to "Trink Menge", "Inkontinenzmaterial" to "Inkontinenz Material",
    )

    /** Speech-recognition noise: confusable words, misheard digits, dropped words, fillers. Names and identifiers stay intact (the de-identifier must find them). */
    private fun noisy(gold: String, p: Double, r: Random): String = gold.split(" ").flatMap { w ->
        val core = w.trimEnd('.', ',', ':')
        val tail = w.removePrefix(core)
        // names, honorifics and identifiers stay intact, so the de-identifier is tested on what it must find
        val protected = core in protectedWords || core.any { it.isDigit() } && core.length > 4 || core.firstOrNull() == 'K' && core.drop(1).all { it.isDigit() }
        when {
            protected || r.nextDouble() > p -> listOf(w)
            core in confusions -> listOf(confusions.getValue(core) + tail)
            core.all { it.isDigit() } && core.isNotEmpty() -> listOf(core.map { if (r.nextBoolean()) ((it.digitToInt() + 1) % 10).digitToChar() else it }.joinToString("") + tail)
            r.nextDouble() < 0.5 -> emptyList()
            else -> listOf(w, r.pick(listOf("äh", "also", "und")))
        }
    }.joinToString(" ")

    companion object {
        val DEFAULT_TENANTS = listOf(
            TenantSpec("pflege-nord", "nord-doku", listOf("hamburg-alster", "kiel-hafen", "luebeck-mitte")),
            TenantSpec("pflege-sued", "sued-doku", listOf("muenchen-west", "augsburg-ost")),
        )
        val US_TENANT = TenantSpec("carehome-us", "us-docs", listOf("ohio-1"))
    }
}
