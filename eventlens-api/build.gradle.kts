dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-spi"))
    implementation("io.javalin:javalin:7.2.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    implementation("com.nimbusds:nimbus-jose-jwt:10.9")
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("io.micrometer:micrometer-core:1.16.5")
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.5")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    testImplementation(project(":eventlens-source-postgres"))
    testImplementation(project(":eventlens-source-mysql"))
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:mysql:1.21.4")
}

tasks.named("processResources") {
    dependsOn(":eventlens-ui:npmBuild")
}
