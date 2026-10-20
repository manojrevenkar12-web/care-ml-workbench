rootProject.name = "care-ml-workbench"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("core", "etl", "api")
