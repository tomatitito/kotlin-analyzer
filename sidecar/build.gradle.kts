import groovy.json.JsonOutput
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm") version "2.2.0-RC2"
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

val kotlinVersion = "2.2.0-RC2"
val supportedRuntimeKotlinVersions = listOf(
    kotlinVersion,
    "2.2.21",
)
val analysisApiArtifacts = listOf(
    "analysis-api-for-ide",
    "analysis-api-standalone-for-ide",
    "analysis-api-k2-for-ide",
    "analysis-api-impl-base-for-ide",
    "analysis-api-platform-interface-for-ide",
    "low-level-api-fir-for-ide",
    "symbol-light-classes-for-ide",
)
val runtimeDependencies = listOf(
    "com.github.ben-manes.caffeine:caffeine:3.1.8",
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3",
    "com.google.code.gson:gson:2.11.0",
    "org.slf4j:slf4j-simple:2.0.16",
)

fun versionedRuntimeDependencies(kotlinVersion: String) = buildList {
    add(dependencies.create("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion"))
    analysisApiArtifacts.forEach { artifactId ->
        add(
            dependencies.create("org.jetbrains.kotlin:$artifactId:$kotlinVersion").also { dependency ->
                (dependency as ExternalModuleDependency).isTransitive = false
            }
        )
    }
    add(dependencies.create("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion"))
    runtimeDependencies.forEach { dependencyNotation ->
        add(dependencies.create(dependencyNotation))
    }
}

fun String.runtimeTaskSuffix(): String = replace(Regex("[^A-Za-z0-9]"), "_")

fun kotlinVersionLine(version: String): String =
    version.substringBefore('-').split('.').take(2).joinToString(".")

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
    analysisApiArtifacts.forEach { artifactId ->
        analysisApi(artifactId)
    }

    // Runtime dependencies used by Analysis API internally
    implementation(runtimeDependencies[0])
    implementation(runtimeDependencies[1])

    // JSON-RPC communication
    implementation(runtimeDependencies[2])

    // Kotlin stdlib (also used for test classpath)
    implementation(kotlin("stdlib"))

    // Logging
    implementation(runtimeDependencies[3])

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

val compileLauncherJava by tasks.registering(JavaCompile::class) {
    source = fileTree("src/launcher/java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/launcher/main"))
    options.release.set(17)
}

val launcherJar by tasks.registering(Jar::class) {
    dependsOn(compileLauncherJava)
    archiveBaseName.set("sidecar-launcher")
    archiveVersion.set("")
    from(compileLauncherJava.map { it.destinationDirectory })
    manifest {
        attributes["Main-Class"] = "dev.kouros.sidecar.launcher.LauncherMain"
    }
}

val assembleRuntimePayloads by tasks.registering {
    group = "build"
    description = "Assemble launcher + versioned sidecar payload layouts under build/runtime."
}

supportedRuntimeKotlinVersions.forEach { runtimeKotlinVersion ->
    val taskName = "assembleRuntimePayload${runtimeKotlinVersion.runtimeTaskSuffix()}"
    val runtimeDir = layout.buildDirectory.dir("runtime/$runtimeKotlinVersion")

    val runtimeTask = tasks.register(taskName) {
        group = "build"
        description = "Assemble the sidecar runtime payload for Kotlin $runtimeKotlinVersion."
        dependsOn(tasks.named("jar"), launcherJar)
        outputs.dir(runtimeDir)

        doLast {
            val rootDir = runtimeDir.get().asFile
            val launcherDir = rootDir.resolve("launcher")
            val payloadDir = rootDir.resolve("payload")

            delete(rootDir)
            launcherDir.mkdirs()
            payloadDir.mkdirs()

            copy {
                from(launcherJar.get().archiveFile)
                into(launcherDir)
            }

            copy {
                from(tasks.named<Jar>("jar").get().archiveFile)
                into(payloadDir)
                rename { "sidecar-impl.jar" }
            }

            val runtimeClasspathFiles = if (runtimeKotlinVersion == kotlinVersion) {
                configurations.runtimeClasspath.get().resolve()
            } else {
                configurations
                    .detachedConfiguration(*versionedRuntimeDependencies(runtimeKotlinVersion).toTypedArray())
                    .resolve()
            }.sortedBy { it.name }

            copy {
                from(runtimeClasspathFiles)
                into(payloadDir)
            }

            val manifest = mapOf(
                "kotlinVersion" to runtimeKotlinVersion,
                "mainClass" to "dev.kouros.sidecar.launcher.LauncherMain",
                "analyzerVersion" to project.version.toString(),
                "targetPlatform" to "any",
                "validatedSameMinor" to listOf(kotlinVersionLine(runtimeKotlinVersion)),
                "classpath" to buildList {
                    add("launcher/${launcherJar.get().archiveFileName.get()}")
                    add("payload/sidecar-impl.jar")
                    runtimeClasspathFiles.forEach { file ->
                        add("payload/${file.name}")
                    }
                },
            )
            rootDir.resolve("manifest.json").writeText(
                JsonOutput.prettyPrint(JsonOutput.toJson(manifest))
            )
        }
    }

    assembleRuntimePayloads.configure {
        dependsOn(runtimeTask)
    }
}

tasks.named("assemble") {
    dependsOn(assembleRuntimePayloads)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
}
