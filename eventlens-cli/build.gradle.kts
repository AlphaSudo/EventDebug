dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-spi"))
    implementation(project(":eventlens-source-postgres"))
    implementation(project(":eventlens-stream-kafka"))
    implementation(project(":eventlens-api"))
    implementation("io.javalin:javalin:7.1.0")
    implementation("info.picocli:picocli:4.7.7")
    implementation("ch.qos.logback:logback-classic:1.5.32")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
}
