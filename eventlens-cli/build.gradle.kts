dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-pg"))
    implementation(project(":eventlens-kafka"))
    implementation(project(":eventlens-api"))
    implementation("info.picocli:picocli:4.7.6")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
}
