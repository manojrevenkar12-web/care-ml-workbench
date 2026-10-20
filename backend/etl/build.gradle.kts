plugins { application }

dependencies {
    implementation(project(":core"))
    runtimeOnly("org.postgresql:postgresql:42.7.5")
}

application { mainClass.set("workbench.etl.MainKt") }
