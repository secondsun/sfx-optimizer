package dev.secondsun.sfxoptimizer.viewer

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.FileService
import dev.secondsun.retro.util.SymbolService
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.GraphColoringAllocator
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey
import dev.secondsun.sfxoptimizer.analysis.LineEffect
import dev.secondsun.sfxoptimizer.analysis.SfxProgramAnalyzer
import dev.secondsun.sfxoptimizer.graphbuilder.CA65Grapher
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph
import dev.secondsun.sfxoptimizer.graphnode.CodeNode
import java.net.URI
import java.util.Locale

class CodeAnalyzer {
    private val superFxMnemonics =
        setOf(
            "add",
            "adc",
            "alt1",
            "alt2",
            "alt3",
            "and",
            "asr",
            "bcc",
            "bcs",
            "beq",
            "bge",
            "bic",
            "blt",
            "bmi",
            "bne",
            "bpl",
            "bra",
            "bvc",
            "bvs",
            "cache",
            "cmode",
            "cmp",
            "color",
            "dec",
            "div2",
            "fmult",
            "from",
            "getb",
            "getbh",
            "getbl",
            "getbs",
            "getc",
            "hib",
            "ibt",
            "inc",
            "iwt",
            "jal",
            "jmp",
            "ldb",
            "ldw",
            "lea",
            "link",
            "ljmp",
            "lm",
            "lms",
            "lmult",
            "lob",
            "loop",
            "lsr",
            "merge",
            "move",
            "moveb",
            "moves",
            "movew",
            "mult",
            "nop",
            "not",
            "or",
            "plot",
            "pop",
            "push",
            "ramb",
            "ret",
            "rol",
            "romb",
            "ror",
            "rpix",
            "sbc",
            "sbk",
            "sex",
            "sm",
            "sms",
            "stb",
            "stop",
            "stw",
            "sub",
            "swap",
            "to",
            "umult",
            "with",
            "xor",
        )

    private val pseudoKeywords =
        setOf(
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
        val tokenizedFile = scanner.tokenize(source)
        tokenizedFile.uri = URI.create("./$fileName")

        val symbolService = SymbolService()
        symbolService.extractDefinitions(tokenizedFile)

        val fileService =
            object : FileService() {
                override fun readLines(fileUri: URI?): TokenizedFile = tokenizedFile

                override fun find(
                    file: URI?,
                    vararg optionalSearchPaths: URI?,
                ): MutableList<URI> = mutableListOf(tokenizedFile.uri)
            }

        val rawLines = source.lines()
        val totalLines = rawLines.size

        val analysis = SfxProgramAnalyzer.analyze(tokenizedFile, rawLines)
        val allocator = GraphColoringAllocator()

        val functionScopeDtos = mutableListOf<FunctionScopeDto>()
        val allBlockDtos = mutableListOf<CodeBlockDto>()
        var blockIdx = 0

        for (fn in analysis.functions) {
            val fnAllocatedMap = mutableMapOf<String, String>()
            for ((k, v) in fn.allocatedMap) {
                fnAllocatedMap[k] = v.name
            }

            val fnIntervalsMap = fn.intervals.filterValues { it.used() }
            val fnPrecolored = mutableMapOf<IntervalKey, Constants.Register>()
            for ((_, decl) in fn.declaredRegisters) {
                if (decl.forcedRegister != null) {
                    fnPrecolored[IntervalKey.LabelKey(decl.name)] = decl.forcedRegister
                }
            }
            val fnInterference = allocator.buildInterferenceGraph(fnIntervalsMap, fnPrecolored)

            val fnIntervalDtos =
                fnIntervalsMap.values
                    .map { interval ->
                        createIntervalDto(
                            interval = interval,
                            functionScope = fn.name,
                            tokenizedFile = tokenizedFile,
                            allocatedMap = fnAllocatedMap,
                            interference = fnInterference.neighbors(interval.key).map { keyToString(it) }.sorted(),
                        )
                    }.sortedWith(compareBy({ !it.isRegister }, { it.startLine }, { it.keyName }))

            val fnGrapher = CA65Grapher(symbolService = symbolService, fileService = fileService)
            val fnNode: CodeNode.FunctionStart? =
                try {
                    fnGrapher.graphFunction(fn.name)
                } catch (e: Exception) {
                    null
                }

            if (fnNode != null) {
                fnNode.functionBody.traverse { node ->
                    if (node is CodeNode.CodeBlock) {
                        blockIdx++
                        allBlockDtos.add(
                            createBlockDto(node, "block-$blockIdx", fn.name, tokenizedFile, fnAllocatedMap, fnInterference),
                        )
                    }
                }
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

        if (analysis.main != null) {
            val main = analysis.main
            val mainAllocatedMap = mutableMapOf<String, String>()
            for ((k, v) in main.allocatedMap) {
                mainAllocatedMap[k] = v.name
            }

            val mainIntervalsMap = main.intervals.filterValues { it.used() }
            val mainPrecolored = mutableMapOf<IntervalKey, Constants.Register>()
            for ((_, decl) in main.declaredRegisters) {
                if (decl.forcedRegister != null) {
                    mainPrecolored[IntervalKey.LabelKey(decl.name)] = decl.forcedRegister
                }
            }
            val mainInterference = allocator.buildInterferenceGraph(mainIntervalsMap, mainPrecolored)

            val mainIntervalDtos =
                mainIntervalsMap.values
                    .map { interval ->
                        createIntervalDto(
                            interval = interval,
                            functionScope = "(main)",
                            tokenizedFile = tokenizedFile,
                            allocatedMap = mainAllocatedMap,
                            interference = mainInterference.neighbors(interval.key).map { keyToString(it) }.sorted(),
                        )
                    }.sortedWith(compareBy({ !it.isRegister }, { it.startLine }, { it.keyName }))

            val mainGrapher = CA65Grapher(symbolService = symbolService, fileService = fileService)
            val mainGraph: CodeGraph? =
                try {
                    mainGrapher.graph(tokenizedFile, main.startLine)
                } catch (e: Exception) {
                    null
                }

            if (mainGraph != null) {
                mainGraph.traverse { node ->
                    if (node is CodeNode.CodeBlock) {
                        blockIdx++
                        allBlockDtos.add(
                            createBlockDto(node, "block-$blockIdx", "(main)", tokenizedFile, mainAllocatedMap, mainInterference),
                        )
                    }
                }
            }

            functionScopeDtos.add(
                0,
                FunctionScopeDto(
                    name = "(main)",
                    startLine = main.startLine,
                    endLine = main.endLine,
                    intervals = mainIntervalDtos,
                    allocatedMap = mainAllocatedMap,
                ),
            )
        }

        val allIntervalDtos = functionScopeDtos.flatMap { it.intervals }
        val overallAllocatedMap = mutableMapOf<String, String>()
        for (scope in functionScopeDtos) {
            for ((k, v) in scope.allocatedMap) {
                overallAllocatedMap[k] = v
            }
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
                    functionScopeDtos.firstOrNull { lineIdx in it.startLine..it.endLine }
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
        val allocReg = if (isReg) regName else allocatedMap[keyName]

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
        node: CodeNode.CodeBlock,
        id: String,
        functionScope: String,
        tokenizedFile: TokenizedFile,
        allocatedMap: Map<String, String>,
        interference: dev.secondsun.sfxoptimizer.InterferenceGraph,
    ): CodeBlockDto {
        val bStart =
            node.lines
                .firstOrNull()
                ?.tokens
                ?.firstOrNull()
                ?.lineNumber ?: 0
        val bEnd =
            node.lines
                .lastOrNull()
                ?.tokens
                ?.lastOrNull()
                ?.lineNumber ?: bStart
        val bIntervals =
            node.intervals.values.filter { it.used() }.map { bInt ->
                val keyName = keyToString(bInt.key)
                val isReg = bInt.key is IntervalKey.RegisterKey
                val regName = (bInt.key as? IntervalKey.RegisterKey)?.register?.name
                val allocReg = if (isReg) regName else allocatedMap[keyName]
                IntervalSpanDto(
                    keyName = keyName,
                    functionScope = functionScope,
                    isRegister = isReg,
                    registerName = regName,
                    startLine = bInt.start,
                    endLine = bInt.end,
                    reads = bInt.reads.mapNotNull { findTokenLocationOnLine(tokenizedFile, it, keyName, isReg) },
                    writes = bInt.writes.mapNotNull { findTokenLocationOnLine(tokenizedFile, it, keyName, isReg) },
                    interferences = interference.neighbors(bInt.key).map { keyToString(it) }.sorted(),
                    allocatedRegister = allocReg,
                    isSpill = allocReg == "SPILL",
                )
            }
        return CodeBlockDto(
            id = id,
            startLine = bStart,
            endLine = bEnd,
            entrances = node.entrances.map { it.hashCode().toString() },
            exits = node.exits.map { it.hashCode().toString() },
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
        } else if (superFxMnemonics.contains(lower)) {
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
        val insnToken =
            tokens.firstOrNull { t ->
                val txt = t.text().lowercase(Locale.ROOT)
                superFxMnemonics.contains(txt) || txt == "condensed_lmult"
            }
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
