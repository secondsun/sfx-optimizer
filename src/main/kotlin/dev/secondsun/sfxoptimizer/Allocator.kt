package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenAttribute
import dev.secondsun.retro.util.TokenType
import dev.secondsun.sfxoptimizer.Constants.Register.*
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph
import dev.secondsun.sfxoptimizer.graphnode.CodeNode

sealed interface AllocationResult {
    data class Register(val register: Constants.Register) : AllocationResult
    data object Spill : AllocationResult
}

data class AllocationContext(
    val registerPool: MutableList<Constants.Register> = mutableListOf(
        R0, R1, R2, R3, R4, R5, R6, R7, R8, R9, R11, R12, R13, R14
    )
) {
    /**
     * Removes a register or spills
     */
    fun allocate(): AllocationResult {
        return if (registerPool.isEmpty()) {
            AllocationResult.Spill
        } else {
            val register = registerPool.removeFirst()
            AllocationResult.Register(register)
        }
    }

    /**
     * Removes a register from the pool
     */
    fun reserve(register: Constants.Register) {
        registerPool.remove(register)
    }

    /**
     * Returns a register to the pool
     */
    fun free(register: Constants.Register) {
        registerPool.add(register)
    }
}

/**
 * Interference graph for graph coloring register allocation.
 * An edge between u and v means their live ranges overlap.
 */
data class InterferenceGraph(
    val nodes: Set<IntervalKey>,
    val intervals: Map<IntervalKey, Interval>,
    val edges: Map<IntervalKey, Set<IntervalKey>>,
    val precolored: Map<IntervalKey, Constants.Register> = emptyMap()
) {
    fun neighbors(node: IntervalKey): Set<IntervalKey> = edges[node] ?: emptySet()
    fun degree(node: IntervalKey): Int = neighbors(node).size
}

/**
 * Graph-Coloring Register Allocator using the Chaitin-Briggs / Kempe heuristic.
 */
class GraphColoringAllocator(
    val registerPool: List<Constants.Register> = listOf(
        R0, R1, R2, R3, R4, R5, R6, R7, R8, R9, R11, R12, R13, R14
    )
) {
    fun buildInterferenceGraph(
        intervals: Map<IntervalKey, Interval>,
        precolored: Map<IntervalKey, Constants.Register> = emptyMap()
    ): InterferenceGraph {
        val edges = mutableMapOf<IntervalKey, MutableSet<IntervalKey>>()
        for (node in intervals.keys) {
            edges[node] = mutableSetOf()
        }

        val keys = intervals.keys.toList()
        for (i in 0 until keys.size) {
            val u = keys[i]
            val intU = intervals[u] ?: continue
            for (j in i + 1 until keys.size) {
                val v = keys[j]
                val intV = intervals[v] ?: continue

                // Two intervals interfere if their live ranges overlap
                if (intU.start <= intV.end && intV.start <= intU.end) {
                    edges[u]?.add(v)
                    edges[v]?.add(u)
                }
            }
        }

        return InterferenceGraph(
            nodes = intervals.keys,
            intervals = intervals,
            edges = edges,
            precolored = precolored
        )
    }

    fun allocate(graph: InterferenceGraph): Map<IntervalKey, AllocationResult> {
        val k = registerPool.size
        val colors = mutableMapOf<IntervalKey, Constants.Register>()

        // 1. Fixed pre-colored nodes
        for ((node, reg) in graph.precolored) {
            colors[node] = reg
        }
        for (node in graph.nodes) {
            if (node is IntervalKey.RegisterKey) {
                colors[node] = node.register
            }
        }

        // 2. Chaitin-Briggs / Kempe simplification phase
        val uncoloredNodes = graph.nodes.filter { it !in colors }.toMutableSet()
        val currentEdges = mutableMapOf<IntervalKey, MutableSet<IntervalKey>>()
        for (node in graph.nodes) {
            currentEdges[node] = graph.neighbors(node).toMutableSet()
        }

        val stack = ArrayDeque<IntervalKey>()

        while (uncoloredNodes.isNotEmpty()) {
            // Find an uncolored node with degree < k
            val candidate = uncoloredNodes.firstOrNull { (currentEdges[it]?.size ?: 0) < k }
            if (candidate != null) {
                uncoloredNodes.remove(candidate)
                stack.addLast(candidate)
                for (neighbor in currentEdges[candidate] ?: emptySet()) {
                    currentEdges[neighbor]?.remove(candidate)
                }
            } else {
                // Potential spill: pick node with highest degree
                val spillNode = uncoloredNodes.maxByOrNull { currentEdges[it]?.size ?: 0 }!!
                uncoloredNodes.remove(spillNode)
                stack.addLast(spillNode)
                for (neighbor in currentEdges[spillNode] ?: emptySet()) {
                    currentEdges[neighbor]?.remove(spillNode)
                }
            }
        }

        // 3. Selection / coloring phase
        val result = mutableMapOf<IntervalKey, AllocationResult>()
        for ((node, reg) in colors) {
            result[node] = AllocationResult.Register(reg)
        }

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val usedColors = mutableSetOf<Constants.Register>()

            for (neighbor in graph.neighbors(node)) {
                val neighborColor = colors[neighbor]
                if (neighborColor != null) {
                    usedColors.add(neighborColor)
                }
            }

            val availableColor = registerPool.firstOrNull { it !in usedColors }
            if (availableColor != null) {
                colors[node] = availableColor
                result[node] = AllocationResult.Register(availableColor)
            } else {
                result[node] = AllocationResult.Spill
            }
        }

        return result
    }
}

/**
 * Report containing allocation results across all functions and the main body.
 */
data class AllocationReport(
    val functionAllocations: Map<String, Map<IntervalKey, AllocationResult>>,
    val mainAllocation: Map<IntervalKey, AllocationResult>
)

/**
 * Collect merged intervals for a function.
 */
fun collectFunctionIntervals(function: CodeNode.FunctionStart): Map<IntervalKey, Interval> {
    val keys = mutableSetOf<IntervalKey>()
    function.functionBody.traverse { node ->
        if (node is CodeNode.CodeBlock) {
            keys.addAll(node.intervals.keys)
        }
    }
    for (param in function.params) {
        keys.add(IntervalKey.LabelKey(param.text()))
    }

    val result = mutableMapOf<IntervalKey, Interval>()
    for (key in keys) {
        val interval = function.functionBody.startNode.intervals(key)
        if (interval != null) {
            if (key is IntervalKey.LabelKey && function.params.any { it.text() == key.label }) {
                if (interval.start > function.location.line) {
                    interval.start = function.location.line
                }
            }
            result[key] = interval
        }
    }
    return result
}

/**
 * Collect merged intervals for the main program body.
 */
fun collectMainIntervals(graph: CodeGraph): Map<IntervalKey, Interval> {
    val keys = mutableSetOf<IntervalKey>()
    graph.traverse { node ->
        if (node is CodeNode.CodeBlock) {
            keys.addAll(node.intervals.keys)
        }
    }
    val result = mutableMapOf<IntervalKey, Interval>()
    for (key in keys) {
        val interval = graph.startNode.intervals(key)
        if (interval != null) {
            result[key] = interval
        }
    }
    return result
}

/**
 * Apply resolved register allocations to tokens in a CodeGraph.
 */
fun applyAllocationsToGraph(graph: CodeGraph, allocations: Map<IntervalKey, AllocationResult>) {
    graph.traverse { node ->
        if (node is CodeNode.CodeBlock) {
            for (line in node.lines) {
                for (token in line.tokens) {
                    val labelKey = IntervalKey.LabelKey(token.text())
                    val alloc = allocations[labelKey]
                    if (alloc is AllocationResult.Register) {
                        token.addAttribute(TokenAttribute.REGISTER_LABEL)
                        token.addMetadata(TokenAttribute.REGISTER_LABEL, alloc.register)
                    }
                }
            }
        }
    }
}

/**
 * Attach registers to register labels using graph coloring.
 */
fun allocate(program: CodeGraph): AllocationReport {
    val allocator = GraphColoringAllocator()

    // 1. Build function stack
    val graphStack = mutableListOf<CodeNode.FunctionStart>()
    fillGraphStack(program, graphStack)

    // Also include any functions discovered during traversal
    program.traverse { node ->
        if (node is CodeNode.CallBlock && !graphStack.contains(node.function)) {
            graphStack.add(node.function)
        } else if (node is CodeNode.FunctionStart && !graphStack.contains(node)) {
            graphStack.add(node)
        }
    }

    // 2. Allocate function locals using graph coloring
    val functionAllocations = mutableMapOf<String, Map<IntervalKey, AllocationResult>>()
    graphStack.asReversed().forEach { function ->
        val intervals = collectFunctionIntervals(function)
        val interferenceGraph = allocator.buildInterferenceGraph(intervals)
        val allocations = allocator.allocate(interferenceGraph)
        applyAllocationsToGraph(function.functionBody, allocations)
        functionAllocations[function.functionName] = allocations
    }

    // 3. Allocate main body locals
    val mainIntervals = collectMainIntervals(program)
    val mainInterference = allocator.buildInterferenceGraph(mainIntervals)
    val mainAllocations = allocator.allocate(mainInterference)
    applyAllocationsToGraph(program, mainAllocations)

    return AllocationReport(functionAllocations, mainAllocations)
}

/**
 * Fills the @param graphStack with data from @param program
 */
private fun fillGraphStack(
    program: CodeGraph,
    graphStack: MutableList<CodeNode.FunctionStart>
) {
    program.traverse { node ->
        if (node is CodeNode.CallBlock) {
            if (!graphStack.contains(node.function)) {
                graphStack.add(node.function)
                fillGraphStack(node.function.functionBody, graphStack)
            }
        }
    }
}

fun peekToken(tokens: List<Token>, i: Int): Token? {
    return if (i < tokens.size) {
        tokens[i]
    } else {
        null
    }
}

/**
 * Return true if token is an argument literal for an instruction.
 * This is a signal that the token should be replaced with a register.
 */
fun isArgument(token: Token): Boolean {
    return token.type == TokenType.TOK_IDENT
}
