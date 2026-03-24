dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-spi"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.1")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.postgresql:postgresql:42.7.10")

    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
}
