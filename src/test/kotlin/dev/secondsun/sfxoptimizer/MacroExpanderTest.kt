package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.macro.CA65MacroExpander
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MacroExpanderTest {
    private val scanner = CA65Scanner()
    private val expander = CA65MacroExpander()

    private fun line(
        file: TokenizedFile,
        index: Int,
    ): String = file.getLine(index).line.trim()

    @Test
    fun `test gsu_stack_push expansion`() {
        val program =
            """
            iwt r4, #$1234
            gsu_stack_push r4
            stop
            """.trimIndent()

        val tokenized = scanner.tokenize(program)
        val expanded = expander.expand(tokenized)

        // Line 0: iwt r4, #$1234
        // Line 1: from r4
        // Line 2: stw (r10)
        // Line 3: inc r10
        // Line 4: inc r10
        // Line 5: stop
        assertEquals(6, expanded.textLines())
        assertEquals("from r4", line(expanded, 1))
        assertEquals("stw (r10)", line(expanded, 2))
        assertEquals("inc r10", line(expanded, 3))
        assertEquals("inc r10", line(expanded, 4))
        assertEquals("stop", line(expanded, 5))
    }

    @Test
    fun `test gsu_stack_pop expansion`() {
        val program =
            """
            gsu_stack_pop r5
            """.trimIndent()

        val tokenized = scanner.tokenize(program)
        val expanded = expander.expand(tokenized)

        assertEquals(4, expanded.textLines())
        assertEquals("dec r10", line(expanded, 0))
        assertEquals("dec r10", line(expanded, 1))
        assertEquals("to r5", line(expanded, 2))
        assertEquals("ldw (r10)", line(expanded, 3))
    }

    @Test
    fun `test recursive backuploop expansion`() {
        val program =
            """
            backuploop
            """.trimIndent()

        val tokenized = scanner.tokenize(program)
        val expanded = expander.expand(tokenized)

        // backuploop expands to gsu_stack_push r12 then gsu_stack_push r13 (8 instructions)
        assertEquals(8, expanded.textLines())
        assertEquals("from r12", line(expanded, 0))
        assertEquals("stw (r10)", line(expanded, 1))
        assertEquals("inc r10", line(expanded, 2))
        assertEquals("inc r10", line(expanded, 3))
        assertEquals("from r13", line(expanded, 4))
        assertEquals("stw (r10)", line(expanded, 5))
        assertEquals("inc r10", line(expanded, 6))
        assertEquals("inc r10", line(expanded, 7))
    }

    @Test
    fun `test custom macro definition and invocation`() {
        val program =
            """
            .macro add_imm reg, val
                with reg
                add val
            .endmacro

            add_imm r3, 5
            """.trimIndent()

        val tokenized = scanner.tokenize(program)
        val expanded = expander.expand(tokenized)

        assertEquals(2, expanded.textLines())
        assertEquals("with r3", line(expanded, 0))
        assertEquals("add 5", line(expanded, 1))
    }

    @Test
    fun `test define directive substitution`() {
        val program =
            """
            .define myPtr r4
            ldw (myPtr)
            """.trimIndent()

        val tokenized = scanner.tokenize(program)
        val expanded = expander.expand(tokenized)

        assertEquals(1, expanded.textLines())
        assertEquals("ldw (r4)", line(expanded, 0))
    }

    @Test
    fun `test for and endfor expansion`() {
        val program =
            """
            for 5
                add r1
            endfor
            """.trimIndent()

        val tokenized = scanner.tokenize(program)
        val expanded = expander.expand(tokenized)

        // for 5 expands to:
        // backuploop (8) + iwt r12, #5 (1) + move r13, r15 (1) = 10 lines
        // add r1 (1 line)
        // endfor expands to:
        // loop (1) + nop (1) + restoreloop (8) = 10 lines
        // Total = 21 lines
        assertEquals(21, expanded.textLines())
        assertEquals("iwt r12, #5", line(expanded, 8))
        assertEquals("move r13, r15", line(expanded, 9))
        assertEquals("add r1", line(expanded, 10))
        assertEquals("loop", line(expanded, 11))
        assertEquals("nop", line(expanded, 12))
    }

    @Test
    fun `test stack alloc and free bytes`() {
        val program =
            """
            gsu_stack_alloc_bytes 4
            gsu_stack_free_bytes 4
            """.trimIndent()

        val tokenized = scanner.tokenize(program)
        val expanded = expander.expand(tokenized)

        assertEquals(4, expanded.textLines())
        assertEquals("with r10", line(expanded, 0))
        assertEquals("adds 4", line(expanded, 1))
        assertEquals("with r10", line(expanded, 2))
        assertEquals("sub 4", line(expanded, 3))
    }
}
