plugins {
    java
    id("com.gradleup.shadow") version "9.4.0"
}


dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-pg"))
    implementation(project(":eventlens-kafka"))
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

    // Ensure the fat JAR runs with preview features
    manifest {
        attributes(
            "Main-Class" to "io.eventlens.EventLensMain",
            "Implementation-Version" to project.version
        )
    }
}

// Make 'build' also produce the shadow jar
tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
