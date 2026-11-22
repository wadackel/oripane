import java.util.regex.Pattern

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "dev.wadackel.oripane"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.wadackel.oripane"
        // XDH (X25519) in the platform's crypto providers starts at API 33.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        // The commit comes from tools/phone as a property rather than from git run here: the
        // configuration cache would capture the git call, and tools/phone alone decides when a
        // tree counts as dirty. Without it (tools/test, CI, debug builds) the name stays 0.1.0.
        versionName = providers.gradleProperty("oripane.commit").map { "0.1.0+$it" }.getOrElse("0.1.0")
    }

    lint {
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded onto the owner's own phone; there is no store identity to protect.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // tools/test passes one --tests pattern to both modules and fails on zero tests
            // itself; Gradle failing here would fail whichever module the pattern misses. A typo in
            // a direct `./gw … --tests` therefore passes silently: run `tools/test android --filter`.
            all {
                it.filter.isFailOnNoMatchingTests = false
                // The screenshot tests hold a native graphics sandbox and several full-screen
                // bitmaps at once; the worker's default 512 MB leaves little room, and
                // org.gradle.jvmargs only sizes the daemon.
                it.maxHeapSize = "2g"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

/** Puts the repository's LICENSE and the bundled fonts' licences in the APK, where About reads them. */
abstract class LicenseAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val names: ListProperty<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = output.get().dir("licenses").asFile.apply { deleteRecursively(); mkdirs() }
        sources.files.zip(names.get()).forEach { (file, name) -> file.copyTo(dir.resolve(name), overwrite = true) }
    }
}

val licenseAssets = tasks.register<LicenseAssets>("licenseAssets") {
    val root = rootProject.layout.projectDirectory
    sources.from(root.file("../LICENSE"), root.file("third_party/inter/OFL.txt"), root.file("third_party/intertight/OFL.txt"))
    names.set(listOf("Oripane-LICENSE.txt", "Inter-OFL.txt", "InterTight-OFL.txt"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(licenseAssets, LicenseAssets::output)
    }
}

aboutLibraries {
    // Offline because the plugin otherwise downloads each licence text from spdx.org during the
    // build and, when that request fails, ships the entry without its text and says nothing. The
    // texts live in aboutlibraries/licenses instead, and a unit test fails on any entry left empty.
    offlineMode = true
    collect {
        configPath = file("aboutlibraries")
    }
    export {
        excludeFields.addAll("description", "developers", "funding", "scm", "organization")
    }
    library {
        mergePlatformArtifacts = true
        // A BOM only pins versions; nothing of it is in the APK.
        exclusionPatterns.add(Pattern.compile(".*-bom"))
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// The baselines and the README's images live outside Roborazzi's output directory, so without this
// a run that changed only one of them would come back UP-TO-DATE or FROM-CACHE and pass without
// comparing. A file tree rather than inputs.dir, which fails validation while the directory does
// not exist, even when optional, so the first recording could not run.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/test/screenshots")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("roborazziBaselines")
    inputs.files(fileTree("../../docs/images")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("docsImages")
}
