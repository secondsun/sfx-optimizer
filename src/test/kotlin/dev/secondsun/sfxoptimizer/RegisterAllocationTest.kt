
package dev.secondsun.sfxoptimizer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.Test

class RegisterAllocationTest {

    @Test
    fun `test reciprocal`() {
        val program = """
            ; In : R0 fixed88 the value to return the reciprocal of
            ; Out : R3 a fixed88 reciprocal
            function reciprocal in
                register dividend, divisor, remainder, quotient
            
                iwt dividend, #$1
                move divisor, in
                move remainder, dividend;
                iwt quotient, #0 ; r0 =  ()
                
                for #$10
                    with remainder
                    add remainder
                    
                    from remainder
                    cmp divisor
                    bmi lp
                    nop
                        
                    with quotient
                    add quotient
                    with quotient
                    add 1 ;quotient++
                    with remainder
                    sub divisor
                lp: 	
                endfor
                move r3, quotient
                return 
            endfunction
            
            register toRecip
            iwt toRecip, #$0800
            
            call reciprocal toRecip
            
            stop
            
        """.trimMargin()

        val mainLine = program.lines().indexOfFirst { it.trim().startsWith("register toRecip") }
        val graph = graph(program, mainLine)

        val report = allocate(graph)

        val fnAlloc = requireNotNull(report.functionAllocations["reciprocal"]) { "Function allocations should contain reciprocal" }
        assertTrue(fnAlloc.isNotEmpty(), "Function allocations should not be empty")

        // Verify that every declared variable was allocated to a register (no spills)
        listOf("dividend", "divisor", "remainder", "quotient", "in").forEach { label ->
            val result = fnAlloc[IntervalKey.LabelKey(label)]
            assertNotNull(result, "Label $label should have an allocation result")
            assertTrue(result is AllocationResult.Register, "Label $label should be allocated to a hardware register")
            println("Allocated $label -> ${(result as AllocationResult.Register).register}")
        }

        // Verify non-interference: interfering variables must NOT share registers
        val divisorReg = (fnAlloc[IntervalKey.LabelKey("divisor")] as AllocationResult.Register).register
        val remainderReg = (fnAlloc[IntervalKey.LabelKey("remainder")] as AllocationResult.Register).register
        val quotientReg = (fnAlloc[IntervalKey.LabelKey("quotient")] as AllocationResult.Register).register

        assertFalse(divisorReg == remainderReg, "divisor and remainder interfere and cannot share a register")
        assertFalse(remainderReg == quotientReg, "remainder and quotient interfere and cannot share a register")
        assertFalse(divisorReg == quotientReg, "divisor and quotient interfere and cannot share a register")

        // Main body allocation
        val mainAlloc = report.mainAllocation
        val toRecipResult = mainAlloc[IntervalKey.LabelKey("toRecip")]
        assertNotNull(toRecipResult, "toRecip should be allocated")
        assertTrue(toRecipResult is AllocationResult.Register, "toRecip should be allocated to a register")
        println("Allocated toRecip -> ${(toRecipResult as AllocationResult.Register).register}")
    }

    @Test
    fun `test graph coloring with spilling under register pressure`() {
        // Create 3 mutually interfering variables: a, b, c
        val keyA: IntervalKey = IntervalKey.LabelKey("a")
        val keyB: IntervalKey = IntervalKey.LabelKey("b")
        val keyC: IntervalKey = IntervalKey.LabelKey("c")

        val intervals: Map<IntervalKey, Interval> = mapOf(
            keyA to Interval(keyA).apply { start = 1; end = 10 },
            keyB to Interval(keyB).apply { start = 1; end = 10 },
            keyC to Interval(keyC).apply { start = 1; end = 10 }
        )

        // Only 2 registers available: R0, R1
        val allocator = GraphColoringAllocator(registerPool = listOf(Constants.Register.R0, Constants.Register.R1))
        val interferenceGraph = allocator.buildInterferenceGraph(intervals)

        val result = allocator.allocate(interferenceGraph)

        val registers = result.values.filterIsInstance<AllocationResult.Register>()
        val spills = result.values.filterIsInstance<AllocationResult.Spill>()

        // 2 should get colored and 1 must spill
        assertTrue(registers.size == 2, "Expected 2 variables colored with 2 registers")
        assertTrue(spills.size == 1, "Expected 1 variable spilled due to register pressure")
        assertFalse(registers[0].register == registers[1].register, "Assigned registers must be distinct")
    }
}