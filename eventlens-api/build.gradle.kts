dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-spi"))
    implementation("io.javalin:javalin:7.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("com.nimbusds:nimbus-jose-jwt:10.8")
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("io.micrometer:micrometer-core:1.16.4")
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.4")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    testImplementation(project(":eventlens-source-postgres"))
    testImplementation(project(":eventlens-source-mysql"))
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:mysql:1.21.4")
}

tasks.named("processResources") {
    dependsOn(":eventlens-ui:npmBuild")
}
