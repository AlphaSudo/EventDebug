dependencies {
    implementation(project(":eventlens-core"))
    implementation("org.apache.kafka:kafka-clients:4.2.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")

    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:kafka:1.21.4")
}
