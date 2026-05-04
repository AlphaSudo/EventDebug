plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.3")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("eventlens-spi")
                description.set("Stable plugin contract for EventLens v3")
            }
        }
    }
}
