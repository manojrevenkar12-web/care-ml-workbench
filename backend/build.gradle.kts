import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions { allWarningsAsErrors.set(false) }
    }

    dependencies {
        "testImplementation"(kotlin("test"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
