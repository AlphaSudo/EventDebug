plugins {
    java
    id("org.owasp.dependencycheck") version "12.2.0"
}

// Shared versions
val javalinVersion      = "7.1.0"
val jacksonVersion      = "2.17.2"
val hikariVersion       = "5.1.0"
val kafkaVersion        = "3.7.1"
val picocliVersion      = "4.7.6"
val slf4jVersion        = "2.0.17"
val logbackVersion      = "1.5.32"
val junitVersion        = "6.0.3"
val assertjVersion      = "3.27.7"
val testcontainersVersion = "1.20.1"
val caffeineVersion     = "3.1.8"
val micrometerVersion   = "1.15.0"
val logstashEncoderVersion = "9.0"
val janinoVersion       = "3.1.12"

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
        // Logging â€” every module gets SLF4J API
        "implementation"("org.slf4j:slf4j-api:$slf4jVersion")
        // Structured JSON logging support (logback conditional config + encoder)
        "runtimeOnly"("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")
        "runtimeOnly"("org.codehaus.janino:janino:$janinoVersion")

        // Test
        "testImplementation"("org.junit.jupiter:junit-jupiter:$junitVersion")
        "testImplementation"("org.assertj:assertj-core:$assertjVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testRuntimeOnly"("ch.qos.logback:logback-classic:$logbackVersion")
    }
}

tasks.register("dependencyScan") {
    group = "verification"
    description = "Run OWASP Dependency Check across all modules"
    dependsOn("dependencyCheckAggregate")
}

dependencyCheck {
    // Do not fail local builds if the NVD feed cannot be updated (e.g. no API key).
    failOnError = false

    // Suppress known false positives
    suppressionFile = "dependency-suppression.xml"

    // Use NVD API key from environment variable, or fall back to gradle.properties.
    // To persist the key: add  dependencyCheck.nvd.apiKey=<your-key>  to ~/.gradle/gradle.properties
    val nvdKey = System.getenv("DEPENDENCY_CHECK_NVD_API_KEY")
        ?: (project.findProperty("dependencyCheck.nvd.apiKey") as? String)

    if (!nvdKey.isNullOrBlank()) {
        nvd.apiKey = nvdKey
        // NVD rate-limits to 50 req/30s with an API key.
        // 10s between calls is conservative but reliable for the initial full download.
        nvd.delay = 10000
    } else {
        // No key – skip the live NVD update entirely and use cached data only.
        autoUpdate = false
    }
}
