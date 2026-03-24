plugins {
    `java-library`
}

dependencies {
    api(project(":eventlens-spi"))
    api("org.junit.jupiter:junit-jupiter-api:6.0.3")
    api("org.assertj:assertj-core:3.27.7")
}
