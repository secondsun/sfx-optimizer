package dev.secondsun.sfxoptimizer.viewer

import dev.secondsun.sfxoptimizer.compiler.SfxCompiler
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

fun main(args: Array<String>) {
    var port = 8080
    var openBrowser = true
    var initialFile: String? = null
    var compileInput: String? = null
    var compileOutput: String? = null
    var depfilePath: String? = null
    var depTarget: String? = null
    var enableInlining = true
    var inlineThreshold = 60
    var enableHoisting = true
    var enablePeephole = true
    var emitDebugLines = true
    var warningsAsErrors = false
    var showStats = false
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
            "--depfile", "-MD" -> {
                if (i + 1 < args.size) {
                    depfilePath = args[i + 1]
                    i++
                }
            }
            "--dep-target", "-MT" -> {
                if (i + 1 < args.size) {
                    depTarget = args[i + 1]
                    i++
                }
            }
            "--include", "-I" -> {
                if (i + 1 < args.size) {
                    includeDirs.add(File(args[i + 1]))
                    i++
                }
            }
            "--inline-threshold" -> {
                if (i + 1 < args.size) {
                    inlineThreshold = args[i + 1].toIntOrNull() ?: 60
                    i++
                }
            }
            "--no-inline" -> enableInlining = false
            "--no-hoist" -> enableHoisting = false
            "--no-peephole" -> enablePeephole = false
            "--no-debug-lines" -> emitDebugLines = false
            "--Werror" -> warningsAsErrors = true
            "--stats" -> showStats = true
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
        val compiler =
            SfxCompiler(
                enableInlining = enableInlining,
                inlineThreshold = inlineThreshold,
                enableHoisting = enableHoisting,
                enablePeephole = enablePeephole,
                emitDebugLines = emitDebugLines,
            )
        val result = compiler.compileFile(inFile, includeDirs)
        for (warning in result.warnings) {
            System.err.println("[sfx-optimizer] warning: $warning")
        }
        if (showStats) {
            val s = result.stats
            System.err.println(
                "[sfx-optimizer] stats (${inFile.name}): " +
                    "files=${s.includedFileCount}, functions=${s.functionCount}, " +
                    "inlined=${s.inlinedSiteCount}, hoisted=${s.hoistedSaveCount}, " +
                    "peephole=${s.peepholeRewriteCount}, spills=${s.spilledVariableCount}",
            )
        }
        if (warningsAsErrors && result.warnings.isNotEmpty()) {
            System.err.println("[sfx-optimizer] error: treating ${result.warnings.size} warning(s) as error (--Werror)")
            kotlin.system.exitProcess(1)
        }
        if (compileOutput != null) {
            val outFile = File(compileOutput)
            outFile.parentFile?.mkdirs()
            outFile.writeText(result.loweredSource)
        } else {
            println(result.loweredSource)
        }
        if (depfilePath != null) {
            writeDepfile(
                depFile = File(depfilePath),
                target = depTarget ?: compileOutput ?: inFile.path,
                deps = result.includedFiles,
                baseDir = File(".").canonicalFile,
            )
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
        openDefaultBrowser(urlString)
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

private fun writeDepfile(
    depFile: File,
    target: String,
    deps: List<File>,
    baseDir: File,
) {
    fun escapeMakePath(path: String): String = path.replace(" ", "\\ ")

    fun formatPath(file: File): String =
        try {
            baseDir.toPath().relativize(file.canonicalFile.toPath()).toString()
        } catch (_: IllegalArgumentException) {
            file.canonicalPath
        }

    val relDeps = deps.map { escapeMakePath(formatPath(it)) }.distinct()
    val sb = StringBuilder()
    sb.append(escapeMakePath(target)).append(':')
    for (d in relDeps) {
        sb.append(' ').append(d)
    }
    sb.append('\n')
    for (d in relDeps) {
        sb.append(d).append(":\n")
    }
    depFile.parentFile?.mkdirs()
    depFile.writeText(sb.toString())
}

private fun openDefaultBrowser(urlString: String) {
    try {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val cmd =
            when {
                os.contains("mac") -> listOf("open", urlString)
                os.contains("win") -> listOf("cmd", "/c", "start", "", urlString)
                else -> listOf("xdg-open", urlString)
            }
        ProcessBuilder(cmd).start()
    } catch (e: Exception) {
        println("Notice: Could not automatically open default browser: ${e.message}")
    }
}
