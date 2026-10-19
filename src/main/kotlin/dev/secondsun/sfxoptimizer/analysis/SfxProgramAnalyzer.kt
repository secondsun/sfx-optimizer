package dev.secondsun.sfxoptimizer.analysis

import dev.secondsun.retro.util.FunctionSyntaxHelper
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.AllocationResult
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.GraphColoringAllocator
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey
import java.util.Locale

object SfxProgramAnalyzer {
    fun analyze(
        tokenizedFile: TokenizedFile,
        rawLines: List<String> = tokenizedFile.rawLines ?: emptyList(),
        knownMacroLines: Set<Int> = emptySet(),
    ): ProgramAnalysisResult {
        val totalLines = maxOf(tokenizedFile.textLines(), rawLines.size)
        val macroLines = if (knownMacroLines.isNotEmpty()) knownMacroLines else SfxLivenessAnalyzer.findMacroDefinitionLines(rawLines)

        // 1. Discover all functions and sort them bottom-up (callees before callers)
        val discoveredFunctions = SfxLivenessAnalyzer.discoverFunctions(tokenizedFile, rawLines, macroLines)
        val sortedFunctions = SfxLivenessAnalyzer.sortFunctionsBottomUp(discoveredFunctions, tokenizedFile, macroLines)

        val allocator = GraphColoringAllocator()
        val functionAnalyses = mutableListOf<FunctionAnalysis>()
        val allocatedFunctionsMap = mutableMapOf<String, FunctionAnalysis>()

        // 2. Allocate each function in bottom-up order
        for (fn in sortedFunctions) {
            val fnLines = (fn.startLine..fn.endLine).filter { it !in macroLines }.toSet()
            val declared = SfxLivenessAnalyzer.extractDeclaredRegisters(tokenizedFile, fnLines, fn.params, fn.effectiveReturnVar)
            val effects =
                SfxLivenessAnalyzer.analyzeScopeLines(
                    tokenizedFile,
                    fnLines,
                    declared,
                    fn.params,
                    fn.effectiveReturnVar,
                    fn.startLine,
                )
            val loops = SfxLivenessAnalyzer.detectLoops(tokenizedFile, fnLines)
            val intervals = SfxLivenessAnalyzer.buildIntervals(fnLines, effects, declared, loops)

            val avoidMap =
                SfxLivenessAnalyzer.computeCallerSaveAvoidance(
                    tokenizedFile,
                    fnLines,
                    declared,
                    intervals,
                    loops,
                    allocatedFunctionsMap,
                )
            val memoryKeys = effects.values.flatMap { it.memoryAddressKeys }.toSet()

            val precolored = mutableMapOf<IntervalKey, Constants.Register>()
            for ((_, decl) in declared) {
                if (decl.forcedRegister != null) {
                    precolored[IntervalKey.LabelKey(decl.name)] = decl.forcedRegister
                }
            }

            val activeIntervals = intervals.filterValues { it.used() }
            val interference = allocator.buildInterferenceGraph(activeIntervals, precolored)
            val allocations = allocator.allocate(interference, memoryKeys, avoidMap)

            val varToReg = mutableMapOf<String, Constants.Register>()
            for ((key, res) in allocations) {
                if (key is IntervalKey.LabelKey && res is AllocationResult.Register) {
                    varToReg[key.label.lowercase(Locale.ROOT)] = res.register
                }
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

            // Compute clobbered registers
            val clobbered = mutableSetOf<Constants.Register>()
            clobbered.add(Constants.Register.R11) // link #4 clobbers r11
            clobbered.addAll(paramRegs)
            if (returnReg != null) clobbered.add(returnReg)

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

        val mainDeclared = SfxLivenessAnalyzer.extractDeclaredRegisters(tokenizedFile, mainLines, emptyList(), null)
        val mainEffects = SfxLivenessAnalyzer.analyzeScopeLines(tokenizedFile, mainLines, mainDeclared, emptyList(), null, 0)
        val mainLoops = SfxLivenessAnalyzer.detectLoops(tokenizedFile, mainLines)
        val mainIntervals = SfxLivenessAnalyzer.buildIntervals(mainLines, mainEffects, mainDeclared, mainLoops)
        val mainAvoid =
            SfxLivenessAnalyzer.computeCallerSaveAvoidance(
                tokenizedFile,
                mainLines,
                mainDeclared,
                mainIntervals,
                mainLoops,
                allocatedFunctionsMap,
            )
        val mainMemoryKeys = mainEffects.values.flatMap { it.memoryAddressKeys }.toSet()

        val mainPrecolored = mutableMapOf<IntervalKey, Constants.Register>()
        for ((_, decl) in mainDeclared) {
            if (decl.forcedRegister != null) {
                mainPrecolored[IntervalKey.LabelKey(decl.name)] = decl.forcedRegister
            }
        }

        val mainActiveIntervals = mainIntervals.filterValues { it.used() }
        val mainInterference = allocator.buildInterferenceGraph(mainActiveIntervals, mainPrecolored)
        val mainAllocations = allocator.allocate(mainInterference, mainMemoryKeys, mainAvoid)

        val mainVarToReg = mutableMapOf<String, Constants.Register>()
        for ((key, res) in mainAllocations) {
            if (key is IntervalKey.LabelKey && res is AllocationResult.Register) {
                mainVarToReg[key.label.lowercase(Locale.ROOT)] = res.register
            }
        }

        val nonFunctionCodeLines =
            mainLines.filter { lineIdx ->
                rawLines.getOrNull(lineIdx)?.trim()?.let { it.isNotEmpty() && !it.startsWith(";") } == true
            }

        val mainAnalysis =
            if (mainVarToReg.isNotEmpty() || mainIntervals.values.any { it.used() }) {
                MainAnalysis(
                    startLine = nonFunctionCodeLines.minOrNull() ?: 0,
                    endLine = nonFunctionCodeLines.maxOrNull() ?: maxOf(0, totalLines - 1),
                    allocatedMap = mainVarToReg,
                    declaredRegisters = mainDeclared,
                    intervals = mainIntervals,
                    loops = mainLoops,
                    effects = mainEffects,
                )
            } else {
                null
            }

        // 4. Combined intervals and allocated map
        val allIntervals = mutableListOf<Interval>()
        for (fn in functionAnalyses) {
            allIntervals.addAll(fn.intervals.values.filter { it.used() })
        }
        if (mainAnalysis != null) {
            allIntervals.addAll(mainAnalysis.intervals.values.filter { it.used() })
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
        )
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
