package workbench.audit

import java.security.MessageDigest
import java.time.Instant

/**
 * Append-only, hash-chained audit trail for everything that touches PHI or changes who can see it:
 * ingest, PHI reveal, permission grants, dataset exports, training on a dataset.
 *
 * Each event's hash covers its content and the previous hash, so editing or deleting any event
 * breaks every later link; `verify` reports the first broken one. In production the chain head is
 * also anchored periodically to write-once storage (S3 Object Lock), so the whole table cannot be
 * rewritten consistently either.
 */
data class AuditEvent(
    val seq: Long,
    val at: Instant,
    val actor: String,
    val action: String,
    val tenant: String,
    val resource: String,
    val detail: Map<String, String>,
    val prevHash: String,
    val hash: String,
)

interface AuditStore {
    fun last(): AuditEvent?

    fun append(event: AuditEvent)

    fun all(): List<AuditEvent>

    fun query(tenant: String?, action: String?, limit: Int): List<AuditEvent>
}

class AuditLog(private val store: AuditStore, private val clock: () -> Instant = Instant::now) {
    private val lock = Any()

    /**
     * Append an event. With several API replicas two appends can race for the same sequence
     * number; the store rejects the second ([AuditConflict], a primary-key violation in
     * PostgreSQL) and it is retried on top of the new head, so the chain never forks.
     */
    fun record(actor: String, action: String, tenant: String, resource: String, detail: Map<String, String> = emptyMap()): AuditEvent = synchronized(lock) {
        repeat(10) {
            val prev = store.last()
            val seq = (prev?.seq ?: 0) + 1
            val at = clock()
            val prevHash = prev?.hash ?: GENESIS
            val e = AuditEvent(seq, at, actor, action, tenant, resource, detail.toSortedMap(), prevHash, hash(seq, at, actor, action, tenant, resource, detail, prevHash))
            try {
                store.append(e)
                return e
            } catch (_: AuditConflict) {
                Thread.sleep(5L * (it + 1))
            }
        }
        throw IllegalStateException("could not append to the audit chain after 10 attempts")
    }

    fun verify(): Verification = verify(store.all())

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

        fun hash(seq: Long, at: Instant, actor: String, action: String, tenant: String, resource: String, detail: Map<String, String>, prev: String): String {
            val canonical = buildString {
                append(seq).append('|').append(at.toEpochMilli()).append('|').append(actor).append('|').append(action).append('|')
                append(tenant).append('|').append(resource).append('|')
                detail.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v.replace("|", "\\|")).append(';') }
                append('|').append(prev)
            }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        fun verify(events: List<AuditEvent>): Verification {
            var prev = GENESIS
            for ((i, e) in events.sortedBy { it.seq }.withIndex()) {
                if (e.seq != i + 1L) return Verification(false, events.size, e.seq, "sequence gap before ${e.seq}")
                if (e.prevHash != prev) return Verification(false, events.size, e.seq, "previous-hash link broken")
                if (hash(e.seq, e.at, e.actor, e.action, e.tenant, e.resource, e.detail, e.prevHash) != e.hash) {
                    return Verification(false, events.size, e.seq, "event content does not match its hash")
                }
                prev = e.hash
            }
            return Verification(true, events.size, null, null)
        }
    }
}

class AuditConflict : RuntimeException("audit sequence already taken")

data class Verification(val intact: Boolean, val events: Int, val firstBrokenSeq: Long?, val reason: String?)

/** Actions that must always be audited. */
object Actions {
    const val INGEST = "phi.ingest"
    const val REVEAL = "phi.reveal"
    const val GRANT = "authz.grant"
    const val REVOKE = "authz.revoke"
    const val SNAPSHOT = "dataset.snapshot"
    const val EXPORT = "dataset.export"
    const val TRAIN = "training.start"
    const val QUARANTINE = "ingest.quarantine"
}
