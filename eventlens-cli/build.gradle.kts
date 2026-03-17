dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-pg"))
    implementation(project(":eventlens-kafka"))
    implementation(project(":eventlens-api"))
    implementation("io.javalin:javalin:6.3.0")
    implementation("info.picocli:picocli:4.7.7")
    implementation("ch.qos.logback:logback-classic:1.5.32")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
}
