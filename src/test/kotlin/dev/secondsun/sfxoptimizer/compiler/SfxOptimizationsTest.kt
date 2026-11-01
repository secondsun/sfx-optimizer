package dev.secondsun.sfxoptimizer.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SfxOptimizationsTest {
    private fun lines(s: String) = s.lines().map { it.trim() }

    // ---- Peephole ----

    @Test
    fun `peephole narrows signed 8-bit iwt and keeps others`() {
        val out =
            lines(
                SfxPeepholeOptimizer.optimize(
                    """
                    iwt r3, #0
                    iwt r4, #-16
                    iwt r4, #${'$'}007F
                    iwt r4, #128
                    iwt r4, #SYMBOL
                    iwt r4, #SYMBOL+1
                    """.trimIndent(),
                ),
            )
        assertEquals(
            listOf("ibt r3, #0", "ibt r4, #-16", "ibt r4, #${'$'}007F", "iwt r4, #128", "iwt r4, #SYMBOL", "iwt r4, #SYMBOL+1"),
            out,
        )
    }

    @Test
    fun `peephole removes self move except in delay slot`() {
        val out =
            lines(
                SfxPeepholeOptimizer.optimize(
                    """
                    move r2, r2
                    add r1
                    bra @x
                    move r2, r2
                    @x:
                    """.trimIndent(),
                ),
            )
        assertEquals(listOf("add r1", "bra @x", "move r2, r2", "@x:"), out)
    }

    @Test
    fun `peephole strips redundant from r0 but not after with`() {
        val out =
            lines(
                SfxPeepholeOptimizer.optimize(
                    """
                    to r3
                    from r0
                    add r4
                    with r5
                    from r0
                    add r4
                    """.trimIndent(),
                ),
            )
        assertEquals(listOf("to r3", "add r4", "with r5", "from r0", "add r4"), out)
    }

    @Test
    fun `peephole reduces power of two multiplies`() {
        val out = lines(SfxPeepholeOptimizer.optimize("with r2\nmult #2\nwith r3\nmult #4\nwith r4\nmult #2\nbeq @x\nnop\n@x:"))
        assertEquals(
            listOf("with r2", "add r2", "with r3", "add r3", "with r3", "add r3", "with r4", "mult #2", "beq @x", "nop", "@x:"),
            out,
        )
    }

    // ---- Inlining ----

    private val helper =
        """
        inline function add_one v : out
            register t
            to t
            add #1
            move out, t
            return out
        endfunction

        """.trimIndent()

    @Test
    fun `explicit inline function is expanded with no call overhead`() {
        val src =
            helper + "\nregister x, y\nibt x, #5\ncall add_one x : y\nstop"
        val lowered = SfxCompiler().compileSource(src).loweredSource
        assertFalse(lowered.contains("call add_one"), lowered)
        assertFalse(lowered.contains("gsu_stack_push r11"), lowered)
        assertFalse(lowered.contains("link #4"), lowered)
        assertFalse(lowered.contains("function add_one"), lowered)
        assertTrue(lowered.contains("[sfx-optimizer] inline add_one"), lowered)
    }

    @Test
    fun `leaf function called inside a loop is auto inlined but kept standalone`() {
        val src =
            """
            function bump v : out
                from v
                to out
                add #1
                return out
            endfunction

            register n, acc
            ibt acc, #0
            ibt n, #4
            forR n
                call bump acc : acc
            endfor
            stop
            """.trimIndent()
        val lowered = SfxCompiler().compileSource(src).loweredSource
        assertFalse(lowered.contains("call bump"), lowered)
        assertTrue(lowered.contains("function bump"), lowered)
    }

    @Test
    fun `leaf function called outside a loop is not auto inlined`() {
        val src =
            """
            function bump v : out
                from v
                to out
                add #1
                return out
            endfunction

            register acc
            ibt acc, #0
            call bump acc : acc
            stop
            """.trimIndent()
        assertTrue(SfxCompiler().compileSource(src).loweredSource.contains("call bump"))
    }

    @Test
    fun `multiple returns become branches to a unique exit label`() {
        val src =
            """
            inline function clamp v : out
                register zero
                ibt zero, #0
                from v
                cmp zero
                bpl @pos
                nop
                return zero
            @pos:
                return v
            endfunction

            register a, b, c, d
            ibt a, #-3
            call clamp a : b
            call clamp b : d
            stop
            """.trimIndent()
        val lowered = SfxCompiler().compileSource(src).loweredSource
        assertTrue(lowered.contains("bra @inline_exit_0"), lowered)
        assertTrue(lowered.contains("bra @inline_exit_1"), lowered)
        assertTrue(lowered.contains("@pos_inline_0:"), lowered)
        assertTrue(lowered.contains("@pos_inline_1:"), lowered)
        assertFalse(lowered.contains("call clamp"), lowered)
    }

    // ---- Loop caller-save hoisting ----

    @Test
    fun `caller save of loop invariant register is hoisted out of the for loop`() {
        val src =
            """
            function clobber_r1 in : out
                register a = r1
                move a, in
                with a
                add a
                move out, a
                return out
            endfunction

            function caller n : total
                register pinned = r1, temp, i
                iwt pinned, #42
                iwt total, #0
                forR n
                    call clobber_r1 total : temp
                    move total, temp
                endfor
                from pinned
                to total
                add total
                return total
            endfunction
            """.trimIndent()
        val lowered = lines(SfxCompiler(enableInlining = false).compileSource(src).loweredSource)
        val forIdx = lowered.indexOfFirst { it.startsWith("forR") }
        val endIdx = lowered.indexOfFirst { it.startsWith("endfor") }
        val pushIdx = lowered.indexOf("gsu_stack_push r1")
        val popIdx = lowered.indexOf("gsu_stack_pop r1")
        assertTrue(pushIdx in 0 until forIdx, lowered.joinToString("\n"))
        assertTrue(popIdx > endIdx, lowered.joinToString("\n"))
        assertEquals(1, lowered.count { it == "gsu_stack_push r1" })
    }
}
