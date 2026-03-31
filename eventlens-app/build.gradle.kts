plugins {
    java
    id("com.gradleup.shadow") version "9.4.0"
}


dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-source-postgres"))
    implementation(project(":eventlens-source-mysql"))
    implementation(project(":eventlens-stream-kafka"))
    implementation(project(":eventlens-api"))
    implementation(project(":eventlens-cli"))
    implementation("ch.qos.logback:logback-classic:1.5.32")
}

// Build the React UI before processing resources
tasks.named("processResources") {
    dependsOn(":eventlens-ui:npmBuild")
}

tasks.shadowJar {
    archiveBaseName.set("eventlens")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    // Ensure all JDBC driver entries survive the service-file merge (SQLite was dropped)
    append("META-INF/services/java.sql.Driver")

    manifest {
        attributes(
            "Main-Class" to "io.eventlens.EventLensMain",
            "Implementation-Version" to project.version
        )
    }
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
