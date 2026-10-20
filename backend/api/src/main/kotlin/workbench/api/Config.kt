package workbench.api

import workbench.phi.Region

/** Runtime configuration, from the environment only (secrets come from Kubernetes Secrets / Secrets Manager). */
data class Config(
    val env: String,
    val port: Int,
    val region: Region,
    val databaseUrl: String?,
    val databaseUser: String?,
    val databasePassword: String?,
    val oidcIssuer: String?,
    val oidcJwksUrl: String?,
    val oidcAudience: String?,
    val devAuth: Boolean,
    val devSecret: String,
    val openFgaUrl: String?,
    val openFgaStoreId: String?,
    val openFgaModelId: String?,
    val trainerMode: String, // none | local | kubernetes
    val trainerCommand: List<String>,
    val trainerImage: String?,
    val namespace: String,
    val artifactDir: String,
    val seedDemo: Boolean,
    val corsOrigins: List<String>,
) {
    val production get() = env == "production"

    /** Refuse to start with settings that are only acceptable on a laptop. */
    fun validate() {
        if (!production) return
        val problems = buildList {
            if (devAuth) add("DEV_AUTH must be off")
            if (oidcIssuer == null || oidcJwksUrl == null) add("OIDC_ISSUER and OIDC_JWKS_URL are required")
            if (databaseUrl == null) add("DATABASE_URL is required")
            if (openFgaUrl == null || openFgaStoreId == null) add("OPENFGA_URL and OPENFGA_STORE_ID are required")
            if (System.getenv("WORKBENCH_MASTER_KEY") == null) add("WORKBENCH_MASTER_KEY is required")
            if (seedDemo) add("SEED_DEMO must be off")
        }
        require(problems.isEmpty()) { "unsafe production configuration: ${problems.joinToString("; ")}" }
    }

    companion object {
        fun fromEnv(e: Map<String, String> = System.getenv()) = Config(
            env = e["WORKBENCH_ENV"] ?: "development",
            port = e["PORT"]?.toInt() ?: 8080,
            region = Region.valueOf(e["WORKBENCH_REGION"] ?: "EU"),
            databaseUrl = e["DATABASE_URL"],
            databaseUser = e["DATABASE_USER"],
            databasePassword = e["DATABASE_PASSWORD"],
            oidcIssuer = e["OIDC_ISSUER"],
            oidcJwksUrl = e["OIDC_JWKS_URL"],
            oidcAudience = e["OIDC_AUDIENCE"],
            devAuth = e["DEV_AUTH"] == "true",
            devSecret = e["DEV_AUTH_SECRET"] ?: "dev-only-secret-change-me",
            openFgaUrl = e["OPENFGA_URL"],
            openFgaStoreId = e["OPENFGA_STORE_ID"],
            openFgaModelId = e["OPENFGA_MODEL_ID"],
            trainerMode = e["TRAINER_MODE"] ?: "none",
            trainerCommand = (e["TRAINER_COMMAND"] ?: "python3 trainer/train.py").split(" "),
            trainerImage = e["TRAINER_IMAGE"],
            namespace = e["K8S_NAMESPACE"] ?: "ml-app",
            artifactDir = e["ARTIFACT_DIR"] ?: "build/artifacts",
            seedDemo = e["SEED_DEMO"] == "true",
            corsOrigins = (e["CORS_ORIGINS"] ?: "http://localhost:5173").split(",").filter { it.isNotBlank() },
        )
    }
}
