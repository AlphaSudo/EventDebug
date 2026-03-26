// React frontend is built by npm; this module just tracks the task
// The actual Vite build outputs to eventlens-api/src/main/resources/web
// Gradle wires this via the npmBuild task (see eventlens-app/build.gradle.kts)

plugins {
    base
}

val npmInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "Install NPM dependencies"
    workingDir = projectDir

    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")

    commandLine(
        if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm",
        "ci",
        "--no-audit",
        "--no-fund"
    )
}

val npmBuild by tasks.registering(Exec::class) {
    dependsOn(npmInstall)
    group = "build"
    description = "Build the React UI with Vite"
    workingDir = projectDir

    // Only re-run when frontend sources change
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("package.json")
    inputs.file("vite.config.ts")
    outputs.dir("${project(":eventlens-api").projectDir}/src/main/resources/web")

    commandLine(
        if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm",
        "run", "build"
    )
}

val npmTestUnit by tasks.registering(Exec::class) {
    dependsOn(npmInstall)
    group = "verification"
    description = "Run frontend unit tests"
    workingDir = projectDir

    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("package.json")
    inputs.file("package-lock.json")

    commandLine(
        if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm",
        "run", "test:unit"
    )
}

val npmTestE2e by tasks.registering(Exec::class) {
    dependsOn(npmInstall)
    group = "verification"
    description = "Run frontend Playwright E2E tests"
    workingDir = projectDir

    inputs.dir("tests/e2e").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("package.json")
    inputs.file("package-lock.json")
    inputs.file("playwright.config.ts")

    environment("VITE_EVENTLENS_DEMO", "true")
    environment("VITE_EVENTLENS_DEMO_ALLOW", "true")

    commandLine(
        if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm",
        "run", "test:e2e"
    )
}

tasks.named("build") {
    dependsOn(npmBuild)
}

tasks.named("check") {
    dependsOn(npmTestUnit)
}
