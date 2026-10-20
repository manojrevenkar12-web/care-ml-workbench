plugins {
    application
    kotlin("plugin.serialization")
}

val ktor = "3.1.3"
val testcontainers = "1.21.4"

dependencies {
    implementation(project(":core"))
    implementation(project(":etl"))
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-auth-jwt:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-server-call-id:$ktor")
    implementation("io.ktor:ktor-server-cors:$ktor")
    implementation("io.ktor:ktor-server-default-headers:$ktor")
    implementation("io.ktor:ktor-server-metrics-micrometer:$ktor")
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.7")
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("com.auth0:jwks-rsa:0.22.1")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("org.testcontainers:postgresql:$testcontainers")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainers")
}

application { mainClass.set("workbench.api.MainKt") }
