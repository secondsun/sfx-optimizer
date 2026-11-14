package dev.secondsun.sfxoptimizer.analysis

import dev.secondsun.retro.util.FunctionSyntaxHelper
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.WorkspaceSnapshot
import dev.secondsun.retro.util.sfx.MacroEffectSummary
import dev.secondsun.retro.util.sfx.SuperFxIsa
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey
import java.util.Locale

object SfxLivenessAnalyzer {
    private val r0Key = IntervalKey.RegisterKey(Constants.Register.R0)
    private val r1Key = IntervalKey.RegisterKey(Constants.Register.R1)
    private val r2Key = IntervalKey.RegisterKey(Constants.Register.R2)
    private val r4Key = IntervalKey.RegisterKey(Constants.Register.R4)
    private val r6Key = IntervalKey.RegisterKey(Constants.Register.R6)
    private val r7Key = IntervalKey.RegisterKey(Constants.Register.R7)
    private val r8Key = IntervalKey.RegisterKey(Constants.Register.R8)
    private val r11Key = IntervalKey.RegisterKey(Constants.Register.R11)
    private val r12Key = IntervalKey.RegisterKey(Constants.Register.R12)
    private val r13Key = IntervalKey.RegisterKey(Constants.Register.R13)
    private val r14Key = IntervalKey.RegisterKey(Constants.Register.R14)

    fun getCodeTokens(
        file: TokenizedFile,
        lineIdx: Int,
    ): List<Token> =
        file.getLineTokens(lineIdx).filter {
            !it.text().startsWith(";")
        }

    fun splitLabelPrefix(tokens: List<Token>): Pair<String?, List<Token>> {
        if (tokens.isEmpty()) return Pair(null, tokens)
        val first = tokens[0]
        if (first.type == TokenType.TOK_COLON || first.text() == ":") {
            return Pair(":", tokens.subList(1, tokens.size))
        }
        if (first.text().endsWith(":") && first.text().length > 1) {
            return Pair(first.text(), tokens.subList(1, tokens.size))
        }
        if (tokens.size >= 2 && (tokens[1].type == TokenType.TOK_COLON || tokens[1].text() == ":")) {
            if (first.type == TokenType.TOK_IDENT || first.type == TokenType.TOK_LOCAL_IDENT) {
                return Pair(first.text() + ":", tokens.subList(2, tokens.size))
            }
        }
        return Pair(null, tokens)
    }

    fun isInsideImmediateOrNamespace(
        tokens: List<Token>,
        idx: Int,
    ): Boolean {
        if (idx < 0 || idx >= tokens.size) return false
        val tok = tokens[idx]
        if (tok.text().startsWith("#")) return true
        if (idx > 0 && tokens[idx - 1].text() == "::") return true
        if (idx + 1 < tokens.size && tokens[idx + 1].text() == "::") return true

        var i = idx - 1
        var parenDepth = 0
        while (i >= 0) {
            val t = tokens[i].text()
            if (t == ")") parenDepth++
            if (t == "(") {
                if (parenDepth > 0) parenDepth--
            }
            if (parenDepth <= 0 && (t == "," || t == ":")) {
                break
            }
            if (t == "#" || t.startsWith("#")) {
                return true
            }
            i--
        }
        return false
    }

    fun resolveKey(
        token: Token,
        declaredRegisters: Map<String, DeclaredRegister>,
    ): IntervalKey? {
        val text = token.text().lowercase(Locale.ROOT)
        val reg = Constants.register(text)
        if (reg != null) return IntervalKey.RegisterKey(reg)
        val declared = declaredRegisters[text]
        if (declared != null) return IntervalKey.LabelKey(declared.name)
        return null
    }

    fun resolveKeyByName(
        name: String,
        declaredRegisters: Map<String, DeclaredRegister>,
    ): IntervalKey? {
        val lower = name.lowercase(Locale.ROOT)
        val reg = Constants.register(lower)
        if (reg != null) return IntervalKey.RegisterKey(reg)
        val declared = declaredRegisters[lower]
        if (declared != null) return IntervalKey.LabelKey(declared.name)
        return null
    }

    fun findRegisterInArgs(
        args: List<Token>,
        declaredRegisters: Map<String, DeclaredRegister>,
    ): Pair<Token, IntervalKey>? {
        for (i in args.indices) {
            if (isInsideImmediateOrNamespace(args, i)) continue
            val tok = args[i]
            val key = resolveKey(tok, declaredRegisters)
            if (key != null) {
                return Pair(tok, key)
            }
        }
        return null
    }

    fun parseCallStatement(tokens: List<Token>): CallStatement? {
        if (!FunctionSyntaxHelper.isCall(tokens)) return null
        val targetIdx = tokens.indexOfFirst { it.text().equals("call", ignoreCase = true) } + 1
        if (targetIdx >= tokens.size) return null
        val target = tokens[targetIdx].text()
        val afterTarget = tokens.subList(targetIdx + 1, tokens.size)

        var colonIdx = -1
        for (i in afterTarget.indices) {
            if ((afterTarget[i].text() == ":" || afterTarget[i].type == TokenType.TOK_COLON) &&
                (i == 0 || afterTarget[i - 1].text() != ":") &&
                (i + 1 >= afterTarget.size || afterTarget[i + 1].text() != ":")
            ) {
                colonIdx = i
                break
            }
        }

        val argTokens = if (colonIdx >= 0) afterTarget.subList(0, colonIdx) else afterTarget
        val dest =
            if (colonIdx >= 0 && colonIdx + 1 < afterTarget.size) {
                afterTarget[colonIdx + 1].text()
            } else {
                null
            }

        val args = mutableListOf<CallArg>()
        var i = 0
        while (i < argTokens.size) {
            val tok = argTokens[i]
            if (tok.text() == ",") {
                i++
                continue
            }
            if (tok.text() == "#") {
                val exprTokens = mutableListOf<String>()
                exprTokens.add("#")
                i++
                if (i < argTokens.size && argTokens[i].text() == "(") {
                    var depth = 1
                    exprTokens.add("(")
                    i++
                    while (i < argTokens.size && depth > 0) {
                        if (argTokens[i].text() == "(") depth++
                        if (argTokens[i].text() == ")") depth--
                        exprTokens.add(argTokens[i].text())
                        i++
                    }
                } else if (i < argTokens.size) {
                    exprTokens.add(argTokens[i].text())
                    i++
                }
                args.add(CallArg.Immediate(exprTokens.joinToString("")))
                continue
            }
            args.add(CallArg.RegOrVar(tok.text(), tok))
            i++
        }

        return CallStatement(target, args, dest)
    }

    private val colonlessDataDirectives =
        setOf(".byte", ".byt", ".dbyt", ".word", ".addr", ".faraddr", ".dword", ".res", ".tag", ".asciiz", ".literal")

    fun findMacroDefinitionLines(rawLines: List<String>): Set<Int> {
        val result = mutableSetOf<Int>()
        var depth = 0
        for (i in rawLines.indices) {
            val trimmed = rawLines[i].substringBefore(";").trim().lowercase(Locale.ROOT)
            if (trimmed.startsWith(".macro ") ||
                trimmed == ".macro" ||
                trimmed.startsWith(".struct ") ||
                trimmed == ".struct" ||
                trimmed.startsWith(".union ") ||
                trimmed == ".union" ||
                trimmed.startsWith(".enum ") ||
                trimmed == ".enum"
            ) {
                depth++
                result.add(i)
            } else if (trimmed.startsWith(".endmacro") ||
                trimmed.startsWith(".endmac") ||
                trimmed.startsWith(".endm") ||
                trimmed.startsWith(".endstruct") ||
                trimmed.startsWith(".endunion") ||
                trimmed.startsWith(".endenum")
            ) {
                result.add(i)
                depth = maxOf(0, depth - 1)
            } else if (depth > 0) {
                result.add(i)
            }
        }
        return result
    }

    fun discoverFunctions(
        file: TokenizedFile,
        rawLines: List<String>,
        macroLines: Set<Int>,
    ): List<FunctionBoundary> {
        val functions = mutableListOf<FunctionBoundary>()
        var currentName: String? = null
        var currentStart = -1
        var currentParams = emptyList<String>()
        var headerReturnVar: String? = null
        var inferredReturnVar: String? = null
        var currentInline = false

        val totalLines = maxOf(file.textLines(), rawLines.size)
        for (lineIdx in 0 until totalLines) {
            if (lineIdx in macroLines) continue
            val codeTokens = getCodeTokens(file, lineIdx)
            val (_, bodyTokens) = splitLabelPrefix(codeTokens)
            if (bodyTokens.isEmpty()) continue

            if (FunctionSyntaxHelper.isFunction(bodyTokens)) {
                val decl = FunctionSyntaxHelper.parseFunction(bodyTokens)
                if (decl.nameToken() != null) {
                    currentName = decl.nameToken().text()
                    currentStart = lineIdx
                    currentParams = decl.parameters().map { it.text() }
                    headerReturnVar = decl.returnVariable().map { it.text() }.orElse(null)
                    inferredReturnVar = null
                    currentInline = decl.isInline()
                }
            } else if (currentName != null && FunctionSyntaxHelper.isReturn(bodyTokens)) {
                val retStmt = FunctionSyntaxHelper.parseReturn(bodyTokens)
                if (retStmt.returnVariable().isPresent) {
                    inferredReturnVar = retStmt.returnVariable().get().text()
                }
            } else if (currentName != null && FunctionSyntaxHelper.isEndFunction(bodyTokens)) {
                functions.add(
                    FunctionBoundary(
                        name = currentName,
                        startLine = currentStart,
                        endLine = lineIdx,
                        params = currentParams,
                        headerReturnVar = headerReturnVar,
                        inferredReturnVar = inferredReturnVar,
                        isInline = currentInline,
                    ),
                )
                currentName = null
                currentStart = -1
                currentParams = emptyList()
                headerReturnVar = null
                inferredReturnVar = null
                currentInline = false
            }
        }

        return functions
    }

    fun sortFunctionsBottomUp(
        functions: List<FunctionBoundary>,
        file: TokenizedFile,
        macroLines: Set<Int>,
    ): List<FunctionBoundary> {
        val fnMap = functions.associateBy { it.name.lowercase(Locale.ROOT) }
        val callGraph = mutableMapOf<String, MutableSet<String>>()
        for (fn in functions) {
            callGraph[fn.name.lowercase(Locale.ROOT)] = mutableSetOf()
        }

        for (fn in functions) {
            val callerKey = fn.name.lowercase(Locale.ROOT)
            for (lineIdx in fn.startLine..fn.endLine) {
                if (lineIdx in macroLines) continue
                val codeTokens = getCodeTokens(file, lineIdx)
                val (_, bodyTokens) = splitLabelPrefix(codeTokens)
                if (FunctionSyntaxHelper.isCall(bodyTokens)) {
                    val call = parseCallStatement(bodyTokens)
                    if (call != null) {
                        val calleeKey = call.target.lowercase(Locale.ROOT)
                        if (fnMap.containsKey(calleeKey) && calleeKey != callerKey) {
                            callGraph[callerKey]?.add(calleeKey)
                        }
                    }
                }
            }
        }

        val visited = mutableSetOf<String>()
        val sortedKeys = mutableListOf<String>()

        fun visit(name: String) {
            if (name in visited) return
            visited.add(name)
            for (callee in callGraph[name] ?: emptySet()) {
                visit(callee)
            }
            sortedKeys.add(name)
        }

        for (fn in functions) {
            visit(fn.name.lowercase(Locale.ROOT))
        }

        return sortedKeys.mapNotNull { fnMap[it] }
    }

    fun extractDeclaredRegisters(
        file: TokenizedFile,
        scopeLines: Set<Int>,
        params: List<String> = emptyList(),
        effectiveReturnVar: String? = null,
    ): Map<String, DeclaredRegister> {
        val declared = mutableMapOf<String, DeclaredRegister>()
        val minLine = scopeLines.minOrNull() ?: 0

        for (p in params) {
            val lower = p.lowercase(Locale.ROOT)
            if (!Constants.isRegister(lower)) {
                declared[lower] = DeclaredRegister(name = p, forcedRegister = null, line = minLine)
            }
        }

        if (effectiveReturnVar != null) {
            val lower = effectiveReturnVar.lowercase(Locale.ROOT)
            if (!Constants.isRegister(lower) && lower !in declared) {
                declared[lower] = DeclaredRegister(name = effectiveReturnVar, forcedRegister = null, line = minLine)
            }
        }

        for (lineIdx in scopeLines.sorted()) {
            val codeTokens = getCodeTokens(file, lineIdx)
            if (codeTokens.isEmpty()) continue
            val (_, bodyTokens) = splitLabelPrefix(codeTokens)
            if (bodyTokens.isEmpty()) continue

            val first = bodyTokens[0]
            val isRegKeyword =
                first.type == TokenType.TOK_REGISTER_KEYWORD ||
                    first.text().equals("register", ignoreCase = true)
            if (!isRegKeyword) continue

            var i = 1
            while (i < bodyTokens.size) {
                val tok = bodyTokens[i]
                if (tok.type == TokenType.TOK_COMMA || tok.text() == ",") {
                    i++
                    continue
                }
                val varName = tok.text()
                val varLower = varName.lowercase(Locale.ROOT)
                if (varName == "=" || tok.type == TokenType.TOK_EQ || Constants.isRegister(varLower)) {
                    i++
                    continue
                }
                i++
                var forcedReg: Constants.Register? = null
                if (i < bodyTokens.size && (bodyTokens[i].text() == "=" || bodyTokens[i].type == TokenType.TOK_EQ)) {
                    i++
                    if (i < bodyTokens.size) {
                        val regTok = bodyTokens[i]
                        forcedReg =
                            Constants.register(regTok.text())
                                ?: Constants.Register.entries.find { it.name.equals(regTok.text(), ignoreCase = true) }
                        i++
                    }
                }
                val existing = declared[varLower]
                declared[varLower] =
                    DeclaredRegister(
                        name = existing?.name ?: varName,
                        forcedRegister = forcedReg ?: existing?.forcedRegister,
                        line = existing?.line ?: lineIdx,
                    )
            }
        }

        return declared
    }

    private fun splitMacroArgGroups(args: List<Token>): List<List<Token>> {
        if (args.isEmpty()) return emptyList()
        val hasComma = args.any { it.type == TokenType.TOK_COMMA || it.text() == "," }
        if (!hasComma) {
            // Single operand or whitespace-separated simple operands
            val groups = mutableListOf<List<Token>>()
            var i = 0
            while (i < args.size) {
                if (args[i].text() == "#") {
                    val end = (i + 2).coerceAtMost(args.size)
                    groups.add(args.subList(i, end))
                    i = end
                } else if (args[i].text() == "(") {
                    var j = i + 1
                    var depth = 1
                    while (j < args.size && depth > 0) {
                        if (args[j].text() == "(") depth++
                        if (args[j].text() == ")") depth--
                        j++
                    }
                    groups.add(args.subList(i, j))
                    i = j
                } else {
                    groups.add(listOf(args[i]))
                    i++
                }
            }
            return groups
        }

        val groups = mutableListOf<List<Token>>()
        var cur = mutableListOf<Token>()
        var depth = 0
        for (tok in args) {
            if (tok.text() == "(") depth++
            if (tok.text() == ")") depth = maxOf(0, depth - 1)
            if (depth == 0 && (tok.type == TokenType.TOK_COMMA || tok.text() == ",")) {
                if (cur.isNotEmpty()) groups.add(cur)
                cur = mutableListOf()
            } else {
                cur.add(tok)
            }
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    fun evaluateLineEffect(
        tokens: List<Token>,
        currentPendingSource: IntervalKey?,
        currentPendingDest: IntervalKey?,
        declaredRegisters: Map<String, DeclaredRegister>,
        params: List<String> = emptyList(),
        effectiveReturnVar: String? = null,
        lineIdx: Int = tokens.firstOrNull()?.lineNumber ?: 0,
        file: TokenizedFile? = null,
        snapshot: WorkspaceSnapshot? = null,
        warnings: MutableList<String>? = null,
    ): LineEffect {
        if (tokens.isEmpty()) {
            return LineEffect(
                lineIdx = lineIdx,
                instructionToken = null,
                nextPendingSource = currentPendingSource,
                nextPendingDest = currentPendingDest,
            )
        }

        // Zero-byte labels do NOT reset active SuperFX modal state!
        val (_, bodyTokens) = splitLabelPrefix(tokens)
        var pendingSource = currentPendingSource
        var pendingDest = currentPendingDest

        if (bodyTokens.isEmpty()) {
            return LineEffect(
                lineIdx = lineIdx,
                instructionToken = null,
                nextPendingSource = pendingSource,
                nextPendingDest = pendingDest,
            )
        }

        val insnTok = bodyTokens[0]
        val mnemonic = insnTok.text().lowercase(Locale.ROOT)
        val secondTok = if (bodyTokens.size >= 2) bodyTokens[1].text().lowercase(Locale.ROOT) else ""
        if (mnemonic.startsWith(".") ||
            secondTok == "=" ||
            secondTok == ":=" ||
            secondTok in colonlessDataDirectives ||
            mnemonic == "register" ||
            insnTok.type == TokenType.TOK_REGISTER_KEYWORD
        ) {
            return LineEffect(
                lineIdx = lineIdx,
                instructionToken = insnTok,
                nextPendingSource = pendingSource,
                nextPendingDest = pendingDest,
            )
        }

        val explicitReads = mutableMapOf<Token, IntervalKey>()
        val explicitWrites = mutableMapOf<Token, IntervalKey>()
        val implicitReads = mutableListOf<IntervalKey>()
        val implicitWrites = mutableListOf<IntervalKey>()
        val memoryAddressKeys = mutableSetOf<IntervalKey>()

        if (FunctionSyntaxHelper.isFunction(bodyTokens)) {
            val decl = FunctionSyntaxHelper.parseFunction(bodyTokens)
            if (!decl.isInline()) {
                implicitReads.add(r11Key) // non-inline function pushes r11 (return address)
            }
            for (param in params) {
                resolveKeyByName(param, declaredRegisters)?.let { implicitWrites.add(it) }
            }
            return LineEffect(
                lineIdx = lineIdx,
                instructionToken = insnTok,
                implicitReads = implicitReads,
                implicitWrites = implicitWrites,
                nextPendingSource = null,
                nextPendingDest = null,
            )
        }

        val args = bodyTokens.subList(1, bodyTokens.size)

        fun useSource() {
            implicitReads.add(pendingSource ?: r0Key)
        }

        fun useDest() {
            implicitWrites.add(pendingDest ?: r0Key)
        }

        when (mnemonic) {
            "endfunction" -> {
                if (effectiveReturnVar != null) {
                    resolveKeyByName(effectiveReturnVar, declaredRegisters)?.let { implicitReads.add(it) }
                }
                pendingSource = null
                pendingDest = null
            }
            "return" -> {
                val retStmt = FunctionSyntaxHelper.parseReturn(bodyTokens)
                if (retStmt.returnVariable().isPresent) {
                    val retTok = retStmt.returnVariable().get()
                    resolveKey(retTok, declaredRegisters)?.let { explicitReads[retTok] = it }
                }
                if (effectiveReturnVar != null) {
                    resolveKeyByName(effectiveReturnVar, declaredRegisters)?.let { implicitReads.add(it) }
                }
                pendingSource = null
                pendingDest = null
            }
            "call" -> {
                val call = parseCallStatement(bodyTokens)
                if (call != null) {
                    for (arg in call.args) {
                        if (arg is CallArg.RegOrVar) {
                            resolveKey(arg.token, declaredRegisters)?.let { explicitReads[arg.token] = it }
                        }
                    }
                    if (call.dest != null) {
                        val destTok = bodyTokens.last()
                        resolveKeyByName(call.dest, declaredRegisters)?.let { explicitWrites[destTok] = it }
                    }
                }
                implicitWrites.add(r11Key)
                pendingSource = null
                pendingDest = null
            }
            "to" -> {
                if (args.isNotEmpty()) {
                    val target = resolveKey(args[0], declaredRegisters)
                    pendingDest = target
                    if (target != null) explicitWrites[args[0]] = target
                }
            }
            "from" -> {
                if (args.isNotEmpty()) {
                    val source = resolveKey(args[0], declaredRegisters)
                    pendingSource = source
                    if (source != null) explicitReads[args[0]] = source
                }
            }
            "with" -> {
                if (args.isNotEmpty()) {
                    val target = resolveKey(args[0], declaredRegisters)
                    pendingDest = target
                    pendingSource = target
                    if (target != null) {
                        explicitWrites[args[0]] = target
                        explicitReads[args[0]] = target
                    }
                }
            }
            "alt1", "alt2", "alt3" -> {
                // Composes with active pendingSource and pendingDest without resetting!
            }
            "ldw", "ldb" -> {
                findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) ->
                    explicitReads[tok] = key
                    memoryAddressKeys.add(key)
                }
                useDest()
                pendingSource = null
                pendingDest = null
            }
            "stw", "stb" -> {
                findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) ->
                    explicitReads[tok] = key
                    memoryAddressKeys.add(key)
                }
                useSource()
                pendingSource = null
                pendingDest = null
            }
            "sbk" -> {
                useSource()
                pendingSource = null
                pendingDest = null
            }
            "lmult" -> {
                useSource()
                useDest()
                implicitReads.add(r6Key)
                implicitWrites.add(r4Key)
                pendingSource = null
                pendingDest = null
            }
            "fmult" -> {
                useSource()
                useDest()
                implicitReads.add(r6Key)
                pendingSource = null
                pendingDest = null
            }
            "mult", "umult", "add", "adc", "sub", "sbc", "and", "bic", "or", "xor" -> {
                findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                useSource()
                useDest()
                pendingSource = null
                pendingDest = null
            }
            "cmp" -> {
                findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                useSource()
                pendingSource = null
                pendingDest = null
            }
            "not", "div2", "lsr", "asr", "rol", "ror", "sex", "swap", "hib", "lob" -> {
                useSource()
                useDest()
                pendingSource = null
                pendingDest = null
            }
            "merge" -> {
                implicitReads.add(r7Key)
                implicitReads.add(r8Key)
                useDest()
                pendingSource = null
                pendingDest = null
            }
            "plot" -> {
                implicitReads.add(r1Key)
                implicitReads.add(r2Key)
                implicitWrites.add(r1Key) // plot auto-increments R1!
                pendingSource = null
                pendingDest = null
            }
            "rpix" -> {
                implicitReads.add(r1Key)
                implicitReads.add(r2Key)
                useDest()
                pendingSource = null
                pendingDest = null
            }
            "getb", "getbs" -> {
                implicitReads.add(r14Key)
                useDest()
                pendingSource = null
                pendingDest = null
            }
            "getbh", "getbl" -> {
                implicitReads.add(r14Key)
                useSource()
                useDest()
                pendingSource = null
                pendingDest = null
            }
            "getc" -> {
                implicitReads.add(r14Key)
                pendingSource = null
                pendingDest = null
            }
            "ramb", "romb", "color", "cmode" -> {
                useSource()
                pendingSource = null
                pendingDest = null
            }
            "loop" -> {
                implicitReads.add(r13Key)
                implicitReads.add(r12Key)
                implicitWrites.add(r12Key)
                pendingSource = null
                pendingDest = null
            }
            "link" -> {
                implicitWrites.add(r11Key)
                pendingSource = null
                pendingDest = null
            }
            "move", "moves", "movew", "moveb" -> {
                val regArgs = mutableListOf<Pair<Token, IntervalKey>>()
                for (idx in args.indices) {
                    if (isInsideImmediateOrNamespace(args, idx)) continue
                    val k = resolveKey(args[idx], declaredRegisters)
                    if (k != null) {
                        regArgs.add(Pair(args[idx], k))
                        if (idx > 0 && args[idx - 1].text() == "(") {
                            memoryAddressKeys.add(k)
                        }
                    }
                }
                if (regArgs.size >= 2) {
                    explicitWrites[regArgs[0].first] = regArgs[0].second
                    explicitReads[regArgs[1].first] = regArgs[1].second
                } else if (regArgs.size == 1) {
                    if (args.firstOrNull()?.text() == "(") {
                        explicitReads[regArgs[0].first] = regArgs[0].second
                    } else {
                        explicitWrites[regArgs[0].first] = regArgs[0].second
                    }
                }
                pendingSource = null
                pendingDest = null
            }
            "iwt", "ibt", "lm", "lms", "lea" -> {
                findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitWrites[tok] = key }
                pendingSource = null
                pendingDest = null
            }
            "inc", "dec", "shl" -> {
                val targetPair = findRegisterInArgs(args, declaredRegisters)
                if (targetPair != null) {
                    explicitReads[targetPair.first] = targetPair.second
                    explicitWrites[targetPair.first] = targetPair.second
                } else if (mnemonic == "shl") {
                    useSource()
                    useDest()
                }
                pendingSource = null
                pendingDest = null
            }
            "sm", "sms" -> {
                val regArgs =
                    args.mapIndexedNotNull { idx, tok ->
                        if (isInsideImmediateOrNamespace(args, idx)) {
                            null
                        } else {
                            resolveKey(tok, declaredRegisters)?.let { Pair(tok, it) }
                        }
                    }
                regArgs.lastOrNull()?.let { (tok, key) -> explicitReads[tok] = key }
                pendingSource = null
                pendingDest = null
            }
            "gsu_stack_push" -> {
                findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                pendingSource = null
                pendingDest = null
            }
            "gsu_stack_pop" -> {
                findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitWrites[tok] = key }
                pendingSource = null
                pendingDest = null
            }
            "gsu_stack_peek" -> {
                val regArgs =
                    args.mapIndexedNotNull { idx, tok ->
                        if (isInsideImmediateOrNamespace(args, idx)) null else resolveKey(tok, declaredRegisters)?.let { Pair(tok, it) }
                    }
                if (regArgs.isNotEmpty()) {
                    explicitWrites[regArgs[0].first] = regArgs[0].second
                }
                if (args.any { it.text() == "," }) {
                    implicitWrites.add(r0Key)
                }
                pendingSource = null
                pendingDest = null
            }
            "gsu_stack_alloc_bytes", "gsu_stack_free_bytes", "init_stack", "backuploop" -> {
                findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                pendingSource = null
                pendingDest = null
            }
            "for" -> {
                implicitWrites.add(r12Key)
                implicitWrites.add(r13Key)
                pendingSource = null
                pendingDest = null
            }
            "forr" -> {
                findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                implicitWrites.add(r12Key)
                implicitWrites.add(r13Key)
                pendingSource = null
                pendingDest = null
            }
            "endfor", "restoreloop", "break" -> {
                implicitReads.add(r12Key)
                implicitReads.add(r13Key)
                implicitWrites.add(r12Key)
                implicitWrites.add(r13Key)
                pendingSource = null
                pendingDest = null
            }
            "if_lt", "if_gt", "if_gte", "if_eq", "if_ne", "if_lte" -> {
                implicitWrites.add(r0Key)
                var afterWriteOp = false
                var writeDone = false
                for (idx in args.indices) {
                    if (isInsideImmediateOrNamespace(args, idx)) continue
                    val tokText = args[idx].text().lowercase(Locale.ROOT)
                    if (tokText in setOf("move", "moves", "iwt", "ibt", "lm", "lms", "lea", "to", "with", "inc", "dec", "shl")) {
                        afterWriteOp = true
                        writeDone = false
                        continue
                    }
                    val key = resolveKey(args[idx], declaredRegisters)
                    if (key != null) {
                        if (afterWriteOp && !writeDone) {
                            explicitWrites[args[idx]] = key
                            writeDone = true
                        } else {
                            explicitReads[args[idx]] = key
                        }
                    }
                }
                pendingSource = null
                pendingDest = null
            }
            else -> {
                if (SuperFxIsa.isMnemonic(mnemonic)) {
                    // Branches (`bra`, `bne`, etc.), `jmp`/`ljmp`, `nop`, `cache`, `stop`
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) ->
                        explicitReads[tok] = key
                    }
                } else {
                    // Derive effect automatically from ca65 .macro definition via MacroEffectSummary!
                    val macroSummary: MacroEffectSummary? =
                        file?.scopeTree()?.macroSummary(mnemonic)?.orElse(null)
                            ?: snapshot?.resolveMacroSummary(mnemonic)?.orElse(null)

                    if (macroSummary != null) {
                        val argGroups = splitMacroArgGroups(args)
                        for (pIdx in macroSummary.addressParams()) {
                            val grp = argGroups.getOrNull(pIdx) ?: continue
                            findRegisterInArgs(grp, declaredRegisters)?.let { (_, key) ->
                                memoryAddressKeys.add(key)
                            }
                        }
                        for (pIdx in macroSummary.upwardReadParams()) {
                            val grp = argGroups.getOrNull(pIdx) ?: continue
                            findRegisterInArgs(grp, declaredRegisters)?.let { (tok, key) ->
                                explicitReads[tok] = key
                            }
                        }
                        for (pIdx in macroSummary.writtenParams()) {
                            val grp = argGroups.getOrNull(pIdx) ?: continue
                            findRegisterInArgs(grp, declaredRegisters)?.let { (tok, key) ->
                                explicitWrites[tok] = key
                            }
                        }
                        for (physRead in macroSummary.physicalReads()) {
                            Constants.register(physRead)?.let { reg ->
                                if (reg != Constants.Register.R10 && reg != Constants.Register.R15) {
                                    implicitReads.add(IntervalKey.RegisterKey(reg))
                                }
                            }
                        }
                        for (physClobber in macroSummary.physicalClobbers()) {
                            Constants.register(physClobber)?.let { reg ->
                                if (reg != Constants.Register.R10 && reg != Constants.Register.R15) {
                                    implicitWrites.add(IntervalKey.RegisterKey(reg))
                                }
                            }
                        }
                    } else {
                        for (idx in args.indices) {
                            if (isInsideImmediateOrNamespace(args, idx)) continue
                            val key = resolveKey(args[idx], declaredRegisters)
                            if (key != null) {
                                explicitReads[args[idx]] = key
                            }
                        }
                        if (declaredRegisters.isNotEmpty()) {
                            warnings?.add(
                                "Unresolved external macro '${insnTok.text()}' at line ${lineIdx + 1}; " +
                                    "conservatively clobbering volatile registers (r0, r4, r14).",
                            )
                            implicitWrites.add(r0Key)
                            implicitWrites.add(r4Key)
                            implicitWrites.add(r14Key)
                        }
                    }
                }
                pendingSource = null
                pendingDest = null
            }
        }

        return LineEffect(
            lineIdx = lineIdx,
            instructionToken = insnTok,
            explicitReads = explicitReads,
            explicitWrites = explicitWrites,
            implicitReads = implicitReads,
            implicitWrites = implicitWrites,
            memoryAddressKeys = memoryAddressKeys,
            nextPendingSource = pendingSource,
            nextPendingDest = pendingDest,
        )
    }

    fun analyzeScopeLines(
        file: TokenizedFile,
        scopeLines: Set<Int>,
        declaredRegisters: Map<String, DeclaredRegister>,
        params: List<String>,
        effectiveReturnVar: String?,
        fnStartLine: Int,
        snapshot: WorkspaceSnapshot? = null,
        warnings: MutableList<String>? = null,
    ): Map<Int, LineEffect> {
        val result = mutableMapOf<Int, LineEffect>()
        var pendingSource: IntervalKey? = null
        var pendingDest: IntervalKey? = null

        for (lineIdx in scopeLines.sorted()) {
            val codeTokens = getCodeTokens(file, lineIdx)
            if (codeTokens.isEmpty()) continue

            val effect =
                evaluateLineEffect(
                    tokens = codeTokens,
                    currentPendingSource = pendingSource,
                    currentPendingDest = pendingDest,
                    declaredRegisters = declaredRegisters,
                    params = params,
                    effectiveReturnVar = effectiveReturnVar,
                    lineIdx = lineIdx,
                    file = file,
                    snapshot = snapshot,
                    warnings = warnings,
                )

            pendingSource = effect.nextPendingSource
            pendingDest = effect.nextPendingDest
            result[lineIdx] = effect
        }

        if (params.isNotEmpty() && fnStartLine in scopeLines && fnStartLine !in result) {
            val impWrites = params.mapNotNull { resolveKeyByName(it, declaredRegisters) }
            result[fnStartLine] =
                LineEffect(
                    lineIdx = fnStartLine,
                    instructionToken = null,
                    implicitWrites = impWrites,
                )
        }

        return result
    }

    fun detectLoops(
        file: TokenizedFile,
        scopeLines: Set<Int>,
    ): List<IntRange> =
        SfxCfgBuilder
            .buildScopeCfg(file, "scope", scopeLines)
            .loops
            .map { it.lineRange }

    fun buildIntervals(
        scopeLines: Set<Int>,
        effects: Map<Int, LineEffect>,
        declaredRegisters: Map<String, DeclaredRegister>,
        loops: List<IntRange>,
    ): Map<IntervalKey, Interval> {
        val intervalsMap = mutableMapOf<IntervalKey, Interval>()

        fun addRead(
            key: IntervalKey,
            lineIdx: Int,
        ) {
            intervalsMap.getOrPut(key) { Interval(key) }.addRead(lineIdx)
            if (key is IntervalKey.LabelKey) {
                val forced = declaredRegisters[key.label.lowercase(Locale.ROOT)]?.forcedRegister
                if (forced != null) {
                    val regKey = IntervalKey.RegisterKey(forced)
                    intervalsMap.getOrPut(regKey) { Interval(regKey) }.addRead(lineIdx)
                }
            }
        }

        fun addWrite(
            key: IntervalKey,
            lineIdx: Int,
        ) {
            intervalsMap.getOrPut(key) { Interval(key) }.addWrite(lineIdx)
            if (key is IntervalKey.LabelKey) {
                val forced = declaredRegisters[key.label.lowercase(Locale.ROOT)]?.forcedRegister
                if (forced != null) {
                    val regKey = IntervalKey.RegisterKey(forced)
                    intervalsMap.getOrPut(regKey) { Interval(regKey) }.addWrite(lineIdx)
                }
            }
        }

        for ((lineIdx, effect) in effects) {
            effect.explicitReads.values.forEach { addRead(it, lineIdx) }
            effect.explicitWrites.values.forEach { addWrite(it, lineIdx) }
            effect.implicitReads.forEach { addRead(it, lineIdx) }
            effect.implicitWrites.forEach { addWrite(it, lineIdx) }
        }

        for ((_, decl) in declaredRegisters) {
            val key = IntervalKey.LabelKey(decl.name)
            val intv = intervalsMap.getOrPut(key) { Interval(key) }
            if (!intv.used() && decl.line in scopeLines) {
                addWrite(key, decl.line)
            }
        }

        var changed = true
        while (changed) {
            changed = false
            for (loop in loops) {
                val loopStart = loop.first
                val loopEnd = loop.last
                for ((key, intv) in intervalsMap) {
                    if (!intv.used()) continue
                    if (key == r0Key) continue

                    val firstReadInLoop = intv.reads.filter { it in loopStart..loopEnd }.minOrNull()
                    val firstWriteInLoop = intv.writes.filter { it in loopStart..loopEnd }.minOrNull()

                    val loopCarried =
                        firstReadInLoop != null && (firstWriteInLoop == null || firstReadInLoop <= firstWriteInLoop)
                    val liveEntering =
                        intv.start < loopStart &&
                            intv.end >= loopStart &&
                            (loopCarried || intv.reads.any { it > loopEnd })
                    val liveExiting = intv.start <= loopEnd && intv.end > loopEnd && intv.reads.any { it > loopEnd }

                    if (liveEntering || liveExiting || loopCarried) {
                        val newStart = minOf(intv.start, loopStart)
                        val newEnd = maxOf(intv.end, loopEnd)
                        if (newStart < intv.start || newEnd > intv.end) {
                            intv.start = newStart
                            intv.end = newEnd
                            changed = true
                        }
                    }
                }
            }
        }

        for ((_, decl) in declaredRegisters) {
            if (decl.forcedRegister != null) {
                val labelIntv = intervalsMap[IntervalKey.LabelKey(decl.name)]
                if (labelIntv != null && labelIntv.used()) {
                    val regKey = IntervalKey.RegisterKey(decl.forcedRegister)
                    val regIntv = intervalsMap.getOrPut(regKey) { Interval(regKey) }
                    regIntv.start = minOf(regIntv.start, labelIntv.start)
                    regIntv.end = maxOf(regIntv.end, labelIntv.end)
                }
            }
        }

        return intervalsMap
    }

    private fun isLiveAfterCall(
        intv: Interval,
        callLine: Int,
        loops: List<IntRange>,
    ): Boolean {
        if (intv.start >= callLine || intv.end <= callLine) return false

        val nextRead = intv.reads.filter { it > callLine }.minOrNull()
        val nextWrite = intv.writes.filter { it > callLine }.minOrNull()
        if (nextWrite != null && (nextRead == null || nextWrite < nextRead)) {
            return false
        }
        if (nextRead != null) {
            return true
        }

        for (loop in loops) {
            if (callLine in loop && intv.start <= loop.first && intv.end >= loop.last) {
                val firstReadInLoop = intv.reads.filter { it in loop }.minOrNull()
                val firstWriteInLoop = intv.writes.filter { it in loop }.minOrNull()
                if (firstReadInLoop != null && (firstWriteInLoop == null || firstReadInLoop <= firstWriteInLoop)) {
                    return true
                }
            }
        }

        return false
    }

    data class CfgLiveness(
        val liveAfterByLine: Map<Int, Set<IntervalKey>>,
        val interferenceEdges: Map<IntervalKey, Set<IntervalKey>>,
    )

    fun computeCfgLiveness(
        file: TokenizedFile,
        cfg: ScopeCfg,
        effects: Map<Int, LineEffect>,
        activeIntervals: Map<IntervalKey, Interval>,
        declaredRegisters: Map<String, DeclaredRegister>,
        fnStartLine: Int? = null,
        effectiveReturnVar: String? = null,
    ): CfgLiveness {
        val activeKeys = activeIntervals.keys
        val readsByLine = HashMap<Int, LinkedHashSet<IntervalKey>>()
        val writesByLine = HashMap<Int, LinkedHashSet<IntervalKey>>()

        fun recordKey(
            target: LinkedHashSet<IntervalKey>,
            key: IntervalKey,
        ) {
            if (key in activeKeys) {
                target.add(key)
            }
            if (key is IntervalKey.LabelKey) {
                val forced = declaredRegisters[key.label.lowercase(Locale.ROOT)]?.forcedRegister
                if (forced != null) {
                    val regKey = IntervalKey.RegisterKey(forced)
                    if (regKey in activeKeys) {
                        target.add(regKey)
                    }
                }
            }
        }

        for ((lineIdx, eff) in effects) {
            val r = LinkedHashSet<IntervalKey>()
            val w = LinkedHashSet<IntervalKey>()
            eff.explicitReads.values.forEach { recordKey(r, it) }
            eff.implicitReads.forEach { recordKey(r, it) }
            eff.explicitWrites.values.forEach { recordKey(w, it) }
            eff.implicitWrites.forEach { recordKey(w, it) }
            if (r.isNotEmpty()) readsByLine[lineIdx] = r
            if (w.isNotEmpty()) writesByLine[lineIdx] = w
        }

        val useMap = HashMap<String, LinkedHashSet<IntervalKey>>(cfg.blocks.size)
        val defMap = HashMap<String, LinkedHashSet<IntervalKey>>(cfg.blocks.size)
        val labeledBlockIds = ArrayList<String>()

        for (b in cfg.blocks) {
            val firstTokens = getCodeTokens(file, b.startLine)
            if (splitLabelPrefix(firstTokens).first != null) {
                labeledBlockIds.add(b.id)
            }
            val use = LinkedHashSet<IntervalKey>()
            val def = LinkedHashSet<IntervalKey>()
            for (lineIdx in b.lineIndices) {
                readsByLine[lineIdx]?.forEach { k ->
                    if (k != r0Key && k !in def) {
                        use.add(k)
                    }
                }
                writesByLine[lineIdx]?.forEach { k ->
                    if (k != r0Key) {
                        def.add(k)
                    }
                }
            }
            useMap[b.id] = use
            defMap[b.id] = def
        }

        val liveIn = cfg.blocks.associate { it.id to LinkedHashSet<IntervalKey>() }
        val liveOut = cfg.blocks.associate { it.id to LinkedHashSet<IntervalKey>() }

        val fallthroughReturnKey =
            effectiveReturnVar
                ?.let { resolveKeyByName(it, declaredRegisters) }
                ?.takeIf { it in activeKeys && it != r0Key }

        if (fallthroughReturnKey != null) {
            for (b in cfg.blocks) {
                if (b.exits.isEmpty() && !b.terminatesWithReturnOrStop && !b.unresolvedDynamicJump) {
                    liveOut.getValue(b.id).add(fallthroughReturnKey)
                }
            }
        }

        var changed = true
        val reversedBlocks = cfg.blocks.asReversed()
        while (changed) {
            changed = false
            for (b in reversedBlocks) {
                val outSet = liveOut.getValue(b.id)
                val prevOutSize = outSet.size
                for (succId in b.exits) {
                    liveIn[succId]?.let { outSet.addAll(it) }
                }
                if (b.unresolvedDynamicJump) {
                    for (targetId in labeledBlockIds) {
                        liveIn[targetId]?.let { outSet.addAll(it) }
                    }
                }
                val inSet = liveIn.getValue(b.id)
                val prevInSize = inSet.size
                inSet.clear()
                inSet.addAll(outSet)
                inSet.removeAll(defMap.getValue(b.id))
                inSet.addAll(useMap.getValue(b.id))
                if (outSet.size != prevOutSize || inSet.size != prevInSize) {
                    changed = true
                }
            }
        }

        val edges = activeKeys.associateWith { LinkedHashSet<IntervalKey>() }
        val liveAfterByLine = HashMap<Int, Set<IntervalKey>>()

        fun addClique(group: Collection<IntervalKey>) {
            if (group.size < 2) return
            val list = group.toList()
            for (i in 0 until list.size) {
                val u = list[i]
                val uEdges = edges[u] ?: continue
                for (j in i + 1 until list.size) {
                    val v = list[j]
                    if (u != v) {
                        uEdges.add(v)
                        edges[v]?.add(u)
                    }
                }
            }
        }

        for (b in cfg.blocks) {
            val live = LinkedHashSet(liveOut.getValue(b.id))
            addClique(live)
            for (lineIdx in b.lineIndices.asReversed()) {
                liveAfterByLine[lineIdx] = LinkedHashSet(live)
                val r = readsByLine[lineIdx] ?: emptySet()
                val w = writesByLine[lineIdx] ?: emptySet()
                if (r.isNotEmpty() || w.isNotEmpty()) {
                    val activeAtLine = LinkedHashSet<IntervalKey>(live.size + r.size + w.size)
                    activeAtLine.addAll(live)
                    activeAtLine.addAll(r)
                    activeAtLine.addAll(w)
                    addClique(activeAtLine)
                }
                if (w.isNotEmpty()) live.removeAll(w)
                for (rk in r) {
                    if (rk != r0Key) live.add(rk)
                }
            }
        }

        if (fnStartLine != null) {
            val entryLive = LinkedHashSet<IntervalKey>()
            val firstBlock = cfg.blocks.firstOrNull()
            if (firstBlock != null) {
                entryLive.addAll(liveIn.getValue(firstBlock.id))
            } else if (fallthroughReturnKey != null) {
                entryLive.add(fallthroughReturnKey)
            }
            liveAfterByLine[fnStartLine] = entryLive
            val r = readsByLine[fnStartLine] ?: emptySet()
            val w = writesByLine[fnStartLine] ?: emptySet()
            val activeAtHeader = LinkedHashSet<IntervalKey>(entryLive.size + r.size + w.size)
            activeAtHeader.addAll(entryLive)
            activeAtHeader.addAll(r)
            activeAtHeader.addAll(w)
            addClique(activeAtHeader)
        }

        return CfgLiveness(liveAfterByLine = liveAfterByLine, interferenceEdges = edges)
    }

    fun computeCallerSaveAvoidance(
        file: TokenizedFile,
        scopeLines: Set<Int>,
        declaredRegisters: Map<String, DeclaredRegister>,
        intervals: Map<IntervalKey, Interval>,
        loops: List<IntRange>,
        allocatedFunctions: Map<String, FunctionAnalysis>,
        liveAfterByLine: Map<Int, Set<IntervalKey>>? = null,
    ): Map<IntervalKey, Set<Constants.Register>> {
        val avoid = mutableMapOf<IntervalKey, MutableSet<Constants.Register>>()
        for (lineIdx in scopeLines.sorted()) {
            val (_, bodyTokens) = splitLabelPrefix(getCodeTokens(file, lineIdx))
            if (!FunctionSyntaxHelper.isCall(bodyTokens)) continue
            val call = parseCallStatement(bodyTokens) ?: continue
            val callee = allocatedFunctions[call.target.lowercase(Locale.ROOT)] ?: continue
            val destKey = call.dest?.let { resolveKeyByName(it, declaredRegisters) }

            for ((key, intv) in intervals) {
                if (key !is IntervalKey.LabelKey) continue
                if (key == destKey) continue
                val liveAcrossCall =
                    liveAfterByLine?.get(lineIdx)?.contains(key)
                        ?: isLiveAfterCall(intv, lineIdx, loops)
                if (liveAcrossCall) {
                    avoid.getOrPut(key) { mutableSetOf() }.addAll(callee.clobberedRegisters)
                }
            }
        }
        return avoid
    }
}
