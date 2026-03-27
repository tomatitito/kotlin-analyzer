package dev.kouros.sidecar

import org.jetbrains.kotlin.config.KotlinCompilerVersion

/**
 * Runtime metadata for the bundled sidecar compiler payload.
 */
object SidecarRuntime {
    val kotlinVersion: String = KotlinCompilerVersion.VERSION
}
