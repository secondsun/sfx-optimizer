package dev.secondsun.sfxoptimizer.analysis

import dev.secondsun.retro.util.FunctionSyntaxHelper
import dev.secondsun.retro.util.WorkspaceSnapshot
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.AllocationResult
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.GraphColoringAllocator
import dev.secondsun.sfxoptimizer.InterferenceGraph
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object SfxProgramAnalyzer {
    @JvmStatic
    fun analyze(
        tokenizedFile: TokenizedFile,
        snapshot: WorkspaceSnapshot?,
    ): ProgramAnalysisResult =
        analyze(
            tokenizedFile = tokenizedFile,
            rawLines = tokenizedFile.rawLines ?: emptyList(),
            knownMacroLines = emptySet(),
            snapshot = snapshot,
        )

    @JvmStatic
    @JvmOverloads
    fun analyze(
        tokenizedFile: TokenizedFile,
        rawLines: List<String> = tokenizedFile.rawLines ?: emptyList(),
        knownMacroLines: Set<Int> = emptySet(),
        snapshot: WorkspaceSnapshot? = null,
    ): ProgramAnalysisResult {
        val totalLines = maxOf(tokenizedFile.textLines(), rawLines.size)
        val macroLines =
            if (knownMacroLines.isNotEmpty()) {
                knownMacroLines
            } else {
                SfxLivenessAnalyzer.findMacroDefinitionLines(rawLines)
            }

        val warnings = mutableListOf<String>()
        val blockIdCounter = AtomicInteger(0)

        // 1. Discover all functions ([inline] function ... endfunction) and sort bottom-up
        val discoveredFunctions = SfxLivenessAnalyzer.discoverFunctions(tokenizedFile, rawLines, macroLines)
        val sortedFunctions = SfxLivenessAnalyzer.sortFunctionsBottomUp(discoveredFunctions, tokenizedFile, macroLines)

        val allocator = GraphColoringAllocator()
        val functionAnalyses = mutableListOf<FunctionAnalysis>()
        val allocatedFunctionsMap = mutableMapOf<String, FunctionAnalysis>()

        // 2. Allocate each function in bottom-up order using ScopeCfg natural loops
        for (fn in sortedFunctions) {
            val fnLines = (fn.startLine..fn.endLine).filter { it !in macroLines }.toSet()
            val cfg = SfxCfgBuilder.buildScopeCfg(tokenizedFile, fn.name, fnLines, blockIdCounter)
            val loops = cfg.loops.map { it.lineRange }

            val declared =
                SfxLivenessAnalyzer.extractDeclaredRegisters(
                    tokenizedFile,
                    fnLines,
                    fn.params,
                    fn.effectiveReturnVar,
                )
            val effects =
                SfxLivenessAnalyzer.analyzeScopeLines(
                    file = tokenizedFile,
                    scopeLines = fnLines,
                    declaredRegisters = declared,
                    params = fn.params,
                    effectiveReturnVar = fn.effectiveReturnVar,
                    fnStartLine = fn.startLine,
                    snapshot = snapshot,
                    warnings = warnings,
                )
            val intervals = SfxLivenessAnalyzer.buildIntervals(fnLines, effects, declared, loops)
            val activeIntervals = intervals.filterValues { it.used() }
            val cfgLiveness =
                SfxLivenessAnalyzer.computeCfgLiveness(
                    file = tokenizedFile,
                    cfg = cfg,
                    effects = effects,
                    activeIntervals = activeIntervals,
                    declaredRegisters = declared,
                    fnStartLine = fn.startLine,
                    effectiveReturnVar = fn.effectiveReturnVar,
                )

            val avoidMap =
                SfxLivenessAnalyzer.computeCallerSaveAvoidance(
                    tokenizedFile,
                    fnLines,
                    declared,
                    intervals,
                    loops,
                    allocatedFunctionsMap,
                    cfgLiveness.liveAfterByLine,
                )
            val memoryKeys = effects.values.flatMap { it.memoryAddressKeys }.toSet()

            val precolored = mutableMapOf<IntervalKey, Constants.Register>()
            for ((_, decl) in declared) {
                if (decl.forcedRegister != null) {
                    precolored[IntervalKey.LabelKey(decl.name)] = decl.forcedRegister
                }
            }

            val interference =
                InterferenceGraph(
                    nodes = activeIntervals.keys,
                    intervals = activeIntervals,
                    edges = cfgLiveness.interferenceEdges,
                    precolored = precolored,
                )
            val paramKeepKeys =
                fn.params
                    .map { it.lowercase(Locale.ROOT) }
                    .filter { !Constants.isRegister(it) }
                    .map { IntervalKey.LabelKey(it) }
                    .toSet()
            val explicitlyUsedKeys =
                effects.values
                    .flatMap { it.explicitReads.values + it.explicitWrites.values }
                    .toSet()
            val (allocations, scratchRegs) =
                allocateWithSpillScratch(
                    allocator = allocator,
                    interference = interference,
                    memoryKeys = memoryKeys,
                    avoidMap = avoidMap,
                    preferKeepKeys = paramKeepKeys,
                    explicitlyUsedKeys = explicitlyUsedKeys,
                    activeIntervals = activeIntervals,
                )

            val varToReg = mutableMapOf<String, Constants.Register>()
            val spilledVars = linkedSetOf<String>()
            for ((key, res) in allocations) {
                if (key is IntervalKey.LabelKey) {
                    when (res) {
                        is AllocationResult.Register -> varToReg[key.label.lowercase(Locale.ROOT)] = res.register
                        AllocationResult.Spill -> {
                            if (key in explicitlyUsedKeys) {
                                spilledVars.add(key.label.lowercase(Locale.ROOT))
                            }
                        }
                    }
                }
            }
            if (spilledVars.isNotEmpty()) {
                warnings.add(
                    "Function '${fn.name}' spilled ${spilledVars.size} variable(s) to R10 stack: " +
                        spilledVars.joinToString(", "),
                )
            }

            val paramRegs =
                fn.params.map { p ->
                    val lower = p.lowercase(Locale.ROOT)
                    Constants.register(lower)
                        ?: varToReg[lower]
                        ?: error("Unallocated parameter '$p' in function '${fn.name}'")
                }

            val returnReg =
                fn.effectiveReturnVar?.let { ret ->
                    val lower = ret.lowercase(Locale.ROOT)
                    Constants.register(lower) ?: varToReg[lower]
                }

            val clobbered = mutableSetOf<Constants.Register>()
            if (!fn.isInline) {
                clobbered.add(Constants.Register.R11) // link #4 clobbers r11
            }
            clobbered.addAll(paramRegs)
            if (returnReg != null) clobbered.add(returnReg)
            if (spilledVars.isNotEmpty()) {
                clobbered.addAll(scratchRegs)
            }

            for (lineIdx in fnLines) {
                val eff = effects[lineIdx] ?: continue
                for (wKey in eff.explicitWrites.values + eff.implicitWrites) {
                    resolveKeyToRegister(wKey, varToReg)?.let { clobbered.add(it) }
                }
                val codeTokens = SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, lineIdx)
                val (_, bodyTokens) = SfxLivenessAnalyzer.splitLabelPrefix(codeTokens)
                if (FunctionSyntaxHelper.isCall(bodyTokens)) {
                    val call = SfxLivenessAnalyzer.parseCallStatement(bodyTokens)
                    if (call != null) {
                        val callee = allocatedFunctionsMap[call.target.lowercase(Locale.ROOT)]
                        if (callee != null) {
                            clobbered.addAll(callee.clobberedRegisters)
                        }
                    }
                }
            }

            val analysis =
                FunctionAnalysis(
                    name = fn.name,
                    startLine = fn.startLine,
                    endLine = fn.endLine,
                    params = fn.params,
                    effectiveReturnVar = fn.effectiveReturnVar,
                    paramRegs = paramRegs,
                    returnReg = returnReg,
                    clobberedRegisters = clobbered,
                    allocatedMap = varToReg,
                    declaredRegisters = declared,
                    intervals = intervals,
                    loops = loops,
                    effects = effects,
                    cfg = cfg,
                    interferences = interference.edges,
                    liveAfterByLine = cfgLiveness.liveAfterByLine,
                    isInline = fn.isInline,
                    spilledVariables = spilledVars,
                    scratchRegisters = if (spilledVars.isNotEmpty()) scratchRegs else emptyList(),
                )
            functionAnalyses.add(analysis)
            allocatedFunctionsMap[fn.name.lowercase(Locale.ROOT)] = analysis
        }

        // 3. Process top-level non-function, non-macro code
        val allFnLines = discoveredFunctions.flatMap { it.startLine..it.endLine }.toSet()
        val mainLines =
            (0 until totalLines)
                .filter { it !in allFnLines && it !in macroLines }
                .toSet()

        val mainCfg = SfxCfgBuilder.buildScopeCfg(tokenizedFile, "(main)", mainLines, blockIdCounter)
        val mainLoops = mainCfg.loops.map { it.lineRange }
        val mainDeclared = SfxLivenessAnalyzer.extractDeclaredRegisters(tokenizedFile, mainLines, emptyList(), null)
        val mainEffects =
            SfxLivenessAnalyzer.analyzeScopeLines(
                file = tokenizedFile,
                scopeLines = mainLines,
                declaredRegisters = mainDeclared,
                params = emptyList(),
                effectiveReturnVar = null,
                fnStartLine = 0,
                snapshot = snapshot,
                warnings = warnings,
            )
        val mainIntervals = SfxLivenessAnalyzer.buildIntervals(mainLines, mainEffects, mainDeclared, mainLoops)
        val mainActiveIntervals = mainIntervals.filterValues { it.used() }
        val mainCfgLiveness =
            SfxLivenessAnalyzer.computeCfgLiveness(
                file = tokenizedFile,
                cfg = mainCfg,
                effects = mainEffects,
                activeIntervals = mainActiveIntervals,
                declaredRegisters = mainDeclared,
            )
        val mainAvoid =
            SfxLivenessAnalyzer.computeCallerSaveAvoidance(
                tokenizedFile,
                mainLines,
                mainDeclared,
                mainIntervals,
                mainLoops,
                allocatedFunctionsMap,
                mainCfgLiveness.liveAfterByLine,
            )
        val mainMemoryKeys = mainEffects.values.flatMap { it.memoryAddressKeys }.toSet()

        val mainPrecolored = mutableMapOf<IntervalKey, Constants.Register>()
        for ((_, decl) in mainDeclared) {
            if (decl.forcedRegister != null) {
                mainPrecolored[IntervalKey.LabelKey(decl.name)] = decl.forcedRegister
            }
        }

        val mainInterference =
            InterferenceGraph(
                nodes = mainActiveIntervals.keys,
                intervals = mainActiveIntervals,
                edges = mainCfgLiveness.interferenceEdges,
                precolored = mainPrecolored,
            )
        val mainExplicitlyUsedKeys =
            mainEffects.values
                .flatMap { it.explicitReads.values + it.explicitWrites.values }
                .toSet()
        val (mainAllocations, mainScratchRegs) =
            allocateWithSpillScratch(
                allocator = allocator,
                interference = mainInterference,
                memoryKeys = mainMemoryKeys,
                avoidMap = mainAvoid,
                preferKeepKeys = emptySet(),
                explicitlyUsedKeys = mainExplicitlyUsedKeys,
                activeIntervals = mainActiveIntervals,
            )

        val mainVarToReg = mutableMapOf<String, Constants.Register>()
        val mainSpilledVars = linkedSetOf<String>()
        for ((key, res) in mainAllocations) {
            if (key is IntervalKey.LabelKey) {
                when (res) {
                    is AllocationResult.Register -> mainVarToReg[key.label.lowercase(Locale.ROOT)] = res.register
                    AllocationResult.Spill -> {
                        if (key in mainExplicitlyUsedKeys) {
                            mainSpilledVars.add(key.label.lowercase(Locale.ROOT))
                        }
                    }
                }
            }
        }
        if (mainSpilledVars.isNotEmpty()) {
            warnings.add(
                "Scope '(main)' spilled ${mainSpilledVars.size} variable(s) to R10 stack: " +
                    mainSpilledVars.joinToString(", "),
            )
        }

        val nonFunctionCodeLines =
            mainLines.filter { lineIdx ->
                rawLines.getOrNull(lineIdx)?.trim()?.let { it.isNotEmpty() && !it.startsWith(";") } == true
            }

        val mainAnalysis =
            if (mainVarToReg.isNotEmpty() ||
                mainSpilledVars.isNotEmpty() ||
                mainIntervals.values.any { it.used() } ||
                mainCfg.blocks.isNotEmpty()
            ) {
                // Only emit (main) when actual executable instructions or variables exist outside functions
                if (mainVarToReg.isNotEmpty() || mainSpilledVars.isNotEmpty() || mainIntervals.values.any { it.used() }) {
                    MainAnalysis(
                        startLine = nonFunctionCodeLines.minOrNull() ?: 0,
                        endLine = nonFunctionCodeLines.maxOrNull() ?: maxOf(0, totalLines - 1),
                        allocatedMap = mainVarToReg,
                        declaredRegisters = mainDeclared,
                        intervals = mainIntervals,
                        loops = mainLoops,
                        effects = mainEffects,
                        cfg = mainCfg,
                        interferences = mainInterference.edges,
                        liveAfterByLine = mainCfgLiveness.liveAfterByLine,
                        spilledVariables = mainSpilledVars,
                        scratchRegisters = if (mainSpilledVars.isNotEmpty()) mainScratchRegs else emptyList(),
                    )
                } else {
                    null
                }
            } else {
                null
            }

        // 4. Combined intervals, blocks (ordered by source appearance), and allocated map
        val allIntervals = mutableListOf<Interval>()
        for (fn in functionAnalyses) {
            allIntervals.addAll(fn.intervals.values.filter { it.used() })
        }
        if (mainAnalysis != null) {
            allIntervals.addAll(mainAnalysis.intervals.values.filter { it.used() })
        }

        val allBlocks = mutableListOf<CfgBlock>()
        if (mainAnalysis != null) {
            allBlocks.addAll(mainAnalysis.cfg.blocks)
        }
        for (fn in functionAnalyses.sortedBy { it.startLine }) {
            allBlocks.addAll(fn.cfg.blocks)
        }

        val overallAllocatedMap = mutableMapOf<String, Constants.Register>()
        for (fn in functionAnalyses) {
            overallAllocatedMap.putAll(fn.allocatedMap)
        }
        if (mainAnalysis != null) {
            overallAllocatedMap.putAll(mainAnalysis.allocatedMap)
        }

        // 5. Calculate maximum register pressure across lines
        var maxPressure = 0
        for (line in 0..totalLines) {
            val count = allIntervals.count { it.start <= line && it.end >= line }
            if (count > maxPressure) {
                maxPressure = count
            }
        }

        val pressureStatus =
            when {
                maxPressure <= 8 -> "OPTIMAL"
                maxPressure <= 14 -> "HIGH"
                else -> "SPILL_RISK"
            }

        return ProgramAnalysisResult(
            tokenizedFile = tokenizedFile,
            totalLines = totalLines,
            functions = functionAnalyses,
            main = mainAnalysis,
            overallAllocatedMap = overallAllocatedMap,
            allIntervals = allIntervals,
            maxRegisterPressure = maxPressure,
            pressureStatus = pressureStatus,
            blocks = allBlocks,
            warnings = warnings,
        )
    }

    private fun allocateWithSpillScratch(
        allocator: GraphColoringAllocator,
        interference: InterferenceGraph,
        memoryKeys: Set<IntervalKey>,
        avoidMap: Map<IntervalKey, Set<Constants.Register>>,
        preferKeepKeys: Set<IntervalKey>,
        explicitlyUsedKeys: Set<IntervalKey>,
        activeIntervals: Map<IntervalKey, Interval>,
    ): Pair<Map<IntervalKey, AllocationResult>, List<Constants.Register>> {
        val initial = allocator.allocate(interference, memoryKeys, avoidMap, preferKeepKeys)
        val hasRealSpill =
            initial.any { (k, res) ->
                k is IntervalKey.LabelKey &&
                    res is AllocationResult.Spill &&
                    k in explicitlyUsedKeys
            }
        if (!hasRealSpill) {
            return initial to emptyList()
        }

        // Reserve 2 scratch registers from the address-capable pool (R1..R9, R11) that are not
        // precolored or directly referenced as raw physical registers in this scope.
        val reservedByUser =
            interference.precolored.values.toSet() +
                activeIntervals.keys
                    .filterIsInstance<IntervalKey.RegisterKey>()
                    .map { it.register }
                    .toSet()
        val scratchPreference =
            listOf(
                Constants.Register.R9,
                Constants.Register.R8,
                Constants.Register.R7,
                Constants.Register.R5,
                Constants.Register.R3,
                Constants.Register.R2,
                Constants.Register.R1,
                Constants.Register.R11,
                Constants.Register.R4,
                Constants.Register.R6,
            )
        val chosenScratch =
            scratchPreference
                .filter { it !in reservedByUser }
                .take(2)
                .ifEmpty { scratchPreference.take(2) }
        val reducedPool = allocator.registerPool.filter { it !in chosenScratch }
        val spillAllocator = GraphColoringAllocator(registerPool = reducedPool)
        val reallocated = spillAllocator.allocate(interference, memoryKeys, avoidMap, preferKeepKeys)
        return reallocated to chosenScratch
    }

    private fun resolveKeyToRegister(
        key: IntervalKey,
        varToReg: Map<String, Constants.Register>,
    ): Constants.Register? =
        when (key) {
            is IntervalKey.RegisterKey -> key.register
            is IntervalKey.LabelKey -> varToReg[key.label.lowercase(Locale.ROOT)]
        }
}
