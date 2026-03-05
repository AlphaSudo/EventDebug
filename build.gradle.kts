plugins {
    java
}

// Shared versions
val javalinVersion      = "6.3.0"
val jacksonVersion      = "2.17.2"
val hikariVersion       = "5.1.0"
val kafkaVersion        = "3.7.1"
val picocliVersion      = "4.7.6"
val slf4jVersion        = "2.0.13"
val logbackVersion      = "1.5.6"
val junitVersion        = "5.11.0"
val assertjVersion      = "3.26.3"
val testcontainersVersion = "1.20.1"
val caffeineVersion     = "3.1.8"

allprojects {
    group   = "io.eventlens"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    // Enable virtual threads and preview features
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("--enable-preview", "-Xlint:preview"))
        options.release.set(21)
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
    }
    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--enable-preview")
    }

    dependencies {
        // Logging — every module gets SLF4J API
        "implementation"("org.slf4j:slf4j-api:$slf4jVersion")

        // Test
        "testImplementation"("org.junit.jupiter:junit-jupiter:$junitVersion")
        "testImplementation"("org.assertj:assertj-core:$assertjVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testRuntimeOnly"("ch.qos.logback:logback-classic:$logbackVersion")
    }
}
