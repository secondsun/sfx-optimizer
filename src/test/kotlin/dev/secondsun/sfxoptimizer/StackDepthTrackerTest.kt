package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.sfxoptimizer.macro.CA65MacroExpander
import dev.secondsun.sfxoptimizer.stack.StackDepthTracker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StackDepthTrackerTest {
    private val tracker = StackDepthTracker()

    @Test
    fun `test push and pop tracking`() {
        val program =
            """
            gsu_stack_push r4
            gsu_stack_push r5
            gsu_stack_pop r5
            gsu_stack_pop r4
            stop
            """.trimIndent()

        val result = tracker.trackSource(program).getValue("(main)")

        assertEquals(4, result.maxDepth)
        assertEquals(0, result.blockExitDepths.values.last())
    }

    @Test
    fun `test alloc and free bytes`() {
        val program =
            """
            gsu_stack_alloc_bytes 8
            nop
            gsu_stack_free_bytes 8
            stop
            """.trimIndent()

        val result = tracker.trackSource(program).getValue("(main)")

        assertEquals(8, result.maxDepth)
        assertEquals(0, result.blockExitDepths.values.last())
    }

    @Test
    fun `test effective displacement calculation`() {
        val program =
            """
            gsu_stack_push r1
            nop
            gsu_stack_alloc_bytes 4
            nop
            stop
            """.trimIndent()

        val expanded = CA65MacroExpander().expandWithProvenance(CA65Scanner().tokenize(program))
        val result = tracker.trackExpanded(expanded).getValue("(main)")

        assertEquals(6, result.maxDepth)
        val firstNop =
            (0 until expanded.tokenizedFile.textLines()).first { line ->
                expanded.tokenizedFile
                    .getLine(line)
                    .line
                    .trim() == "nop"
            }
        assertEquals(2, result.lineDepths[firstNop])
        assertEquals(4, result.effectiveDisplacement(slotBaseOffset = 2, line = firstNop))
    }

    @Test
    fun `test for and endfor loop preserves stack depth`() {
        val program =
            """
            for 5
                add r1
            endfor
            stop
            """.trimIndent()

        val result = tracker.trackSource(program).getValue("(main)")

        // for backs up r12 and r13 (4 bytes)
        assertEquals(4, result.maxDepth)
        // after endfor, stack is restored to 0
        assertEquals(0, result.blockExitDepths.values.last())
    }

    @Test
    fun `test mismatched stack depth branch throws IllegalStateException in strict mode`() {
        val program =
            """
                beq skip
                nop
                gsu_stack_push r4
            skip:
                nop
                stop
            """.trimIndent()

        val exception =
            assertThrows(IllegalStateException::class.java) {
                tracker.trackSource(program, strict = true)
            }
        assertTrue(exception.message!!.contains("Mismatched stack depth at branch target"))
    }

    @Test
    fun `test matching stack depth branch succeeds`() {
        val program =
            """
                beq branch_b
                nop
                gsu_stack_push r4
                bra done
                nop
            branch_b:
                gsu_stack_push r5
            done:
                gsu_stack_pop r0
                stop
            """.trimIndent()

        val result = tracker.trackSource(program).getValue("(main)")
        assertEquals(2, result.maxDepth)
    }

    @Test
    fun `test trackSource with function`() {
        val program =
            """
            function test_fn
                gsu_stack_push r4
                gsu_stack_pop r4
                return
            endfunction

            call test_fn
            stop
            """.trimIndent()

        val results = tracker.trackSource(program)

        val fnResult = results.getValue("test_fn")
        assertEquals(2, fnResult.maxDepth)
        assertEquals(0, fnResult.blockExitDepths.values.last())

        val mainResult = results.getValue("(main)")
        assertEquals(0, mainResult.maxDepth)
    }

    @Test
    fun `test auditFindings emits exact 1-based line after macro expansion and no duplicate function diagnostics`() {
        val program =
            """
            ; line 1 comment
            .macro noop_macro
                nop
            .endmacro

            function leaking_fn
                for 4
                    noop_macro
                endfor
                gsu_stack_push r4
                return
            endfunction

            call leaking_fn
            call leaking_fn
            stop
            """.trimIndent()

        val tokenized = CA65Scanner().tokenize(program)
        val violations = tracker.auditFindings(tokenized)

        // Audited once per scope despite two calls from (main), and `return` is on exact 1-based line 11!
        assertEquals(1, violations.size)
        val v = violations[0]
        assertEquals("STACK_LEAK_ON_RETURN", v.rule)
        assertEquals(11, v.line)
        assertEquals(2, v.displacement)
        assertTrue(v.message.contains("Δ_SP = 2 bytes"))

        val diagnostics = tracker.auditFile(tokenized)
        assertEquals(1, diagnostics.size)
        assertEquals(11, diagnostics[0].line())
    }

    @Test
    fun `test auditFindings detects divergent branch depth with exact 1-based source line`() {
        val program =
            """
                ; line 1
                beq skip
                nop
                gsu_stack_push r4
            skip:
                nop
                stop
            """.trimIndent()

        val violations = tracker.auditFindings(CA65Scanner().tokenize(program))
        assertEquals(1, violations.size)
        val v = violations[0]
        assertEquals("DIVERGENT_STACK_DEPTH", v.rule)
        assertEquals(4, v.line)
    }

    @Test
    fun `test zero-byte label and alt1 preserve modal r10 state`() {
        val program =
            """
            function modal_stack_fn
                with r10
            @after_with:
                add #4
                with r10
                alt1
                sub #4
                return
            endfunction
            """.trimIndent()

        val results = tracker.trackSource(program, strict = false)
        val fnResult = results.getValue("modal_stack_fn")
        assertEquals(4, fnResult.maxDepth)
        assertTrue(fnResult.violations.isEmpty(), "Expected balanced stack across label and alt1, got ${fnResult.violations}")
    }

    @Test
    fun `test intra-function long jump via iwt and jmp tracks stack depth and does not leak`() {
        val program =
            """
            function long_jump_fn
                gsu_stack_push r1
                iwt r13, #@cleanup
                jmp r13
                nop
            @cleanup:
                gsu_stack_pop r1
                return
            endfunction

            call long_jump_fn
            stop
            """.trimIndent()

        val results = tracker.trackSource(program, strict = false)
        val fnResult = results.getValue("long_jump_fn")
        assertEquals(2, fnResult.maxDepth)
        assertEquals(0, fnResult.violations.size)

        val diagnostics = tracker.auditFile(CA65Scanner().tokenize(program))
        assertTrue(diagnostics.none { it.code() == "STACK_LEAK_ON_RETURN" })
    }

    @Test
    fun `test intra-function long jump via iwt and jmp detects genuine stack leak at return`() {
        val program =
            """
            function leaking_long_jump_fn
                gsu_stack_push r1
                iwt r13, #@cleanup
                jmp r13
                nop
            @cleanup:
                return
            endfunction

            call leaking_long_jump_fn
            stop
            """.trimIndent()

        val violations = tracker.auditFindings(CA65Scanner().tokenize(program))
        assertEquals(1, violations.size)
        assertEquals("STACK_LEAK_ON_RETURN", violations[0].rule)
        assertEquals(7, violations[0].line)
        assertEquals(2, violations[0].displacement)
        assertTrue(violations[0].message.contains("Δ_SP = 2 bytes"))
    }

    @Test
    fun `test unresolved dynamic indirect jump does not emit stack leak on return`() {
        val program =
            """
            function dynamic_dispatch_fn
                gsu_stack_push r1
                jmp r12
                nop
            endfunction

            call dynamic_dispatch_fn
            stop
            """.trimIndent()

        val violations = tracker.auditFindings(CA65Scanner().tokenize(program))
        assertTrue(violations.none { it.rule == "STACK_LEAK_ON_RETURN" })
    }

    @Test
    fun `test return via r11 with stack leak still triggers STACK_LEAK_ON_RETURN`() {
        val program =
            """
            function r11_return_fn
                gsu_stack_push r1
                jmp r11
                nop
            endfunction

            call r11_return_fn
            stop
            """.trimIndent()

        val violations = tracker.auditFindings(CA65Scanner().tokenize(program))
        assertEquals(1, violations.size)
        assertEquals("STACK_LEAK_ON_RETURN", violations[0].rule)
        assertEquals(3, violations[0].line)
        assertEquals(2, violations[0].displacement)
    }
}
