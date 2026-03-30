package dev.kouros.sidecar

/**
 * Entry point for the kotlin-analyzer JVM sidecar.
 *
 * Reads JSON-RPC requests from stdin, processes them using the Kotlin Analysis API,
 * and writes JSON-RPC responses to stdout. All logging goes to stderr.
 */
fun main(args: Array<String>) {
    System.err.println("kotlin-analyzer sidecar v0.4.2 starting (Kotlin ${SidecarRuntime.kotlinVersion})")

    val transport = JsonRpcTransport(
        input = System.`in`,
        output = System.out,
    )

    val server = AnalysisServer(transport)
    server.run()

    System.err.println("kotlin-analyzer sidecar exiting")
}
