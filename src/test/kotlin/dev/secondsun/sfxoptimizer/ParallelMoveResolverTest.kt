package dev.secondsun.sfxoptimizer

import dev.secondsun.sfxoptimizer.Constants.Register
import dev.secondsun.sfxoptimizer.solver.MoveInstruction
import dev.secondsun.sfxoptimizer.solver.ParallelMoveResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParallelMoveResolverTest {
    private val resolver = ParallelMoveResolver()

    @Test
    fun `test empty and identity moves`() {
        val result =
            resolver.resolve(
                listOf(
                    Register.R1 to Register.R1,
                    Register.R2 to Register.R2,
                ),
            )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `test independent parallel moves`() {
        val result =
            resolver.resolve(
                listOf(
                    Register.R1 to Register.R2,
                    Register.R3 to Register.R4,
                ),
            )
        assertEquals(2, result.size)
        assertTrue(result.all { it is MoveInstruction.Move })
    }

    @Test
    fun `test linear move chain topological sort`() {
        val moves =
            listOf(
                Register.R1 to Register.R2,
                Register.R2 to Register.R3,
            )
        val result = resolver.resolve(moves)

        assertEquals(2, result.size)
        // R2 -> R3 must happen before R1 -> R2
        assertEquals(MoveInstruction.Move(Register.R2, Register.R3), result[0])
        assertEquals(MoveInstruction.Move(Register.R1, Register.R2), result[1])

        val asm = result.flatMap { it.toAssembly() }
        assertEquals(listOf("move r3, r2", "move r2, r1"), asm)
    }

    @Test
    fun `test 2-register cycle with XOR swap`() {
        val moves =
            listOf(
                Register.R1 to Register.R2,
                Register.R2 to Register.R1,
            )
        val result = resolver.resolve(moves)

        assertEquals(1, result.size)
        val swap = result[0] as MoveInstruction.XorSwap
        assertEquals(Register.R1, swap.regA)
        assertEquals(Register.R2, swap.regB)

        val asm = swap.toAssembly()
        val expectedAsm =
            listOf(
                "with r1",
                "to r1",
                "xor r2",
                "with r2",
                "to r2",
                "xor r1",
                "with r1",
                "to r1",
                "xor r2",
            )
        assertEquals(expectedAsm, asm)
    }

    @Test
    fun `test 2-register cycle with scratch register`() {
        val moves =
            listOf(
                Register.R1 to Register.R2,
                Register.R2 to Register.R1,
            )
        val result = resolver.resolve(moves, scratchReg = Register.R3)

        assertEquals(3, result.size)
        assertEquals(MoveInstruction.Move(Register.R2, Register.R3), result[0])
        assertEquals(MoveInstruction.Move(Register.R1, Register.R2), result[1])
        assertEquals(MoveInstruction.Move(Register.R3, Register.R1), result[2])

        val asm = result.flatMap { it.toAssembly() }
        assertEquals(listOf("move r3, r2", "move r2, r1", "move r1, r3"), asm)
    }

    @Test
    fun `test 3-register cycle decomposed into XOR swaps`() {
        val moves =
            listOf(
                Register.R1 to Register.R2,
                Register.R2 to Register.R3,
                Register.R3 to Register.R1,
            )
        val result = resolver.resolve(moves)

        // 3-node cycle decomposes into 2 XOR swaps
        assertEquals(2, result.size)
        assertTrue(result[0] is MoveInstruction.XorSwap)
        assertTrue(result[1] is MoveInstruction.XorSwap)

        val swap1 = result[0] as MoveInstruction.XorSwap
        val swap2 = result[1] as MoveInstruction.XorSwap
        assertEquals(Register.R2, swap1.regA)
        assertEquals(Register.R3, swap1.regB)
        assertEquals(Register.R1, swap2.regA)
        assertEquals(Register.R2, swap2.regB)
    }

    @Test
    fun `test chain entering a cycle`() {
        val moves =
            listOf(
                Register.R0 to Register.R1,
                Register.R1 to Register.R2,
                Register.R2 to Register.R1,
            )
        val result = resolver.resolve(moves)

        // The cycle (R1, R2) is resolved via swap, then R0 -> R1 is moved
        assertEquals(2, result.size)
        assertTrue(result[0] is MoveInstruction.XorSwap)
        assertEquals(MoveInstruction.Move(Register.R0, Register.R1), result[1])
    }
}
