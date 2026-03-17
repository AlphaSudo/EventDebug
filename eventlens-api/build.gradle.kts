dependencies {
    implementation(project(":eventlens-core"))
    implementation(project(":eventlens-pg"))
    implementation(project(":eventlens-kafka"))
    implementation("io.javalin:javalin:6.3.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.1")
    implementation("ch.qos.logback:logback-classic:1.5.32")
}
