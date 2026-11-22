plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.org.json)
}

tasks.test {
    // The golden vectors are produced by the Mac host (`oripane-host vectors`).
    systemProperty("vectors", rootProject.file("../spec/vectors.json").absolutePath)
    // The property carries only the path; without the file as an input, a change to the vectors
    // alone would leave the task UP-TO-DATE or restored from the build cache, unchecked.
    inputs.file(rootProject.file("../spec/vectors.json")).withPropertyName("vectors").withPathSensitivity(PathSensitivity.NONE)
    // tools/test passes one --tests pattern to both modules and fails on zero tests itself; Gradle
    // failing here would fail whichever module the pattern misses. A typo in a direct
    // `./gw … --tests` therefore passes silently: run `tools/test android --filter` instead.
    filter.isFailOnNoMatchingTests = false
}
