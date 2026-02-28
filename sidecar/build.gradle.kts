plugins {
    kotlin("jvm") version "2.1.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "dev.kouros"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide-plugin-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

val kotlinVersion = "2.1.20"

/**
 * Helper to add a "-for-ide" artifact with transitive deps disabled.
 * These JARs are self-contained (fat JARs merging internal modules),
 * but their POMs reference unpublished internal artifacts as compile deps.
 * We must disable transitivity to avoid resolution failures.
 */
fun DependencyHandlerScope.analysisApi(artifactId: String) {
    implementation("org.jetbrains.kotlin:$artifactId:$kotlinVersion") {
        isTransitive = false
    }
}

dependencies {
    // Kotlin compiler - provides IntelliJ Platform PSI/VFS infrastructure + compiler core
    implementation("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion")

    // Analysis API "-for-ide" artifacts from JetBrains Space repo
    analysisApi("analysis-api-for-ide")
    analysisApi("analysis-api-standalone-for-ide")
    analysisApi("analysis-api-k2-for-ide")
    analysisApi("analysis-api-impl-base-for-ide")
    analysisApi("analysis-api-platform-interface-for-ide")
    analysisApi("low-level-api-fir-for-ide")
    analysisApi("symbol-light-classes-for-ide")

    // Runtime dependencies used by Analysis API internally
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // JSON-RPC communication
    implementation("com.google.code.gson:gson:2.11.0")

    // Kotlin stdlib (also used for test classpath)
    implementation(kotlin("stdlib"))

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("net.jqwik:jqwik:1.9.2")
    testImplementation("net.jqwik:jqwik-kotlin:1.9.2")
}

application {
    mainClass.set("dev.kouros.sidecar.MainKt")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi",
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaNonPublicApi",
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaIdeApi",
        )
    }
}

tasks.test {
    useJUnitPlatform()
    // Analysis API requires reflective access to java.base internals
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
    // Integration tests need enough heap for the Analysis API session
    maxHeapSize = "1g"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("sidecar")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
}
