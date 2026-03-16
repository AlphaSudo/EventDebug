dependencies {
    implementation(project(":eventlens-core"))
    implementation("org.apache.kafka:kafka-clients:4.2.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.testcontainers:kafka:1.20.1")
}
