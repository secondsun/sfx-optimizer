package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.macro.CA65MacroExpander
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

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
        assertEquals("add 4", line(expanded, 1))
        assertEquals("with r10", line(expanded, 2))
        assertEquals("sub 4", line(expanded, 3))
    }

    @Test
    fun `test expandWithProvenance preserves exact 1-based original line origins`() {
        val uri = URI.create("file:///workspace/sample.sgs")
        val program =
            """
            ; header comment (line 1)
            .macro bump reg
                inc reg
            .endmacro

            gsu_stack_push r4
            @after_push: bump r2
            stop
            """.trimIndent()

        val tokenized = scanner.tokenize(program, uri)
        val expanded = expander.expandWithProvenance(tokenized)

        // Expanded lines:
        // 0..3: from r4 / stw (r10) / inc r10 / inc r10 -> all from original line 6
        // 4..5: @after_push: / inc r2 -> both from original line 7
        // 6: stop -> from original line 8
        assertEquals(7, expanded.tokenizedFile.textLines())
        for (i in 0..3) {
            assertEquals(uri, expanded.originOf(i).uri)
            assertEquals(6, expanded.originOf(i).line1)
        }
        assertEquals("@after_push:", line(expanded.tokenizedFile, 4))
        assertEquals(7, expanded.originOf(4).line1)
        assertEquals("inc r2", line(expanded.tokenizedFile, 5))
        assertEquals(7, expanded.originOf(5).line1)
        assertEquals("stop", line(expanded.tokenizedFile, 6))
        assertEquals(8, expanded.originOf(6).line1)
    }
}
