package dev.secondsun.sfxoptimizer.viewer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ViewerServer(
    val initialPort: Int = 8080,
    val workingDir: File = File("."),
) {
    private var server: HttpServer? = null
    var boundPort: Int = -1
        private set

    val analyzer = CodeAnalyzer()

    fun start(): Int {
        var port = initialPort
        var created = false
        while (!created && port < initialPort + 100) {
            try {
                server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
                boundPort = port
                created = true
            } catch (e: Exception) {
                port++
            }
        }
        if (!created || server == null) {
            throw IllegalStateException("Failed to bind HttpServer on port $initialPort..${initialPort + 100}")
        }

        val s = server!!
        s.createContext("/", RootHandler())
        s.createContext("/viewer.css", ResourceHandler("viewer.css", "text/css"))
        s.createContext("/viewer.js", ResourceHandler("viewer.js", "application/javascript"))
        s.createContext("/api/files", FilesHandler())
        s.createContext("/api/file", FileLoadHandler())
        s.createContext("/api/analyze", AnalyzeHandler())
        s.executor = null // default executor
        s.start()
        return boundPort
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private inner class RootHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed")
                return
            }
            val stream = getResourceStream("index.html")
            if (stream == null) {
                sendResponse(exchange, 404, "text/plain", "index.html not found in resources")
                return
            }
            val bytes = stream.readAllBytes()
            exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class ResourceHandler(
        val resourceName: String,
        val contentType: String,
    ) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed")
                return
            }
            val stream = getResourceStream(resourceName)
            if (stream == null) {
                sendResponse(exchange, 404, "text/plain", "Resource $resourceName not found")
                return
            }
            val bytes = stream.readAllBytes()
            exchange.responseHeaders.set("Content-Type", "$contentType; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class FilesHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed")
                return
            }
            val extensions = setOf("s", "sgs", "i", "asm")
            val filesList = mutableListOf<String>()

            fun scanDir(
                dir: File,
                depth: Int,
            ) {
                if (depth > 5) return
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (child.isDirectory && !child.name.startsWith(".")) {
                        scanDir(child, depth + 1)
                    } else if (child.isFile) {
                        val ext = child.extension.lowercase()
                        if (extensions.contains(ext)) {
                            filesList.add(child.path)
                        }
                    }
                }
            }
            scanDir(workingDir, 0)
            val json = SimpleJson.serialize(filesList)
            sendResponse(exchange, 200, "application/json", json)
        }
    }

    private inner class FileLoadHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed")
                return
            }
            val query = exchange.requestURI.query ?: ""
            val params = parseQueryParams(query)
            val path = params["path"]
            if (path.isNullOrBlank()) {
                sendResponse(exchange, 400, "application/json", """{"error":"Missing 'path' query parameter"}""")
                return
            }
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                sendResponse(exchange, 404, "application/json", """{"error":"File not found: ${SimpleJson.escapeString(path)}"}""")
                return
            }
            val content = file.readText(StandardCharsets.UTF_8)
            val analysis = analyzer.analyze(content, file.name)
            val json = SimpleJson.serialize(analysis)
            sendResponse(exchange, 200, "application/json", json)
        }
    }

    private inner class AnalyzeHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "POST") {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed")
                return
            }
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val analysis = analyzer.analyze(body, "snippet.sgs")
            val json = SimpleJson.serialize(analysis)
            sendResponse(exchange, 200, "application/json", json)
        }
    }

    private fun getResourceStream(name: String): InputStream? {
        val classLoader = ViewerServer::class.java.classLoader
        return classLoader.getResourceAsStream("viewer/$name")
            ?: File("src/main/resources/viewer/$name").takeIf { it.exists() }?.inputStream()
    }

    private fun sendResponse(
        exchange: HttpExchange,
        code: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "$contentType; charset=UTF-8")
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        val os: OutputStream = exchange.responseBody
        os.use { it.write(bytes) }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                val value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                result[key] = value
            }
        }
        return result
    }
}

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8080
    val server = ViewerServer(initialPort = port)
    val bound = server.start()
    println("SFX Optimizer Code Viewer running at http://localhost:$bound")
    println("Press Ctrl+C to exit.")
}
