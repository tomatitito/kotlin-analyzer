plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework:spring-web:6.2.3")
    implementation("org.springframework:spring-webflux:6.2.3")
    implementation("org.springframework.data:spring-data-mongodb:4.4.3")
    implementation("io.arrow-kt:arrow-core:2.1.0")
    implementation("io.projectreactor:reactor-core:3.7.3")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xcontext-parameters", "-Xmulti-dollar-interpolation")
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
