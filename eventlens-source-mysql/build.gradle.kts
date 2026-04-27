dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-spi"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.mysql:mysql-connector-j:9.7.0")

    testImplementation(project(":eventlens-plugin-test"))
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:mysql:1.21.4")
}

