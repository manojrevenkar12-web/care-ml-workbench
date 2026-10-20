// The domain core: no web framework, no driver — only the JDK and kotlinx-serialization's JSON
// tree API. Everything here is unit-tested without containers.
plugins { `java-library` }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
