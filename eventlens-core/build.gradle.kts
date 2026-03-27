dependencies {
    implementation(project(":eventlens-spi"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("de.mkammerer:argon2-jvm:2.12")
}
