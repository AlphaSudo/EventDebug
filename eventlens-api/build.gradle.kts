dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-pg"))
    implementation(project(":eventlens-kafka"))
    implementation("io.javalin:javalin:7.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.1")
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("io.micrometer:micrometer-core:1.15.0")
    implementation("io.micrometer:micrometer-registry-prometheus:1.15.0")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.21.1")
}
