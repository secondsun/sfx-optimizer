package dev.secondsun.sfxoptimizer

sealed interface AllocationResult {
    data class Register(
        val register: Constants.Register,
    ) : AllocationResult

    data object Spill : AllocationResult
}

/**
 * Interference graph for graph coloring register allocation.
 * An edge between u and v means their live ranges overlap.
 */
data class InterferenceGraph(
    val nodes: Set<IntervalKey>,
    val intervals: Map<IntervalKey, Interval>,
    val edges: Map<IntervalKey, Set<IntervalKey>>,
    val precolored: Map<IntervalKey, Constants.Register> = emptyMap(),
) {
    fun neighbors(node: IntervalKey): Set<IntervalKey> = edges[node] ?: emptySet()

    fun degree(node: IntervalKey): Int = neighbors(node).size
}

/**
 * Graph-Coloring Register Allocator using the Chaitin-Briggs / Kempe heuristic
 * with SuperFX Address Register Pool, Extended Register Pool, and Caller-Save Avoidance.
 */
class GraphColoringAllocator(
    val registerPool: List<Constants.Register> = defaultExtendedPool,
) {
    companion object {
        // Address Register Pool: R1..R9, R11 (all legal in ldw/stw/ldb/stb (Rm))
        val defaultAddressRegisterPool =
            listOf(
                Constants.Register.R1,
                Constants.Register.R2,
                Constants.Register.R3,
                Constants.Register.R4,
                Constants.Register.R5,
                Constants.Register.R6,
                Constants.Register.R7,
                Constants.Register.R8,
                Constants.Register.R9,
                Constants.Register.R11,
            )

        // Extended pool for scalar variables: Address Register Pool + R12, R13
        val defaultExtendedPool =
            defaultAddressRegisterPool +
                listOf(
                    Constants.Register.R12,
                    Constants.Register.R13,
                )
    }

    private val isCustomPool = registerPool != defaultExtendedPool

    val primaryPool: List<Constants.Register> =
        if (isCustomPool) registerPool else defaultAddressRegisterPool

    val extendedPool: List<Constants.Register> =
        if (isCustomPool) registerPool else defaultExtendedPool

    fun buildInterferenceGraph(
        intervals: Map<IntervalKey, Interval>,
        precolored: Map<IntervalKey, Constants.Register> = emptyMap(),
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
            precolored = precolored,
        )
    }

    fun allocate(
        graph: InterferenceGraph,
        memoryKeys: Set<IntervalKey> = emptySet(),
        avoidMap: Map<IntervalKey, Set<Constants.Register>> = emptyMap(),
    ): Map<IntervalKey, AllocationResult> {
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

        fun effectiveDegree(node: IntervalKey): Pair<Int, Int> {
            val pool = if (node in memoryKeys) primaryPool else extendedPool
            val neighbors = currentEdges[node] ?: emptySet()
            val precoloredCount =
                neighbors
                    .mapNotNull { colors[it] }
                    .filter { it in pool }
                    .toSet()
                    .size
            val uncoloredCount = neighbors.count { it in uncoloredNodes }
            return Pair(precoloredCount + uncoloredCount, pool.size)
        }

        while (uncoloredNodes.isNotEmpty()) {
            val candidate =
                uncoloredNodes.firstOrNull { node ->
                    val (deg, k) = effectiveDegree(node)
                    deg < k
                }
            val chosen =
                candidate
                    ?: uncoloredNodes.maxByOrNull { node ->
                        val (deg, k) = effectiveDegree(node)
                        deg - k
                    }!!
            uncoloredNodes.remove(chosen)
            stack.addLast(chosen)
            for (neighbor in currentEdges[chosen] ?: emptySet()) {
                currentEdges[neighbor]?.remove(chosen)
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

            val pool = if (node in memoryKeys) primaryPool else extendedPool
            val avoid = avoidMap[node] ?: emptySet()

            // First prefer registers not clobbered by calls across which `node` is live
            val preferred = pool.firstOrNull { it !in usedColors && it !in avoid }
            val chosen = preferred ?: pool.firstOrNull { it !in usedColors }

            if (chosen != null) {
                colors[node] = chosen
                result[node] = AllocationResult.Register(chosen)
            } else {
                result[node] = AllocationResult.Spill
            }
        }

        return result
    }
}
