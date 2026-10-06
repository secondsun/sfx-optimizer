package dev.secondsun.sfxoptimizer.viewer

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ViewerServerTest {
    @Test
    fun `test simple json serializer`() {
        val loc = LocationDto(line = 2, colStart = 4, colEnd = 8)
        val json = SimpleJson.serialize(loc)
        assertEquals("""{"line":2,"colStart":4,"colEnd":8}""", json)

        val escaped = SimpleJson.escapeString("Hello \"World\"\nTest\\")
        assertEquals(""""Hello \"World\"\nTest\\"""", escaped)
    }

    @Test
    fun `test code analyzer on reciprocal sample`() {
        val program =
            """
            function reciprocal in
                register dividend, divisor, remainder, quotient
            
                iwt dividend, #$1
                move divisor, in
                move remainder, dividend
                iwt quotient, #0
                
                with remainder
                add remainder
                return
            endfunction
            
            register toRecip
            iwt toRecip, #$0800
            call reciprocal toRecip
            stop
            """.trimIndent()

        val analyzer = CodeAnalyzer()
        val result = analyzer.analyze(program, "reciprocal.sgs")

        assertEquals("reciprocal.sgs", result.fileName)
        assertTrue(result.totalLines > 10)
        assertFalse(result.intervals.isEmpty())

        // Check that variable labels were extracted
        val intervalNames = result.intervals.map { it.keyName.lowercase() }
        assertTrue(intervalNames.contains("torecip"))
        assertTrue(intervalNames.contains("dividend"))
        assertTrue(intervalNames.contains("divisor"))

        // Check allocated map
        assertTrue(result.allocatedMap.keys.any { it.equals("torecip", ignoreCase = true) })
        val allocatedReg =
            result.allocatedMap.entries
                .first { it.key.equals("torecip", ignoreCase = true) }
                .value
        assertNotNull(allocatedReg)

        // Check tokens were classified
        val firstLine = result.lines[0]
        assertTrue(firstLine.tokens.any { it.cssClass == "tok-keyword" && it.text == "function" })
    }

    @Test
    fun `test multi-function code scoping`() {
        val program =
            """
            function vector3_copy
               move r1, r0
               move r3, r2
               return
            endfunction

            function vector3_cross
               move r4, r0
               move r5, r1
               return
            endfunction
            """.trimIndent()

        val analyzer = CodeAnalyzer()
        val result = analyzer.analyze(program, "vector.i")

        assertEquals(2, result.functions.size)
        assertEquals("vector3_copy", result.functions[0].name)
        assertEquals("vector3_cross", result.functions[1].name)

        // In vector3_copy, r3 interval should have scope "vector3_copy" and endLine <= 4
        val copyR3 = result.functions[0].intervals.find { it.keyName == "r3" }
        assertNotNull(copyR3)
        assertEquals("vector3_copy", copyR3.functionScope)
        assertTrue(copyR3.endLine <= 4)

        // In vector3_cross, r4 interval should have scope "vector3_cross" and startLine >= 5
        val crossR4 = result.functions[1].intervals.find { it.keyName == "r4" }
        assertNotNull(crossR4)
        assertEquals("vector3_cross", crossR4.functionScope)
        assertTrue(crossR4.startLine >= 5)
    }

    @Test
    fun `test vector utility file analysis`() {
        val file = File("src/test/resources/homebrew/X-GSU/gsu_maths/gsu_vector.i")
        assertTrue(file.exists())
        val content = file.readText()
        val analyzer = CodeAnalyzer()
        val result = analyzer.analyze(content, file.name)

        // Verify all 11 vector functions were discovered
        assertTrue(result.functions.size >= 10)
        val fnNames = result.functions.map { it.name }
        assertTrue(fnNames.contains("vector3_copy"))
        assertTrue(fnNames.contains("vector3_cross"))
        assertTrue(fnNames.contains("vector3_dot"))
        assertTrue(fnNames.contains("vector3_add"))
        assertTrue(fnNames.contains("vector3_negate"))
        assertTrue(fnNames.contains("vector3_subtract"))
        assertTrue(fnNames.contains("vector3_normalize"))
        assertTrue(fnNames.contains("vector3_transform"))

        // Check that vector3_copy has independent scoped intervals
        val copyFn = result.functions.find { it.name == "vector3_copy" }!!
        assertFalse(copyFn.intervals.isEmpty())
        copyFn.intervals.forEach {
            assertEquals("vector3_copy", it.functionScope)
            assertTrue(it.startLine >= copyFn.startLine && it.endLine <= copyFn.endLine)
        }

        // Verify that no fake (main) scope bleeding exists
        assertFalse(fnNames.contains("(main)"))
        assertTrue(result.intervals.all { it.functionScope != "(main)" })

        // Verify strict function boundaries between vector3_cross and vector3_dot
        val crossFn = result.functions.find { it.name == "vector3_cross" }!!
        val dotFn = result.functions.find { it.name == "vector3_dot" }!!
        assertTrue(crossFn.endLine < dotFn.startLine)
        crossFn.intervals.forEach {
            assertTrue(it.startLine >= crossFn.startLine && it.endLine <= crossFn.endLine)
        }
        dotFn.intervals.forEach {
            assertTrue(it.startLine >= dotFn.startLine && it.endLine <= dotFn.endLine)
        }
    }

    @Test
    fun `test viewer server endpoints`() {
        val tempDir = File.createTempFile("sfx_test", "dir")
        tempDir.delete()
        tempDir.mkdirs()

        val sampleFile = File(tempDir, "test.sgs")
        sampleFile.writeText(
            """
            register count = r8
            iwt count, #$12
            from count
            to r5
            add #$2
            stop
            """.trimIndent(),
        )

        val server = ViewerServer(initialPort = 18880, workingDir = tempDir)
        val port = server.start()
        assertTrue(port >= 18880)

        val client = HttpClient.newHttpClient()

        try {
            // 1. Root page
            val rootReq =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/"))
                    .GET()
                    .build()
            val rootRes = client.send(rootReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, rootRes.statusCode())
            assertTrue(rootRes.body().contains("SuperFX Code & Liveness Viewer"))

            // 2. CSS resource
            val cssReq =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/viewer.css"))
                    .GET()
                    .build()
            val cssRes = client.send(cssReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, cssRes.statusCode())
            assertTrue(cssRes.body().contains("--line-height"))

            // 3. JS resource
            val jsReq =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/viewer.js"))
                    .GET()
                    .build()
            val jsRes = client.send(jsReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, jsRes.statusCode())
            assertTrue(jsRes.body().contains("LINE_HEIGHT"))

            // 4. API files
            val filesReq =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/api/files"))
                    .GET()
                    .build()
            val filesRes = client.send(filesReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, filesRes.statusCode())
            assertTrue(filesRes.body().contains("test.sgs"))

            // 5. API load file
            val fileLoadReq =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/api/file?path=${sampleFile.absolutePath}"))
                    .GET()
                    .build()
            val fileLoadRes = client.send(fileLoadReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, fileLoadRes.statusCode())
            assertTrue(fileLoadRes.body().contains("\"fileName\":\"test.sgs\""))
            assertTrue(fileLoadRes.body().contains("\"count\""))

            // 6. API analyze POST
            val analyzeReq =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/api/analyze"))
                    .POST(HttpRequest.BodyPublishers.ofString("iwt r1, #5\nstw (r1)"))
                    .build()
            val analyzeRes = client.send(analyzeReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, analyzeRes.statusCode())
            assertTrue(analyzeRes.body().contains("\"r1\""))
        } finally {
            server.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test implicit registers in vector3_copy`() {
        val file = File("src/test/resources/homebrew/X-GSU/gsu_maths/gsu_vector.i")
        val content = file.readText()
        val analyzer = CodeAnalyzer()
        val result = analyzer.analyze(content, file.name)

        val copyFn = result.functions.find { it.name == "vector3_copy" }!!
        val r0Interval = copyFn.intervals.find { it.keyName == "r0" }
        assertNotNull(r0Interval, "vector3_copy should track implicit r0 interval from ldw/stw")

        // ldw (r1) writes r0 implicitly, stw (r2) reads r0 implicitly
        assertTrue(r0Interval.writes.isNotEmpty(), "r0 should have writes from ldw")
        assertTrue(r0Interval.reads.isNotEmpty(), "r0 should have reads from stw")

        // Check tokens on lines with ldw and stw
        val ldwLine = result.lines.find { it.text.contains("ldw (r1)") && it.lineNumber in copyFn.startLine..copyFn.endLine }!!
        val ldwToken = ldwLine.tokens.find { it.text.equals("ldw", ignoreCase = true) }!!
        assertTrue(ldwToken.implicitWrites.contains("r0"), "ldw token should have implicit write for r0")

        val stwLine = result.lines.find { it.text.contains("stw (r2)") && it.lineNumber in copyFn.startLine..copyFn.endLine }!!
        val stwToken = stwLine.tokens.find { it.text.equals("stw", ignoreCase = true) }!!
        assertTrue(stwToken.implicitReads.contains("r0"), "stw token should have implicit read for r0")
    }

    @Test
    fun `test implicit registers for lmult`() {
        val snippet =
            """
            function test_mult
                to r5
                ldw (r0)
                to r6
                ldw (r1)
                lmult
                return
            endfunction
            """.trimIndent()
        val analyzer = CodeAnalyzer()
        val result = analyzer.analyze(snippet, "test.sgs")

        val fn = result.functions.find { it.name == "test_mult" }!!
        val r4Interval = fn.intervals.find { it.keyName == "r4" }
        val r6Interval = fn.intervals.find { it.keyName == "r6" }

        assertNotNull(r4Interval, "lmult should implicitly write r4")
        assertNotNull(r6Interval, "lmult should implicitly read r6")

        val lmultLine = result.lines.find { it.text.contains("lmult") }!!
        val lmultToken = lmultLine.tokens.find { it.text == "lmult" }!!
        assertTrue(lmultToken.implicitWrites.contains("r4"))
        assertTrue(lmultToken.implicitReads.contains("r6"))
    }

    @Test
    fun `test undeclared labels do not appear in swimlanes`() {
        val file = File("src/test/resources/homebrew/X-GSU/gsu_maths/gsu_vector.i")
        val content = file.readText()
        val analyzer = CodeAnalyzer()
        val result = analyzer.analyze(content, file.name)

        val crossFn = result.functions.find { it.name == "vector3_cross" }!!
        val intervalKeys = crossFn.intervals.map { it.keyName.lowercase() }

        // Code labels or address constants must NOT be treated as registers
        assertFalse(intervalKeys.contains("vector_cross_out"), "VECTOR_CROSS_OUT should not be in swimlane intervals")
        assertFalse(intervalKeys.contains("small"), "small branch label should not be in swimlane intervals")
        assertFalse(intervalKeys.contains("big"), "big branch label should not be in swimlane intervals")

        // In vector3_cross all intervals should be hardware registers
        assertTrue(crossFn.intervals.all { it.isRegister })
    }

    @Test
    fun `test forced register assignment precoloring`() {
        val program =
            """
            function test_forced
                register my_var = r4
                iwt my_var, #$10
                stw (my_var)
                return
            endfunction
            """.trimIndent()
        val analyzer = CodeAnalyzer()
        val result = analyzer.analyze(program, "test_forced.sgs")

        val fn = result.functions.find { it.name == "test_forced" }!!
        val myVarInterval = fn.intervals.find { it.keyName == "my_var" }
        assertNotNull(myVarInterval, "my_var should have an interval")
        assertEquals("R4", myVarInterval.allocatedRegister, "my_var should be allocated to forced register R4")
        assertFalse(myVarInterval.isSpill)
    }

    @Test
    fun `test all code block files analyze cleanly`() {
        val codeBlocksDir = File("src/test/resources/code_blocks")
        assertTrue(codeBlocksDir.exists() && codeBlocksDir.isDirectory)
        val files = codeBlocksDir.listFiles { f -> f.extension == "sgs" } ?: emptyArray()
        assertTrue(files.isNotEmpty(), "code_blocks directory should contain sample files")

        val analyzer = CodeAnalyzer()
        for (f in files) {
            val result = analyzer.analyze(f.readText(), f.name)
            assertTrue(result.totalLines > 0, "${f.name} should have lines")
            assertNotNull(result.fileName)
        }
    }
}
