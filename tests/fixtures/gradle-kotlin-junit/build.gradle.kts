plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("printProjectConfig") {
    doLast {
        println("---CLASSPATH-START---")
        linkedSetOf(
            *configurations.getByName("compileClasspath").resolve().toTypedArray(),
            *configurations.getByName("testCompileClasspath").resolve().toTypedArray(),
        ).forEach {
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
