import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin like core: what the app stores is tested on the JVM against a real SQLite, in CI.
// The Android app plugs in its own SQLite driver; the schema and queries are the same.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

sqldelight {
    databases {
        register("KamayoqDatabase") {
            packageName.set("app.kamayoq.data.db")
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
        }
    }
}

// Runs against a real Kavita, so it stays out of :data:test, which never touches the network.
val kavitaTest by sourceSets.creating

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(project(":epub-fixtures"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)

    "kavitaTestImplementation"(sourceSets.main.get().output)
    "kavitaTestImplementation"(project(":core"))
    "kavitaTestImplementation"(libs.okhttp)
    "kavitaTestImplementation"(libs.sqldelight.sqlite.driver)
    "kavitaTestImplementation"(libs.kotlinx.serialization.json)
    "kavitaTestImplementation"(kotlin("test-junit5"))
    "kavitaTestImplementation"(platform(libs.junit.bom))
    "kavitaTestRuntimeOnly"(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("kavitaTest") {
    testClassesDirs = kavitaTest.output.classesDirs
    classpath = kavitaTest.runtimeClasspath
    useJUnitPlatform()
    // The answer lives in the server, which Gradle cannot see: never reuse a previous result.
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}

tasks.register<JavaExec>("kavitaFixtures") {
    classpath = kavitaTest.runtimeClasspath
    mainClass.set("app.kamayoq.data.kavita.KavitaFixturesKt")
    args(layout.buildDirectory.dir("kavita-fixtures").get().asFile)
}
