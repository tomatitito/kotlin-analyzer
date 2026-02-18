plugins {
    kotlin("jvm") version "2.1.20"
    application
}

group = "dev.kouros"
version = "0.1.0-SNAPSHOT"

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

    // Kotlin stdlib (also used for test classpath)
    implementation(kotlin("stdlib"))

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("dev.kouros.spike.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.named<JavaExec>("run") {
    // Pass JDK home for classpath resolution
    systemProperty("jdk.home", System.getProperty("java.home"))
    // Enable assertions for debugging
    jvmArgs("-ea")
    // Needed for internal API access
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
}

tasks.register<JavaExec>("runWithMemoryLimit") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.kouros.spike.MainKt")
    systemProperty("jdk.home", System.getProperty("java.home"))
    jvmArgs("-ea", "-Xmx512m",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
}

tasks.register("printClasspathSize") {
    doLast {
        val jars = configurations.runtimeClasspath.get().files
        var totalSize = 0L
        jars.sortedBy { it.name }.forEach { jar ->
            totalSize += jar.length()
            println("  ${jar.name}: ${jar.length() / 1024}KB")
        }
        println("---")
        println("Total: ${jars.size} JARs, ${totalSize / 1024 / 1024}MB")
    }
}
