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

    private fun expandAndGraph(source: String) = graphExpanded(source)

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

        val graph = expandAndGraph(program)
        val result = tracker.track(graph.startNode)

        assertEquals(4, result.maxDepth)
        val exitDepth = result.blockExitDepths[graph.startNode.main]
        assertEquals(0, exitDepth)
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

        val graph = expandAndGraph(program)
        val result = tracker.track(graph.startNode)

        assertEquals(8, result.maxDepth)
        assertEquals(0, result.blockExitDepths[graph.startNode.main])
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

        val graph = expandAndGraph(program)
        val result = tracker.track(graph.startNode)

        // slotBaseOffset = 2 (e.g. 2 bytes below baseline)
        // Before push: delta_sp = 0, displacement = 2 + 0 = 2
        // After push (where nop executes): delta_sp = 2, displacement = 2 + 2 = 4
        // After alloc 4: delta_sp = 6, displacement = 2 + 6 = 8
        assertEquals(6, result.maxDepth)
        val nopLines =
            result.lineDepths.filterKeys { line ->
                graph.startNode.main.lines.any {
                    it.line.contains("nop") && it.tokens.firstOrNull()?.lineNumber == line
                }
            }
        assertTrue(nopLines.isNotEmpty())
        val firstNop = nopLines.keys.minOrNull()!!
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

        val graph = expandAndGraph(program)
        val result = tracker.track(graph.startNode)

        // for backs up r12 and r13 (4 bytes)
        assertEquals(4, result.maxDepth)
        // after endfor, stack is restored to 0
        assertEquals(0, result.blockExitDepths.values.last())
    }

    @Test
    fun `test mismatched stack depth branch throws IllegalStateException`() {
        val program =
            """
                beq skip
                nop
                gsu_stack_push r4
            skip:
                nop
                stop
            """.trimIndent()

        val graph = expandAndGraph(program)

        val exception =
            assertThrows(IllegalStateException::class.java) {
                tracker.track(graph.startNode)
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

        val graph = expandAndGraph(program)
        val result = tracker.track(graph.startNode)

        assertEquals(2, result.maxDepth)
    }

    @Test
    fun `test trackGraph with function`() {
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

        val expanded = CA65MacroExpander().expand(CA65Scanner().tokenize(program))
        val callLine = (0 until expanded.textLines()).find { expanded.getLineText(it)?.contains("call test_fn") == true } ?: 0
        val graph = graphExpanded(program, callLine)
        val results = tracker.trackGraph(graph)

        val fn = graph.getFunction("test_fn")!!
        val fnResult = results[fn]!!
        assertEquals(2, fnResult.maxDepth)
        assertEquals(0, fnResult.blockExitDepths.values.last())

        val mainResult = results[graph.startNode]!!
        assertEquals(0, mainResult.maxDepth)
    }
}
