package workbench.phi

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope encryption for original records (the only place raw PHI is kept after ingest).
 *
 * Every record gets a fresh 256-bit data key; the record is sealed with AES-GCM and the data key
 * is wrapped by the tenant's key-encryption key. In production the KEK lives in AWS KMS (one key
 * per tenant and region) and wrapping is a KMS call, so a database dump alone reveals nothing.
 * The record id and tenant are bound as associated data: a ciphertext copied onto another record
 * fails to decrypt.
 */
class PhiVault(private val keys: KeyProvider, private val random: SecureRandom = SecureRandom()) {
    fun seal(tenant: String, recordId: String, plaintext: ByteArray): SealedRecord {
        val dek = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(tenant, recordId))
        val ct = cipher.doFinal(plaintext)
        val wrapped = keys.wrap(tenant, dek.encoded)
        return SealedRecord(tenant, recordId, keys.keyId(tenant), b64(wrapped), b64(iv), b64(ct))
    }

    fun open(sealed: SealedRecord): ByteArray {
        val dek: SecretKey = SecretKeySpec(keys.unwrap(sealed.tenant, sealed.keyId, unb64(sealed.wrappedKey)), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(128, unb64(sealed.iv)))
        cipher.updateAAD(aad(sealed.tenant, sealed.recordId))
        return cipher.doFinal(unb64(sealed.ciphertext))
    }

    private fun aad(tenant: String, recordId: String) = "$tenant/$recordId".toByteArray()

    companion object {
        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}

data class SealedRecord(val tenant: String, val recordId: String, val keyId: String, val wrappedKey: String, val iv: String, val ciphertext: String)

/** Key-encryption keys per tenant. Production: AWS KMS (Encrypt/Decrypt with an encryption context). */
interface KeyProvider {
    fun keyId(tenant: String): String

    fun wrap(tenant: String, dataKey: ByteArray): ByteArray

    fun unwrap(tenant: String, keyId: String, wrapped: ByteArray): ByteArray
}

/**
 * Local key provider for development and tests: per-tenant KEKs derived from one master secret
 * (HKDF-like HMAC derivation), wrapping with AES-GCM. Never used with real PHI.
 */
class LocalKeyProvider(private val master: ByteArray, private val random: SecureRandom = SecureRandom()) : KeyProvider {
    init {
        require(master.size >= 32) { "master key must be at least 256 bits" }
    }

    override fun keyId(tenant: String) = "local:$tenant:v1"

    private fun kek(tenant: String): SecretKey {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        return SecretKeySpec(mac.doFinal("kek:$tenant:v1".toByteArray()), "AES")
    }

    override fun wrap(tenant: String, dataKey: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, kek(tenant), GCMParameterSpec(128, iv))
        c.updateAAD(tenant.toByteArray())
        return iv + c.doFinal(dataKey)
    }

    override fun unwrap(tenant: String, keyId: String, wrapped: ByteArray): ByteArray {
        require(keyId == keyId(tenant)) { "key $keyId does not belong to tenant $tenant" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, kek(tenant), GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        c.updateAAD(tenant.toByteArray())
        return c.doFinal(wrapped.copyOfRange(12, wrapped.size))
    }
}

/**
 * Where data may be processed. US customer data carries PHI under HIPAA and may only be
 * processed in the US deployment, and only for tenants with a signed Business Associate
 * Agreement; EU data stays in the EU (GDPR).
 */
enum class Region { EU, US }

data class TenantPolicy(val tenant: String, val region: Region, val baaSigned: Boolean)

class ResidencyViolation(message: String) : RuntimeException(message)

object Residency {
    fun check(policy: TenantPolicy, processingRegion: Region) {
        if (policy.region != processingRegion) {
            throw ResidencyViolation("tenant ${policy.tenant} data is ${policy.region}-resident; this deployment processes $processingRegion data")
        }
        if (policy.region == Region.US && !policy.baaSigned) {
            throw ResidencyViolation("tenant ${policy.tenant} has no Business Associate Agreement; US PHI may not be processed")
        }
    }
}
