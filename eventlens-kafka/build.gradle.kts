dependencies {
    implementation(project(":eventlens-core"))
    implementation("org.apache.kafka:kafka-clients:3.7.1")

    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.testcontainers:kafka:1.20.1")
}
