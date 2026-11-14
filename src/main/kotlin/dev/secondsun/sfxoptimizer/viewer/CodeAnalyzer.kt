package dev.secondsun.sfxoptimizer.viewer

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.sfx.SuperFxIsa
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey
import dev.secondsun.sfxoptimizer.analysis.CfgBlock
import dev.secondsun.sfxoptimizer.analysis.LineEffect
import dev.secondsun.sfxoptimizer.analysis.SfxLivenessAnalyzer
import dev.secondsun.sfxoptimizer.analysis.SfxProgramAnalyzer
import java.net.URI
import java.util.Locale

class CodeAnalyzer {
    private val pseudoKeywords =
        setOf(
            "inline",
            "function",
            "endfunction",
            "call",
            "register",
            "for",
            "forr",
            "endfor",
            "return",
            "stop",
            "to",
            "from",
            "with",
            "export",
            "import",
            "global",
            "byte",
            "word",
            "res",
            "org",
            "segment",
            "scope",
            "proc",
            "endproc",
        )

    fun analyze(
        source: String,
        fileName: String = "source.sgs",
    ): FileAnalysisResultDto {
        val scanner = CA65Scanner()
        val tokenizedFile = scanner.tokenize(source, URI.create("./$fileName"))
        val rawLines = source.lines()
        val totalLines = rawLines.size

        val analysis = SfxProgramAnalyzer.analyze(tokenizedFile, rawLines)

        val functionScopeDtos = mutableListOf<FunctionScopeDto>()
        val allBlockDtos = mutableListOf<CodeBlockDto>()

        if (analysis.main != null) {
            val main = analysis.main
            val mainAllocatedMap = main.allocatedMap.mapValues { (_, v) -> v.name }
            val mainIntervalsMap = main.intervals.filterValues { it.used() }

            val mainIntervalDtos =
                mainIntervalsMap.values
                    .map { interval ->
                        createIntervalDto(
                            interval = interval,
                            functionScope = "(main)",
                            tokenizedFile = tokenizedFile,
                            allocatedMap = mainAllocatedMap,
                            interference = (main.interferences[interval.key] ?: emptySet()).map { keyToString(it) }.sorted(),
                        )
                    }.sortedWith(compareBy({ !it.isRegister }, { it.startLine }, { it.keyName }))

            for (block in main.cfg.blocks) {
                allBlockDtos.add(
                    createBlockDto(
                        block = block,
                        functionScope = "(main)",
                        tokenizedFile = tokenizedFile,
                        activeIntervals = mainIntervalsMap,
                        allocatedMap = mainAllocatedMap,
                        interferences = main.interferences,
                    ),
                )
            }

            functionScopeDtos.add(
                FunctionScopeDto(
                    name = "(main)",
                    startLine = main.startLine,
                    endLine = main.endLine,
                    intervals = mainIntervalDtos,
                    allocatedMap = mainAllocatedMap,
                ),
            )
        }

        for (fn in analysis.functions.sortedBy { it.startLine }) {
            val fnAllocatedMap = fn.allocatedMap.mapValues { (_, v) -> v.name }
            val fnIntervalsMap = fn.intervals.filterValues { it.used() }

            val fnIntervalDtos =
                fnIntervalsMap.values
                    .map { interval ->
                        createIntervalDto(
                            interval = interval,
                            functionScope = fn.name,
                            tokenizedFile = tokenizedFile,
                            allocatedMap = fnAllocatedMap,
                            interference = (fn.interferences[interval.key] ?: emptySet()).map { keyToString(it) }.sorted(),
                        )
                    }.sortedWith(compareBy({ !it.isRegister }, { it.startLine }, { it.keyName }))

            for (block in fn.cfg.blocks) {
                allBlockDtos.add(
                    createBlockDto(
                        block = block,
                        functionScope = fn.name,
                        tokenizedFile = tokenizedFile,
                        activeIntervals = fnIntervalsMap,
                        allocatedMap = fnAllocatedMap,
                        interferences = fn.interferences,
                    ),
                )
            }

            functionScopeDtos.add(
                FunctionScopeDto(
                    name = fn.name,
                    startLine = fn.startLine,
                    endLine = fn.endLine,
                    intervals = fnIntervalDtos,
                    allocatedMap = fnAllocatedMap,
                ),
            )
        }

        val allIntervalDtos = functionScopeDtos.flatMap { it.intervals }
        val overallAllocatedMap = mutableMapOf<String, String>()
        for (scope in functionScopeDtos) {
            overallAllocatedMap.putAll(scope.allocatedMap)
        }

        val allLineEffects = mutableMapOf<Int, LineEffect>()
        for (fn in analysis.functions) {
            allLineEffects.putAll(fn.effects)
        }
        if (analysis.main != null) {
            allLineEffects.putAll(analysis.main.effects)
        }

        val lineDtos =
            (0 until totalLines).map { lineIdx ->
                val lineText = if (lineIdx < rawLines.size) rawLines[lineIdx] else ""
                val tokens = tokenizedFile.getLineTokens(lineIdx) ?: emptyList()

                val activeScope =
                    functionScopeDtos.firstOrNull { it.name != "(main)" && lineIdx in it.startLine..it.endLine }
                        ?: functionScopeDtos.firstOrNull { it.name == "(main)" }

                val effect = allLineEffects[lineIdx]
                val tokenDtos =
                    tokens.map { token ->
                        classifyToken(token, lineIdx, activeScope, effect)
                    }

                SourceLineDto(
                    lineNumber = lineIdx,
                    text = lineText,
                    tokens = tokenDtos,
                )
            }

        return FileAnalysisResultDto(
            fileName = fileName,
            totalLines = totalLines,
            lines = lineDtos,
            blocks = allBlockDtos,
            intervals = allIntervalDtos,
            allocatedMap = overallAllocatedMap,
            functions = functionScopeDtos,
        )
    }

    private fun createIntervalDto(
        interval: Interval,
        functionScope: String,
        tokenizedFile: TokenizedFile,
        allocatedMap: Map<String, String>,
        interference: List<String>,
    ): IntervalSpanDto {
        val keyName = keyToString(interval.key)
        val isReg = interval.key is IntervalKey.RegisterKey
        val regName = (interval.key as? IntervalKey.RegisterKey)?.register?.name
        val allocReg = if (isReg) regName else allocatedMap[keyName.lowercase(Locale.ROOT)] ?: allocatedMap[keyName]

        val readsLoc =
            interval.reads.mapNotNull { lineNum ->
                findTokenLocationOnLine(tokenizedFile, lineNum, keyName, isReg)
            }
        val writesLoc =
            interval.writes.mapNotNull { lineNum ->
                findTokenLocationOnLine(tokenizedFile, lineNum, keyName, isReg)
            }

        return IntervalSpanDto(
            keyName = keyName,
            functionScope = functionScope,
            isRegister = isReg,
            registerName = regName,
            startLine = interval.start,
            endLine = interval.end,
            reads = readsLoc,
            writes = writesLoc,
            interferences = interference,
            allocatedRegister = allocReg,
            isSpill = allocReg == "SPILL",
        )
    }

    private fun createBlockDto(
        block: CfgBlock,
        functionScope: String,
        tokenizedFile: TokenizedFile,
        activeIntervals: Map<IntervalKey, Interval>,
        allocatedMap: Map<String, String>,
        interferences: Map<IntervalKey, Set<IntervalKey>>,
    ): CodeBlockDto {
        val lineSet = block.lineIndices.toSet()
        val bIntervals =
            activeIntervals.values
                .filter { intv ->
                    intv.start <= block.endLine && intv.end >= block.startLine
                }.map { bInt ->
                    val keyName = keyToString(bInt.key)
                    val isReg = bInt.key is IntervalKey.RegisterKey
                    val regName = (bInt.key as? IntervalKey.RegisterKey)?.register?.name
                    val allocReg = if (isReg) regName else allocatedMap[keyName.lowercase(Locale.ROOT)] ?: allocatedMap[keyName]
                    IntervalSpanDto(
                        keyName = keyName,
                        functionScope = functionScope,
                        isRegister = isReg,
                        registerName = regName,
                        startLine = maxOf(bInt.start, block.startLine),
                        endLine = minOf(bInt.end, block.endLine),
                        reads =
                            bInt.reads.filter { it in lineSet }.mapNotNull {
                                findTokenLocationOnLine(
                                    tokenizedFile,
                                    it,
                                    keyName,
                                    isReg,
                                )
                            },
                        writes =
                            bInt.writes.filter { it in lineSet }.mapNotNull {
                                findTokenLocationOnLine(
                                    tokenizedFile,
                                    it,
                                    keyName,
                                    isReg,
                                )
                            },
                        interferences = (interferences[bInt.key] ?: emptySet()).map { keyToString(it) }.sorted(),
                        allocatedRegister = allocReg,
                        isSpill = allocReg == "SPILL",
                    )
                }

        return CodeBlockDto(
            id = block.id,
            startLine = block.startLine,
            endLine = block.endLine,
            entrances = block.entrances,
            exits = block.exits,
            intervals = bIntervals,
        )
    }

    private fun classifyToken(
        token: Token,
        lineIdx: Int,
        activeScope: FunctionScopeDto?,
        lineEffect: LineEffect?,
    ): SourceTokenDto {
        val text = token.text()
        val lower = text.lowercase(Locale.ROOT)
        var cssClass = "tok-ident"
        var varKey: String? = null
        var isWrite = false
        var isRead = false
        var impReads = emptyList<String>()
        var impWrites = emptyList<String>()

        if (text.startsWith(";")) {
            cssClass = "tok-comment"
        } else if (text.startsWith("\"") || text.startsWith("'")) {
            cssClass = "tok-string"
        } else if (text.startsWith("#") || text.startsWith("$") || text.matches(Regex("^[0-9].*"))) {
            cssClass = "tok-number"
        } else if (SuperFxIsa.isMnemonic(lower)) {
            cssClass = "tok-insn"
        } else if (pseudoKeywords.contains(lower)) {
            cssClass = "tok-keyword"
        } else if (Constants.isRegister(lower)) {
            cssClass = "tok-register"
            varKey = lower
        } else if (text.matches(Regex("^[,\\[\\]():=+\\-]+$"))) {
            cssClass = "tok-punct"
        } else if (activeScope != null) {
            val matchedInterval = activeScope.intervals.firstOrNull { it.keyName.equals(lower, ignoreCase = true) }
            if (matchedInterval != null) {
                varKey = matchedInterval.keyName
                cssClass = if (matchedInterval.isRegister) "tok-register" else "tok-var-label"
            }
        }

        if (lineEffect != null) {
            val writeKey = lineEffect.explicitWrites[token]
            val readKey = lineEffect.explicitReads[token]
            if (writeKey != null) {
                varKey = keyToString(writeKey)
                isWrite = true
                isRead = false
            } else if (readKey != null) {
                varKey = keyToString(readKey)
                isWrite = false
                isRead = true
            }

            if (token == lineEffect.instructionToken) {
                impReads = lineEffect.implicitReads.map { keyToString(it) }
                impWrites = lineEffect.implicitWrites.map { keyToString(it) }
                if (varKey == null) {
                    if (impWrites.size == 1 && impReads.isEmpty()) {
                        varKey = impWrites[0]
                        isWrite = true
                    } else if (impReads.size == 1 && impWrites.isEmpty()) {
                        varKey = impReads[0]
                        isRead = true
                    }
                }
            }
        }

        return SourceTokenDto(
            text = text,
            type = token.type?.name ?: "UNKNOWN",
            cssClass = cssClass,
            line = lineIdx,
            colStart = token.startIndex,
            colEnd = token.endIndex,
            variableKey = varKey,
            functionScope = activeScope?.name,
            isWrite = isWrite,
            isRead = isRead,
            implicitReads = impReads,
            implicitWrites = impWrites,
        )
    }

    private fun findTokenLocationOnLine(
        file: TokenizedFile,
        lineNum: Int,
        keyName: String,
        isRegister: Boolean,
    ): LocationDto? {
        val tokens = file.getLineTokens(lineNum) ?: return null
        val matchedToken =
            tokens.firstOrNull { t ->
                val txt = t.text().lowercase(Locale.ROOT)
                txt == keyName.lowercase(Locale.ROOT) || (isRegister && Constants.isRegister(txt) && txt == keyName)
            }
        if (matchedToken != null) {
            return LocationDto(line = lineNum, colStart = matchedToken.startIndex, colEnd = matchedToken.endIndex)
        }
        val codeTokens = SfxLivenessAnalyzer.getCodeTokens(file, lineNum)
        val (_, bodyTokens) = SfxLivenessAnalyzer.splitLabelPrefix(codeTokens)
        val insnToken = bodyTokens.firstOrNull()
        return if (insnToken != null) {
            LocationDto(line = lineNum, colStart = insnToken.startIndex, colEnd = insnToken.endIndex)
        } else {
            LocationDto(line = lineNum, colStart = 0, colEnd = 0)
        }
    }

    private fun keyToString(key: IntervalKey): String =
        when (key) {
            is IntervalKey.RegisterKey -> key.register.label.lowercase(Locale.ROOT)
            is IntervalKey.LabelKey -> key.label
        }
}
