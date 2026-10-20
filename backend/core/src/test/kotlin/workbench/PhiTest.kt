package workbench

import org.junit.jupiter.api.Test
import workbench.phi.Deidentifier
import workbench.phi.KnownIdentifiers
import workbench.phi.LocalKeyProvider
import workbench.phi.Person
import workbench.phi.PhiDetector
import workbench.phi.PhiKind
import workbench.phi.PhiVault
import workbench.phi.Region
import workbench.phi.Residency
import workbench.phi.ResidencyViolation
import workbench.phi.TenantPolicy
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhiTest {
    private val known = KnownIdentifiers(
        people = listOf(Person("Gertrud", "Hansen"), Person("Lena", "Hansen"), Person("Tobias", "Wiegand")),
        dateOfBirth = LocalDate.of(1931, 4, 7),
        insuranceNumber = "K123456789",
        phone = "0171 2345678",
    )
    private val text = "Frau Hansen (geb. 07.04.1931, KV-Nr K123456789) in Zimmer 214 klagt über Schmerzen. " +
        "Tochter Lena Hansen informiert unter 0171 2345678, mail lena.h@example.de. Herr Brandt aus dem Nachbarzimmer half. " +
        "Sturz am 12.03.2024, Bewohnerin ist 93 Jahre alt, wohnt sonst Lindenstraße 4. Pfleger Wiegand dokumentiert um 12 Uhr."

    @Test
    fun `detects structured, pattern and context PHI`() {
        val kinds = PhiDetector().detect(text, known).groupingBy { it.kind }.eachCount()
        assertEquals(4, kinds[PhiKind.PERSON_NAME]) // Hansen, Lena Hansen, Brandt (context only), Wiegand
        assertEquals(1, kinds[PhiKind.DATE_OF_BIRTH])
        assertEquals(1, kinds[PhiKind.INSURANCE_NUMBER])
        assertEquals(1, kinds[PhiKind.PHONE])
        assertEquals(1, kinds[PhiKind.EMAIL])
        assertEquals(1, kinds[PhiKind.ROOM])
        assertEquals(1, kinds[PhiKind.AGE_OVER_89])
        assertEquals(1, kinds[PhiKind.ADDRESS])
        assertEquals(1, kinds[PhiKind.DATE])
    }

    @Test
    fun `does not flag care vocabulary or clock times`() {
        val spans = PhiDetector().detect("Frau Bewohnerin erhielt um 12 Uhr 500 ml Tee, RR 130/80, Puls 72.", KnownIdentifiers())
        assertTrue(spans.isEmpty(), "unexpected: $spans")
    }

    @Test
    fun `de-identification removes every identifier and keeps the text usable`() {
        val d = Deidentifier("tenant-a-secret-key-0123456789abcdef".toByteArray())
        val r = d.deidentify(text, known, "res-1", LocalDate.of(2024, 3, 15))
        for (secret in listOf("Hansen", "Gertrud", "Wiegand", "Brandt", "K123456789", "0171", "lena.h@", "1931", "214", "Lindenstraße", "93 Jahre")) {
            assertFalse(r.text.contains(secret), "'$secret' survived: ${r.text}")
        }
        assertContains(r.text, "klagt über Schmerzen")
        assertContains(r.text, "90+")
        assertContains(r.text, "[VERSICHERTENNR]")
        assertContains(r.text, "12 Uhr")
        assertEquals(r.surrogates.size, r.found.values.sum())
        r.surrogates.forEach { assertTrue(it.end <= r.text.length) }
    }

    @Test
    fun `surrogates are consistent within a tenant and differ across tenants`() {
        val a = Deidentifier("tenant-a-secret-key-0123456789abcdef".toByteArray())
        val b = Deidentifier("tenant-b-secret-key-0123456789abcdef".toByteArray())
        val k = KnownIdentifiers(people = listOf(Person("Gertrud", "Hansen")))
        val ref = LocalDate.of(2024, 1, 1)
        val x = a.deidentify("Frau Hansen schläft.", k, "r1", ref).text
        val y = a.deidentify("Heute isst Frau Hansen gut.", k, "r1", ref).text
        assertEquals(x.split(" ")[1], y.split(" ")[3])
        val names = (1..12).map { b.deidentify("Frau Name$it", KnownIdentifiers(people = listOf(Person("X", "Name$it"))), "r", ref).text } +
            (1..12).map { a.deidentify("Frau Name$it", KnownIdentifiers(people = listOf(Person("X", "Name$it"))), "r", ref).text }
        assertNotEquals(names.take(12), names.drop(12))
    }

    @Test
    fun `dates shift by one offset per resident, so intervals survive`() {
        val d = Deidentifier("tenant-a-secret-key-0123456789abcdef".toByteArray())
        val r = d.deidentify("Sturz am 10.03.2024, Kontrolle am 17.03.2024.", KnownIdentifiers(), "res-7", LocalDate.of(2024, 3, 20)).text
        val dates = Regex("\\d{2}\\.\\d{2}\\.\\d{4}").findAll(r).map { LocalDate.parse(it.value, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.toList()
        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(dates[0], dates[1]))
        assertNotEquals(LocalDate.of(2024, 3, 10), dates[0])
        val off = d.dateOffset("res-7")
        assertTrue(kotlin.math.abs(off) in 30..365)
    }

    @Test
    fun `vault round-trips and refuses tampering, swapped records and foreign tenants`() {
        val keys = LocalKeyProvider(ByteArray(32) { it.toByte() })
        val v = PhiVault(keys)
        val s = v.seal("tenant-a", "itm_1", "Gertrud Hansen".toByteArray())
        assertEquals("Gertrud Hansen", String(v.open(s)))
        assertFalse(s.ciphertext.contains("Hansen"))
        val ct = PhiVault.unb64(s.ciphertext).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { v.open(s.copy(ciphertext = PhiVault.b64(ct))) }
        assertFailsWith<Exception> { v.open(s.copy(recordId = "itm_2")) }
        assertFailsWith<Exception> { v.open(s.copy(tenant = "tenant-b")) }
    }

    @Test
    fun `US PHI needs a BAA and the US deployment and EU data stays in the EU`() {
        Residency.check(TenantPolicy("eu", Region.EU, false), Region.EU)
        Residency.check(TenantPolicy("us", Region.US, true), Region.US)
        assertFailsWith<ResidencyViolation> { Residency.check(TenantPolicy("us", Region.US, false), Region.US) }
        assertFailsWith<ResidencyViolation> { Residency.check(TenantPolicy("us", Region.US, true), Region.EU) }
        assertFailsWith<ResidencyViolation> { Residency.check(TenantPolicy("eu", Region.EU, false), Region.US) }
    }
}
