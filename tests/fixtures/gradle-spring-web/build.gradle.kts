plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework:spring-web:6.2.3")
    implementation("org.springframework:spring-webmvc:6.2.3")
    implementation("io.arrow-kt:arrow-core:2.1.0")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xcontext-parameters")
    }
}

tasks.register("printProjectConfig") {
    doLast {
        println("---CLASSPATH-START---")
        configurations.getByName("compileClasspath").resolve().forEach {
            println(it.absolutePath)
        }
        println("---CLASSPATH-END---")

        println("---FLAGS-START---")
        val flagsFound = mutableSetOf<String>()
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().forEach { task ->
            task.compilerOptions.freeCompilerArgs.get().forEach { flag ->
                flagsFound.add(flag)
            }
        }
        flagsFound.forEach { println(it) }
        println("---FLAGS-END---")
    }
}
