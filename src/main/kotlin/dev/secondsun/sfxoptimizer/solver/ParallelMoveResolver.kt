package dev.secondsun.sfxoptimizer.solver

import dev.secondsun.sfxoptimizer.Constants.Register

/**
 * An instruction emitted by the parallel move solver to shuffle registers.
 */
sealed interface MoveInstruction {
    /** Generates the equivalent ca65 SuperFX assembly instructions. */
    fun toAssembly(): List<String>

    /** Standard register-to-register move: move dst, src */
    data class Move(
        val src: Register,
        val dst: Register,
    ) : MoveInstruction {
        override fun toAssembly(): List<String> = listOf("move ${dst.label}, ${src.label}")
    }

    /**
     * In-place 3-instruction XOR swap between two registers without temporary registers or memory:
     * with rA / to rA / xor rB
     * with rB / to rB / xor rA
     * with rA / to rA / xor rB
     */
    data class XorSwap(
        val regA: Register,
        val regB: Register,
    ) : MoveInstruction {
        override fun toAssembly(): List<String> =
            listOf(
                "with ${regA.label}",
                "to ${regA.label}",
                "xor ${regB.label}",
                "with ${regB.label}",
                "to ${regB.label}",
                "xor ${regA.label}",
                "with ${regA.label}",
                "to ${regA.label}",
                "xor ${regB.label}",
            )
    }
}

/**
 * Resolves parallel copies (e.g. at call sites or phi joins) by sorting
 * non-cyclic moves topologically and resolving cyclic moves via scratch shuttles or XOR swaps.
 */
class ParallelMoveResolver {
    /**
     * Resolves a set of parallel copies (from src to dst).
     *
     * @param moves pairs of (src, dst)
     * @param scratchReg optional free register to shuttle cyclic moves through
     * @return ordered sequence of [MoveInstruction]s implementing the parallel copy
     */
    fun resolve(
        moves: List<Pair<Register, Register>>,
        scratchReg: Register? = null,
    ): List<MoveInstruction> {
        val pending = moves.filter { (src, dst) -> src != dst }.distinct().toMutableList()
        val result = mutableListOf<MoveInstruction>()

        while (pending.isNotEmpty()) {
            // Step 1: Find moves where dst is not the source of any pending move
            val readyMove = pending.find { (_, dst) -> pending.none { it.first == dst } }

            if (readyMove != null) {
                result.add(MoveInstruction.Move(readyMove.first, readyMove.second))
                pending.remove(readyMove)
            } else {
                // Step 2: All remaining moves contain at least one directed cycle.
                // Trace until a previously visited node is encountered.
                val visited = mutableListOf<Register>()
                var curr = pending.first().first
                while (curr !in visited) {
                    visited.add(curr)
                    val nextMove =
                        pending.find { it.first == curr }
                            ?: error("Cycle detection failure for register $curr")
                    curr = nextMove.second
                }

                // The cycle starts at the first occurrence of curr in visited
                val cycleStartIndex = visited.indexOf(curr)
                val cycleNodes = visited.subList(cycleStartIndex, visited.size).toList()

                // Remove only the cycle moves from pending
                val cycleEdges =
                    cycleNodes.indices
                        .map { i -> cycleNodes[i] to cycleNodes[(i + 1) % cycleNodes.size] }
                        .toSet()
                pending.removeAll { it in cycleEdges }

                // Break the cycle
                if (scratchReg != null && scratchReg !in cycleNodes) {
                    val last = cycleNodes.last()
                    result.add(MoveInstruction.Move(last, scratchReg))
                    for (i in cycleNodes.size - 2 downTo 0) {
                        result.add(MoveInstruction.Move(cycleNodes[i], cycleNodes[i + 1]))
                    }
                    result.add(MoveInstruction.Move(scratchReg, cycleNodes.first()))
                } else {
                    // In-place decomposition into XOR swaps:
                    // For i from k - 2 down to 0: Swap(r_i, r_{i+1})
                    for (i in cycleNodes.size - 2 downTo 0) {
                        result.add(MoveInstruction.XorSwap(cycleNodes[i], cycleNodes[i + 1]))
                    }
                }
            }
        }

        return result
    }
}
