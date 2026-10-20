package workbench.authz

/**
 * Relationship-based access control, modelled exactly like the OpenFGA model in `fga/model.fga`.
 *
 * Production calls OpenFGA (the FGA proxy) through [OpenFgaAuthorizer] in the API module; tests
 * and local development use [LocalAuthorizer], which evaluates the same rewrite rules over a
 * tuple list. A contract test runs the same cases against both.
 *
 *   tenant:   member, admin
 *   project:  tenant (parent), admin, reviewer, annotator, viewer, phi_viewer
 *             admin      = [user] or admin from tenant
 *             reviewer   = [user] or admin
 *             annotator  = [user] or reviewer
 *             viewer     = [user] or annotator or member from tenant
 *             phi_viewer = [user] and member from tenant  (explicit break-glass grant only)
 *   dataset:  project (parent), can_read = viewer from project, can_train = reviewer from project
 */
data class Tuple(val user: String, val relation: String, val obj: String)

interface Authorizer {
    fun check(user: String, relation: String, obj: String): Boolean
}

class Forbidden(message: String) : RuntimeException(message)

fun Authorizer.require(user: String, relation: String, obj: String) {
    if (!check(user, relation, obj)) throw Forbidden("$user is not $relation of $obj")
}

sealed interface Rule {
    /** Directly assigned: a tuple (user, relation, object) exists. */
    data object Direct : Rule

    /** Another relation on the same object. */
    data class Computed(val relation: String) : Rule

    /** A relation on the object referenced by `tupleset` (e.g. the project's tenant). */
    data class FromParent(val tupleset: String, val relation: String) : Rule

    data class Union(val rules: List<Rule>) : Rule

    data class Intersection(val rules: List<Rule>) : Rule
}

object Model {
    val types: Map<String, Map<String, Rule>> = mapOf(
        "tenant" to mapOf("member" to Rule.Union(listOf(Rule.Direct, Rule.Computed("admin"))), "admin" to Rule.Direct),
        "project" to mapOf(
            "tenant" to Rule.Direct,
            "admin" to Rule.Union(listOf(Rule.Direct, Rule.FromParent("tenant", "admin"))),
            "reviewer" to Rule.Union(listOf(Rule.Direct, Rule.Computed("admin"))),
            "annotator" to Rule.Union(listOf(Rule.Direct, Rule.Computed("reviewer"))),
            "viewer" to Rule.Union(listOf(Rule.Direct, Rule.Computed("annotator"), Rule.FromParent("tenant", "member"))),
            "phi_viewer" to Rule.Intersection(listOf(Rule.Direct, Rule.FromParent("tenant", "member"))),
        ),
        "dataset" to mapOf(
            "project" to Rule.Direct,
            "can_read" to Rule.FromParent("project", "viewer"),
            "can_train" to Rule.FromParent("project", "reviewer"),
        ),
    )
}

class LocalAuthorizer(tuples: Collection<Tuple> = emptyList()) : Authorizer {
    private val tuples = java.util.concurrent.ConcurrentHashMap.newKeySet<Tuple>().apply { addAll(tuples) }

    fun write(t: Tuple) {
        tuples += t
    }

    fun delete(t: Tuple) {
        tuples -= t
    }

    fun all(): Set<Tuple> = tuples.toSet()

    override fun check(user: String, relation: String, obj: String): Boolean = eval(user, relation, obj, 0)

    private fun eval(user: String, relation: String, obj: String, depth: Int): Boolean {
        if (depth > 16) return false
        val type = obj.substringBefore(':')
        val rule = Model.types[type]?.get(relation) ?: return false
        return evalRule(rule, user, relation, obj, depth)
    }

    private fun evalRule(rule: Rule, user: String, relation: String, obj: String, depth: Int): Boolean = when (rule) {
        Rule.Direct -> Tuple(user, relation, obj) in tuples
        is Rule.Computed -> eval(user, rule.relation, obj, depth + 1)
        is Rule.FromParent -> tuples.filter { it.obj == obj && it.relation == rule.tupleset }.any { eval(user, rule.relation, it.user, depth + 1) }
        is Rule.Union -> rule.rules.any { evalRule(it, user, relation, obj, depth) }
        is Rule.Intersection -> rule.rules.all { evalRule(it, user, relation, obj, depth) }
    }
}
