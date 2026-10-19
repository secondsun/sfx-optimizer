package dev.secondsun.sfxoptimizer.compiler

import dev.secondsun.sfxoptimizer.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SfxCompilerTest {
    @Test
    fun `test function parameters return and call lowering`() {
        val source =
            """
            function add_values a, b : sum
                from a
                to sum
                add b
                return sum
            endfunction

            register x, y, result = r3
            iwt x, #10
            iwt y, #20
            call add_values x, y : result
            stop
            """.trimIndent()

        val compiler = SfxCompiler()
        val compiled = compiler.compileSource(source)

        val addInfo = compiled.functionInfos["add_values"]
        assertNotNull(addInfo)
        assertEquals(2, addInfo.paramRegs.size)
        assertNotNull(addInfo.returnReg)
        // Parameter registers a and b must be distinct
        assertTrue(addInfo.paramRegs[0] != addInfo.paramRegs[1])

        // Main result was precolored to R3
        assertEquals(Constants.Register.R3, compiled.mainAllocations["result"])

        // Lowered code should contain standard `function add_values`, `call add_values`, `return`
        assertTrue(compiled.loweredSource.contains("function add_values"))
        assertTrue(compiled.loweredSource.contains("call add_values"))
        assertTrue(compiled.loweredSource.contains("return"))
        // And no raw `register ` declarations remaining
        assertFalse(
            compiled.loweredSource
                .lines()
                .any { it.trim().startsWith("register ") },
        )
    }

    @Test
    fun `test caller save push pop when live variable is clobbered by callee`() {
        val source =
            """
            function clobber_r1 in : out
                register a = r1
                move a, in
                with a
                add a
                move out, a
                return out
            endfunction

            function caller ptr : total
                register pinned = r1, temp
                iwt pinned, #42
                call clobber_r1 ptr : temp
                from pinned
                to total
                add temp
                return total
            endfunction
            """.trimIndent()

        val compiler = SfxCompiler()
        val compiled = compiler.compileSource(source)

        // Since `pinned` is forced to R1 and is live across `call clobber_r1` (which clobbers R1),
        // the compiler must emit `gsu_stack_push r1` before the call and `gsu_stack_pop r1` after.
        assertTrue(compiled.loweredSource.contains("gsu_stack_push r1"))
        assertTrue(compiled.loweredSource.contains("gsu_stack_pop r1"))
    }

    @Test
    fun `test loop liveness prevents clobbering variable defined before loop`() {
        val source =
            """
            function sum_loop ptr, count : acc
                register item
                iwt acc, #0
                forR count
                    to item
                    ldw (ptr)
                    with acc
                    add item
                    inc ptr
                    inc ptr
                endfor
                return acc
            endfunction
            """.trimIndent()

        val compiler = SfxCompiler()
        val compiled = compiler.compileSource(source)
        val info = compiled.functionInfos["sum_loop"]
        assertNotNull(info)

        val ptrReg = info.allocatedMap["ptr"]
        val accReg = info.allocatedMap["acc"]
        val itemReg = info.allocatedMap["item"]
        assertNotNull(ptrReg)
        assertNotNull(accReg)
        assertNotNull(itemReg)

        // ptr, acc, and item are all live inside the loop and must get distinct registers
        assertEquals(3, setOf(ptrReg, accReg, itemReg).size)
        // ptr is used in ldw (ptr), so it must not be R12, R13, R14, R15
        assertTrue(ptrReg !in setOf(Constants.Register.R12, Constants.Register.R13, Constants.Register.R14, Constants.Register.R15))
    }

    @Test
    fun `test immediate and struct namespace tokens are not replaced with registers`() {
        val source =
            """
            function test_ns camera, up : out
                iwt camera, #(CAMERA + camera::up)
                to out
                ldw (camera)
                with out
                add up
                return out
            endfunction
            """.trimIndent()

        val compiler = SfxCompiler()
        val compiled = compiler.compileSource(source)

        // #(CAMERA + camera::up) must remain intact
        assertTrue(compiled.loweredSource.contains("#(CAMERA + camera::up)"))
    }

    @Test
    fun `test unified SfxProgramAnalyzer and SfxCompiler produce identical register allocation`() {
        val source =
            """
            function transform_point in_pt, out_pt : ok
                register temp_x, temp_y, scale = r5
                to temp_x
                ldw (in_pt)
                inc in_pt
                inc in_pt
                to temp_y
                ldw (in_pt)
                with temp_x
                mult scale
                with temp_y
                mult scale
                from temp_x
                stw (out_pt)
                inc out_pt
                inc out_pt
                from temp_y
                stw (out_pt)
                iwt ok, #1
                return ok
            endfunction

            function process_all points, count : total
                register cur_pt, out_buf, status
                iwt total, #0
                forR count
                    call transform_point cur_pt, out_buf : status
                    with total
                    add status
                endfor
                return total
            endfunction

            register main_pts, main_count, main_res = r3
            iwt main_pts, #100
            iwt main_count, #10
            call process_all main_pts, main_count : main_res
            stop
            """.trimIndent()

        val compiler = SfxCompiler()
        val compiled = compiler.compileSource(source)

        val tokenized =
            dev.secondsun.retro.util
                .CA65Scanner()
                .tokenize(source)
        val analysis =
            dev.secondsun.sfxoptimizer.analysis.SfxProgramAnalyzer
                .analyze(tokenized, source.lines())

        // Main allocations match
        val main = analysis.main
        assertNotNull(main)
        assertEquals(main.allocatedMap, compiled.mainAllocations)

        // Function allocations match
        val analysisFnMap = analysis.functions.associateBy { it.name }
        assertEquals(analysisFnMap.keys, compiled.functionInfos.keys)
        for ((fnName, fnAnalysis) in analysisFnMap) {
            val compiledFn = compiled.functionInfos[fnName]
            assertNotNull(compiledFn)
            assertEquals(fnAnalysis.allocatedMap, compiledFn.allocatedMap, "Allocations differ for function $fnName")
            assertEquals(fnAnalysis.paramRegs, compiledFn.paramRegs, "Param registers differ for function $fnName")
            assertEquals(fnAnalysis.returnReg, compiledFn.returnReg, "Return register differs for function $fnName")
        }
    }
}
