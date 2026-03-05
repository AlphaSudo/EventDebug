// React frontend is built by npm; this module just tracks the task
// The actual Vite build outputs to eventlens-api/src/main/resources/web
// Gradle wires this via the npmBuild task (see eventlens-app/build.gradle.kts)

plugins {
    base
}

val npmBuild by tasks.registering(Exec::class) {
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

tasks.named("build") {
    dependsOn(npmBuild)
}
