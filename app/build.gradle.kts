import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Versioning policy (docs/decisions/versioning-2026-10-25.md): releases are MAJOR.MINOR.PATCH
// with a versionCode raised by hand; test builds are numbered by the commit count, which only
// grows because published history is never rewritten.
val nextRelease = "0.1.0"
val releaseVersionCode = 1
val commitCount: Int = providers.gradleProperty("kamayoqEarlierCommits").get().toInt() + providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()
val shallowClone: Boolean = providers.exec {
    commandLine("git", "rev-parse", "--is-shallow-repository")
}.standardOutput.asText.get().trim() == "true"
if (shallowClone) {
    throw GradleException("Shallow clone: the commit count would be wrong. Fetch the full history.")
}

// The debug key is fixed so each published test build installs over the previous one and keeps
// its data. It lives only in the repository secrets, and only the publish job of ci.yml decodes it.
// Verification builds run without it and are never published.
val debugKeystorePath: String? = System.getenv("KAMAYOQ_DEBUG_KEYSTORE_PATH")
val debugStorePassword: String? = System.getenv("KAMAYOQ_DEBUG_STORE_PASSWORD")
val publishing = System.getenv("KAMAYOQ_PUBLISH") == "true"
if (publishing && (debugKeystorePath.isNullOrEmpty() || debugStorePassword.isNullOrEmpty())) {
    throw GradleException("Publishing without the debug signing secrets; refusing to sign with a throwaway key.")
}

android {
    namespace = "app.kamayoq.android"
    // Current Compose libraries require compiling against API 37.1 or later
    // (same setting as Mihon).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.kamayoq.android"
        // Android 12, decided on 2026-10-24.
        minSdk = 31
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = nextRelease
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!debugKeystorePath.isNullOrEmpty()) {
            create("fixedDebug") {
                storeFile = file(debugKeystorePath)
                storePassword = debugStorePassword
                keyAlias = "debug"
                keyPassword = debugStorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Test build installs next to the future release build, as a separate app.
            applicationIdSuffix = ".debug"
            if (!debugKeystorePath.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric reads the app's manifest and resources (developer.android.com, Robolectric).
            isIncludeAndroidResources = true
            // Robolectric reaches FileDescriptor internals through jdk.internal.access, which Java 21
            // closes; Robolectric 4.17 opens it for its own tests (build-logic TestTaskConfiguration.kt).
            all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

// The copy of foliate-js reaches the APK under reader/, beside the page that uses it, and its
// record stays out (third_party/foliate-js/UPSTREAM.md).
abstract class CopyReaderLibrary : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val library: DirectoryProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(library) { exclude("UPSTREAM.md") }
            into(assets.dir("reader/foliate-js"))
        }
    }
}

val copyReaderLibrary = tasks.register<CopyReaderLibrary>("copyReaderLibrary") {
    library.set(rootProject.layout.projectDirectory.dir("third_party/foliate-js"))
    assets.set(layout.buildDirectory.dir("generated/reader-library"))
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(libs.sqldelight.android.driver)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.androidx.webkit)

    // The app's lifecycle on the JVM, without an emulator (docs/research/android-tests-for-recreation.md).
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Screens tested on the JVM too (decided on 29/10/2026). The manifest piece gives the tests
    // an empty screen to draw on; it goes in debug builds only, as Android's guide says.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    // The real WebView, on an emulator (.github/workflows/emulator.yml).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(project(":epub-fixtures"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// Test builds: versionCode is the commit count and the name says which release they lead to,
// e.g. 0.1.0-r27. The test build has its own application id, so its numbers never mix with
// the release ones.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyReaderLibrary, CopyReaderLibrary::assets)
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(commitCount)
            output.versionName.set("$nextRelease-r$commitCount")
        }
    }
}
