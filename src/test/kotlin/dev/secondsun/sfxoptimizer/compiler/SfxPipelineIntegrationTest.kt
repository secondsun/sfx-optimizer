package dev.secondsun.sfxoptimizer.compiler

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SfxPipelineIntegrationTest {
    // ---- Workstream 1 & 2: Depfiles & .dbg source line mapping ----

    @Test
    fun `compileFile collects included files and emits ca65 dbg file and line directives`() {
        val tempDir = Files.createTempDirectory("sfx-opt-pipeline").toFile()
        try {
            val helper = File(tempDir, "helper.i")
            helper.writeText(
                """
                .struct my_pair
                    a .word
                    b .word
                .endstruct

                function add_two x : out
                    from x
                    to out
                    add #2
                    return out
                endfunction
                """.trimIndent(),
            )
            val main = File(tempDir, "main.sgs")
            main.writeText(
                """
                .include "helper.i"
                register v, res
                ibt v, #5
                call add_two v : res
                stop
                """.trimIndent(),
            )

            val result = SfxCompiler().compileFile(main, listOf(tempDir))

            // Workstream 1: canonical included files captured for depfile emission
            val names = result.includedFiles.map { it.name }
            assertEquals(listOf("main.sgs", "helper.i"), names)
            assertEquals(2, result.stats.includedFileCount)
            assertEquals(1, result.stats.functionCount)

            // Workstream 2: .dbg file headers + .dbg line directives emitted outside .struct
            assertTrue(result.loweredSource.contains(".dbg file, \"main.sgs\""), result.loweredSource)
            assertTrue(result.loweredSource.contains(".dbg file, \"helper.i\""), result.loweredSource)
            assertTrue(result.loweredSource.contains(".dbg line, \"helper.i\", 6"), result.loweredSource)
            assertTrue(result.loweredSource.contains(".dbg line, \"main.sgs\", 3"), result.loweredSource)

            // Must NOT inject .dbg line inside .struct body
            val structBody =
                result.loweredSource
                    .substringAfter(".struct my_pair")
                    .substringBefore(".endstruct")
            assertFalse(structBody.contains(".dbg line"), "Found .dbg line inside .struct:\n$structBody")

            // Opt-out disables .dbg directives
            val noDbg = SfxCompiler(emitDebugLines = false).compileFile(main, listOf(tempDir))
            assertFalse(noDbg.loweredSource.contains(".dbg file"), noDbg.loweredSource)
            assertFalse(noDbg.loweredSource.contains(".dbg line"), noDbg.loweredSource)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---- Workstream 4: R10 Stack Spill Lowering ----

    @Test
    fun `function exceeding 12 live registers lowers spills to balanced R10 stack frame`() {
        // 13 simultaneously live variables in a single function forces >= 1 spill onto R10 stack
        val source =
            """
            function sum_thirteen a : total
                register v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13
                ibt v1, #1
                ibt v2, #2
                ibt v3, #3
                ibt v4, #4
                ibt v5, #5
                ibt v6, #6
                ibt v7, #7
                ibt v8, #8
                ibt v9, #9
                ibt v10, #10
                ibt v11, #11
                ibt v12, #12
                ibt v13, #13
                move total, a
                with total
                add v1
                with total
                add v2
                with total
                add v3
                with total
                add v4
                with total
                add v5
                with total
                add v6
                with total
                add v7
                with total
                add v8
                with total
                add v9
                with total
                add v10
                with total
                add v11
                with total
                add v12
                with total
                add v13
                return total
            endfunction
            """.trimIndent()

        val result = SfxCompiler().compileSource(source)

        assertTrue(result.stats.spilledVariableCount > 0, "Expected at least 1 spilled variable")
        assertTrue(result.warnings.any { it.contains("spilled") }, "Expected spill warning: ${result.warnings}")

        val lowered = result.loweredSource
        // Spill frame allocated at function entry and freed before return
        assertTrue(lowered.contains("allocate "), lowered)
        assertTrue(Regex("""with r10\s*\n\s*add #""").containsMatchIn(lowered), lowered)
        assertTrue(Regex("""with r10\s*\n\s*sub #""").containsMatchIn(lowered), lowered)
        // Reloads and stores via r10-relative addressing
        assertTrue(lowered.contains("from r10"), lowered)
        assertTrue(lowered.contains("stw ("), lowered)
        assertTrue(lowered.contains("ldw ("), lowered)
        // No unlowered register names remain in code
        assertFalse(lowered.lines().any { !it.trim().startsWith(";") && Regex("""\bv1[0-3]\b""").containsMatchIn(it) }, lowered)
    }

    @Test
    fun `spilled variable inside forR loop adjusts R10 offset by +4 bytes for backuploop`() {
        val source =
            """
            function spill_in_loop n : acc
                register v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13
                ibt v1, #1
                ibt v2, #2
                ibt v3, #3
                ibt v4, #4
                ibt v5, #5
                ibt v6, #6
                ibt v7, #7
                ibt v8, #8
                ibt v9, #9
                ibt v10, #10
                ibt v11, #11
                ibt v12, #12
                ibt v13, #13
                ibt acc, #0
                forR n
                    with acc
                    add v1
                    with acc
                    add v2
                    with acc
                    add v3
                    with acc
                    add v4
                    with acc
                    add v5
                    with acc
                    add v6
                    with acc
                    add v7
                    with acc
                    add v8
                    with acc
                    add v9
                    with acc
                    add v10
                    with acc
                    add v11
                    with acc
                    add v12
                    with acc
                    add v13
                endfor
                return acc
            endfunction
            """.trimIndent()

        val result = SfxCompiler().compileSource(source)
        assertTrue(result.stats.spilledVariableCount > 0)
        // Inside forR..endfor, at least one spill offset must be strictly larger (+4) than outside
        val bodyBeforeLoop = result.loweredSource.substringBefore("forR")
        val bodyInLoop = result.loweredSource.substringAfter("forR").substringBefore("endfor")
        val offRegex = Regex("""(?:sub #(\d+)|(?:iwt|ibt) r\d+, #(\d+)\s*\n\s*from r10)""")
        val beforeMax =
            offRegex
                .findAll(bodyBeforeLoop)
                .mapNotNull { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }
                .maxOrNull() ?: 0
        val inLoopMax =
            offRegex
                .findAll(bodyInLoop)
                .mapNotNull { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }
                .maxOrNull() ?: 0
        assertEquals(beforeMax + 4, inLoopMax, "Expected +4 R10 offset inside forR loop:\n${result.loweredSource}")
    }

    @Test
    fun `spilled variable write preserves flags for immediately following branch`() {
        val source =
            """
            function spill_flags p : out
                register v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13
                ibt v1, #1
                ibt v2, #2
                ibt v3, #3
                ibt v4, #4
                ibt v5, #5
                ibt v6, #6
                ibt v7, #7
                ibt v8, #8
                ibt v9, #9
                ibt v10, #10
                ibt v11, #11
                ibt v12, #12
                ibt v13, #3
            @dec_loop:
                with v13
                sub #1
                bne @dec_loop
                nop
                from v1
                add v2
                add v3
                add v4
                add v5
                add v6
                add v7
                add v8
                add v9
                add v10
                add v11
                add v12
                move out, r0
                return out
            endfunction
            """.trimIndent()

        val result = SfxCompiler().compileSource(source)
        val afterSub =
            result.loweredSource
                .substringAfter("sub #1")
                .substringBefore("bne @dec_loop")
        // Between `sub #1` and `bne @dec_loop`, ONLY flag-preserving instructions (`from`, `stw`) may appear
        for (line in afterSub.lines()) {
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) continue
            val mn = code.substringBefore(' ').lowercase()
            assertTrue(
                mn == "from" || mn == "stw",
                "Unexpected flag-clobbering instruction '$code' between sub #1 and bne:\n${result.loweredSource}",
            )
        }
    }

    // ---- Workstream 5: Cache-Aware Inlining & Enhanced Peephole ----

    @Test
    fun `cache-aware inliner skips inlining when cache block would exceed 512 bytes`() {
        val bigBody = (1..200).joinToString("\n") { "    iwt r1, #$it" }
        val source =
            """
            inline function huge_helper v : out
                move out, v
                with out
                add #1
                return out
            endfunction

            function cached_hot_loop x : res
                cache
            $bigBody
                call huge_helper x : res
                return res
            endfunction
            """.trimIndent()

        val result = SfxCompiler().compileSource(source)
        // 200 * 3B = 600B > 512B budget -> inliner must skip and warn, keeping standalone function
        assertTrue(result.loweredSource.contains("call huge_helper"), result.loweredSource)
        assertTrue(result.loweredSource.contains("function huge_helper"), result.loweredSource)
        assertTrue(result.warnings.any { it.contains("512B budget") }, "Expected cache budget warning: ${result.warnings}")
    }

    @Test
    fun `enhanced peephole strips redundant to r0 and with r0 and fuses dead-r0 ldw move`() {
        val input =
            """
            from r3
            to r0
            add r4
            with r0
            add r5
            ldw (r11)
            move r9, r0
            inc r11
            inc r11
            ldw (r11)
            move r7, r0
            add r1
            """.trimIndent()

        val res = SfxPeepholeOptimizer.optimizeWithStats(input)
        val outLines = res.code.lines().map { it.trim() }

        // `to r0` and `with r0` stripped; first `ldw (r11); move r9, r0` fused (next ldw overwrites r0);
        // second `ldw (r11); move r7, r0` NOT fused because `add r1` reads r0!
        assertEquals(
            listOf(
                "from r3",
                "add r4",
                "add r5",
                "to r9",
                "ldw (r11)",
                "inc r11",
                "inc r11",
                "ldw (r11)",
                "move r7, r0",
                "add r1",
            ),
            outLines,
        )
        assertEquals(3, res.rewriteCount)
    }
}
