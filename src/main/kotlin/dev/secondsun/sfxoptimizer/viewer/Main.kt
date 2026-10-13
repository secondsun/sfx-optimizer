package dev.secondsun.sfxoptimizer.viewer

import dev.secondsun.sfxoptimizer.compiler.SfxCompiler
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

fun main(args: Array<String>) {
    var port = 8080
    var openBrowser = true
    var initialFile: String? = null
    var compileInput: String? = null
    var compileOutput: String? = null
    val includeDirs = mutableListOf<File>()

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--compile", "-c" -> {
                if (i + 1 < args.size) {
                    compileInput = args[i + 1]
                    i++
                }
            }
            "--output", "-o" -> {
                if (i + 1 < args.size) {
                    compileOutput = args[i + 1]
                    i++
                }
            }
            "--include", "-I" -> {
                if (i + 1 < args.size) {
                    includeDirs.add(File(args[i + 1]))
                    i++
                }
            }
            "--port", "-p" -> {
                if (i + 1 < args.size) {
                    port = args[i + 1].toIntOrNull() ?: 8080
                    i++
                }
            }
            "--no-browser" -> {
                openBrowser = false
            }
            else -> {
                if (arg.startsWith("-I") && arg.length > 2) {
                    includeDirs.add(File(arg.substring(2)))
                } else if (!arg.startsWith("-")) {
                    initialFile = arg
                }
            }
        }
        i++
    }

    if (compileInput != null) {
        val inFile = File(compileInput)
        if (!inFile.exists()) {
            System.err.println("Error: Input file not found: ${inFile.absolutePath}")
            kotlin.system.exitProcess(1)
        }
        val compiler = SfxCompiler()
        val result = compiler.compileFile(inFile, includeDirs)
        if (compileOutput != null) {
            val outFile = File(compileOutput)
            outFile.parentFile?.mkdirs()
            outFile.writeText(result.loweredSource)
        } else {
            println(result.loweredSource)
        }
        return
    }

    val server = ViewerServer(initialPort = port, workingDir = File("."))
    val boundPort = server.start()

    val fileQuery =
        if (initialFile != null) {
            "?file=" + URLEncoder.encode(initialFile, StandardCharsets.UTF_8)
        } else {
            ""
        }
    val urlString = "http://127.0.0.1:$boundPort/$fileQuery"

    println(
        """
        ╔══════════════════════════════════════════════════════════╗
        ║         SuperFX Assembly Liveness Code Viewer            ║
        ╚══════════════════════════════════════════════════════════╝
        Server running at: $urlString
        Press Ctrl+C to exit.
        """.trimIndent(),
    )

    if (openBrowser) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(urlString))
            }
        } catch (e: Exception) {
            println("Notice: Could not automatically open default browser: ${e.message}")
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("\nStopping Viewer server...")
            server.stop()
        },
    )

    // Keep process alive
    try {
        Thread.currentThread().join()
    } catch (e: InterruptedException) {
        server.stop()
    }
}
