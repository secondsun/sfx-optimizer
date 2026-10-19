package dev.secondsun.sfxoptimizer.graphbuilder

import dev.secondsun.retro.util.FileService
import dev.secondsun.retro.util.FunctionSyntaxHelper
import dev.secondsun.retro.util.SymbolService
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenAttribute
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.instruction.GSUInstruction
import dev.secondsun.retro.util.instruction.Instructions
import dev.secondsun.retro.util.vo.Location
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.retro.util.vo.Tokens
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.IntervalKey
import dev.secondsun.sfxoptimizer.analysis.DeclaredRegister
import dev.secondsun.sfxoptimizer.analysis.SfxLivenessAnalyzer
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph
import dev.secondsun.sfxoptimizer.graphnode.CodeNode
import java.net.URI
import java.util.Locale

typealias FileName = URI
typealias LineNumber = Int

private enum class StepInType { NoBlock, StepToStartBlock, JumpToStartBlock, MidBlock }

class CA65Grapher(
    val symbolService: SymbolService = SymbolService(),
    val fileService: FileService = FileService(),
) {
    private var sReg: Token? = null
    private var dReg: Token? = null

    val visitedMap = mutableMapOf<Pair<FileName, LineNumber>, CodeNode.CodeBlock>()

    private fun isConditionalJump(tokens: Tokens): Boolean =
        Instructions.conditionalJumpInstructions.stream().anyMatch { it.matches(tokens) }

    private fun isUnconditionalJump(tokens: Tokens): Boolean =
        Instructions.unconditionalJumpInstructions.stream().anyMatch {
            it.matches(tokens)
        }

    fun graph(
        file: TokenizedFile,
        line: Int = 0,
    ): CodeGraph {
        val mainNode = makeNode(file, line)
        val start = CodeNode.Start(mainNode)
        val programGraph = CodeGraph(start)
        programGraph.traverse { node ->
            when (node) {
                is CodeNode.FunctionStart -> programGraph.addFunction(node.functionName, node)
                else -> {}
            }
        }

        return programGraph
    }

    fun graphFunction(functionName: String): CodeNode.FunctionStart {
        val location = symbolService.getLocation(functionName)

        var lines = fileService.readLines(location.filename)

        val functionLine = lines.getLine(location.line)
        val funcDecl = FunctionSyntaxHelper.parseFunction(functionLine)
        val params = funcDecl.parameters()
        val returnVar = funcDecl.returnVariable().orElse(null)

        val registerLabels = params.map { RegisterLabel(it.text()) }.toMutableSet()
        if (returnVar != null && !Constants.isRegister(returnVar.text())) {
            registerLabels.add(RegisterLabel(returnVar.text()))
        }

        val mainNode = makeNode(lines, location.line + 1, registerLabels = registerLabels)
        val start = CodeNode.Start(mainNode)
        val functionBody = CodeGraph(start)
        functionBody.traverse { node ->
            when (node) {
                is CodeNode.FunctionStart -> functionBody.addFunction(node.functionName, node)
                else -> {}
            }
        }

        val functionNode = CodeNode.FunctionStart(functionName, location, functionBody, params, returnVar)
        return functionNode
    }

    /**
     * steps though a @param file starting at @param line.
     * You may provide an initial block with @param code
     */
    private fun makeNode(
        file: TokenizedFile,
        line: Int,
        code: CodeNode.CodeBlock = CodeNode.CodeBlock(Location(file.uri(), line, 0, 0)),
        registerLabels: MutableSet<RegisterLabel> = mutableSetOf<RegisterLabel>(),
    ): CodeNode.CodeBlock {
        for (idx in line..<file.textLines()) {
            // Check to make sure we haven't stepped/jumped into an existing block
            val stepInType: StepInType = getStepInType(file, idx, line)

            when (stepInType) {
                StepInType.StepToStartBlock -> {
                    val nextBlock = visitedMap[Pair(file.uri, idx)]!!
                    code.addExit(nextBlock)
                    nextBlock.addEntrance(code)
                    return code
                }
                StepInType.JumpToStartBlock -> {
                    return visitedMap[Pair(file.uri, idx)]!!
                }
                StepInType.MidBlock -> {
                    val nextBlock = visitedMap[Pair(file.uri, idx)]!!
                    val splitBlocks: Pair<CodeNode.CodeBlock, CodeNode.CodeBlock> = nextBlock.split(idx)

                    val block2 = splitBlocks.second
                    val block2Start = idx
                    val block2End = idx + block2.lines.size
                    for (lineNu in block2Start..<block2End) {
                        visitedMap[Pair(block2.loc.filename, lineNu)] = block2
                    }
                    return block2
                }
                StepInType.NoBlock -> {
                    // Continue as normal
                }
            }

            visitedMap[Pair(file.uri, idx)] = code

            val tokens = file.getLine(idx)
            if (tokens != null) {
                if (isLabelDef(tokens) && code.lines.isNotEmpty()) { // label definitions start a new block
                    // Close current block and start a new one.
                    val newNode = CodeNode.CodeBlock(Location(file.uri(), idx, 0, 0))
                    newNode.addEntrance(code)
                    code.addExit(newNode)
                    newNode.addLine(tokens)
                    visitedMap[Pair(file.uri, idx)] = newNode
                    makeNode(file, idx + 1, newNode, registerLabels)
                    return code
                } else if (isUnconditionalJump(tokens)) {
                    code.addLine(tokens)
                    if (idx == file.textLines()) {
                        tokens.tokens[0].addAttribute(TokenAttribute.ERROR)
                        tokens.tokens[0].message = "Unexpected end of file, branch instructions are always followed by an instruction"
                    } else {
                        val nextLine = file.getLine(idx + 1)
                        code.addLine(nextLine)
                        val dest = tokens.tokens[1]
                        // import next token
                        // create exits
                        val destinationLocation = symbolService.getLocation(dest.text())
                        val nextBlock = makeNode(file, destinationLocation.line, registerLabels = registerLabels)

                        code.addExit(nextBlock)
                        nextBlock.addEntrance(code)
                    }
                    break
                } else if (isConditionalJump(tokens)) {
                    code.addLine(tokens)
                    val nextLine = file.getLine(idx + 1)
                    code.addLine(nextLine)

                    // take the branch
                    var dest = tokens.tokens[1]
                    // import next token
                    // create exits
                    var destinationLocation = symbolService.getLocation(dest.text())
                    var nextBlock = makeNode(file, destinationLocation.line, registerLabels = registerLabels)

                    code.addExit(nextBlock)
                    nextBlock.addEntrance(code)

                    // don't take the branch
                    nextBlock = makeNode(file, idx + 2, registerLabels = registerLabels)

                    code.addExit(nextBlock)
                    nextBlock.addEntrance(code)
                    break
                } else if (isForLoop(tokens)) {
                    code.addLine(tokens)
                    val endForLine: Int = findEndFor(file, idx)
                    if (endForLine == -1) {
                        tokens[0].addAttribute(TokenAttribute.ERROR)
                        tokens[0].message = "Missing endfor"
                    } else {
                        val forOrForR = tokens[0].text()
                        if (forOrForR.equals("for", true)) {
                            // add body as exit
                            code.addWrite(Constants.Register.R12, idx)
                            code.addWrite(Constants.Register.R13, idx)
                            var nextBlock = makeNode(file, idx + 1, registerLabels = registerLabels)

                            code.addExit(nextBlock)
                            nextBlock.addEntrance(code)

                            // add end of loop as exit
                            nextBlock = makeNode(file, endForLine + 1, registerLabels = registerLabels)
                            code.addWrite(Constants.Register.R12, endForLine)
                            code.addRead(Constants.Register.R12, endForLine)
                            code.addRead(Constants.Register.R13, endForLine)
                            code.addExit(nextBlock)
                            nextBlock.addEntrance(code)
                            break
                        } else { // check forR
                            val count = tokens[1]
                            if (count.type != TokenType.TOK_REGISTER && !registerLabels.contains(RegisterLabel(count.text()))) {
                                tokens[0].addAttribute(TokenAttribute.ERROR)
                                tokens[0].message = "Register required"
                            }
                            // add body as exit
                            code.addWrite(Constants.Register.R12, idx)
                            code.addWrite(Constants.Register.R13, idx)
                            code.addRead(count)
                            count.addMetadata(TokenAttribute.REGISTER_LABEL, registerLabels.find { it.label == count.text() })

                            var nextBlock = makeNode(file, idx + 1, registerLabels = registerLabels)

                            code.addExit(nextBlock)
                            nextBlock.addEntrance(code)

                            // add end of loop as exit
                            nextBlock = makeNode(file, endForLine + 1, registerLabels = registerLabels)
                            code.addWrite(Constants.Register.R12, endForLine)
                            code.addRead(Constants.Register.R12, endForLine)
                            code.addRead(Constants.Register.R13, endForLine)
                            code.addExit(nextBlock)
                            nextBlock.addEntrance(code)
                            break
                        }
                    }
                } else if (isEndFor(tokens)) {
                    code.addLine(tokens)
                    tokens[0].addAttribute(TokenAttribute.ERROR)
                    tokens[0].message = "Unexpected endfor"
                } else if (isReturnOrEndFunction(tokens)) { // handle return or endfunction
                    if (FunctionSyntaxHelper.isReturn(tokens)) {
                        val returnStmt = FunctionSyntaxHelper.parseReturn(tokens)
                        returnStmt.returnVariable().ifPresent { retVar ->
                            code.addRead(retVar)
                        }
                    }
                    code.addLine(tokens)
                    code.addExit(CodeNode.End)
                    break
                } else if (isCall(tokens)) {
                    val callStmt = FunctionSyntaxHelper.parseCall(tokens)
                    val functionNode = graphFunction(callStmt.targetName())
                    val callBlock =
                        CodeNode.CallBlock(
                            function = functionNode,
                            line = tokens.tokens[0].lineNumber,
                            tokens = tokens,
                            destinationVariable = callStmt.destinationVariable().orElse(null),
                            arguments = callStmt.arguments(),
                        )

                    checkFunctionTypesMatchAndCreateIntervals(callBlock, functionNode, tokens, registerLabels)
                    code.addExit(callBlock)
                    callBlock.addEntrance(code)

                    val nextBlock = makeNode(file, idx + 1, registerLabels = registerLabels)

                    callBlock.addExit(nextBlock)
                    nextBlock.addEntrance(callBlock)
                    break
                } else if (isRegisterVariableDeclaration(tokens)) { // register
                    if (isRegisterVariableAssignment(tokens, registerLabels)) { // check for register $label = $register
                        registerLabels.add(
                            RegisterLabel(tokens.tokens[1].text(), Constants.Register.valueOf(tokens[3].text().toString().uppercase())),
                        )
                    } // you so pretty
                    handleRegisterVariableDeclaration(tokens, registerLabels)
                    code.addLine(tokens)
                } else { // Not a jump, handle register and variable intervals
                    val firstToken = tokens[0]
                    if (GSUInstruction.isInstruction(firstToken)) {
                        checkAndApplyRegisterLabelAttribute(tokens, registerLabels)
                        val instruction = Instructions.entries.find { it.instruction.matches(tokens) }
                        if (instruction != null) {
                            val mnemonic = firstToken.text().lowercase(Locale.ROOT)
                            when (mnemonic) {
                                "from" -> {
                                    sReg = tokens[1]
                                }
                                "to" -> {
                                    dReg = tokens[1]
                                }
                                "with" -> {
                                    sReg = tokens[1]
                                    dReg = tokens[1]
                                }
                                "alt1", "alt2", "alt3" -> {
                                    // Preserves pending modal registers
                                }
                                else -> {
                                    val declaredMap =
                                        registerLabels.associate {
                                            it.label.lowercase(Locale.ROOT) to
                                                DeclaredRegister(it.label, it.register, firstToken.lineNumber)
                                        }
                                    val sKey = sReg?.let { SfxLivenessAnalyzer.resolveKey(it, declaredMap) }
                                    val dKey = dReg?.let { SfxLivenessAnalyzer.resolveKey(it, declaredMap) }
                                    val effect =
                                        SfxLivenessAnalyzer.evaluateLineEffect(
                                            tokens = tokens.tokens,
                                            currentPendingSource = sKey,
                                            currentPendingDest = dKey,
                                            declaredRegisters = declaredMap,
                                            lineIdx = firstToken.lineNumber,
                                        )

                                    val r0Key = IntervalKey.RegisterKey(Constants.Register.R0)
                                    if (effect.implicitReads.contains(sKey ?: r0Key)) {
                                        useSreg(code, firstToken.lineNumber)
                                    } else if (effect.nextPendingSource == null) {
                                        sReg = null
                                    }

                                    if (effect.implicitWrites.contains(dKey ?: r0Key)) {
                                        useDreg(code, firstToken.lineNumber)
                                    } else if (effect.nextPendingDest == null) {
                                        dReg = null
                                    }

                                    effect.explicitReads.forEach { (tok, _) -> code.addRead(tok) }
                                    effect.explicitWrites.forEach { (tok, _) -> code.addWrite(tok) }
                                    effect.implicitReads.forEach { key ->
                                        if (key is IntervalKey.RegisterKey && key != sKey) {
                                            code.addRead(key.register, firstToken.lineNumber)
                                        }
                                    }
                                    effect.implicitWrites.forEach { key ->
                                        if (key is IntervalKey.RegisterKey && key != dKey) {
                                            code.addWrite(key.register, firstToken.lineNumber)
                                        }
                                    }
                                }
                            }
                        } else {
                            firstToken.apply {
                                addAttribute(TokenAttribute.ERROR)
                                message = "${firstToken.text()} is incorrect. Correct form : TODO"
                            }
                        }
                    }
                    code.addLine(tokens)
                }
            } else {
                code.addLine(Tokens("", emptyList()))
            }

            if (idx == file.textLines() - 1) { // end of file
                code.addExit(CodeNode.End)
            }
        }
        return code
    }

    /**
     * Returns the line number of the end of the for loop
     *
     * Recursive
     *
     * @param file is the file the for loop is in
     * @param idx is the index of the start of the for loop
     *
     * @throws IllegalArgumentException if idx is not a for loop start
     *
     * @return -1 if no endFor, otherwise line of endfor
     */
    private fun findEndFor(
        file: TokenizedFile,
        idxIn: Int,
    ): Int {
        var idx = idxIn
        var line = file.getLine(idx)

        if (!isForLoop(line)) {
            throw IllegalArgumentException("find EndFor is not started on a for loop")
        }

        while (line != null && line[0].type != TokenType.TOK_EOF) {
            idx += 1
            line = file.getLine(idx)

            if (line != null && isEndFor(line)) {
                return idx
            }

            if (line != null && isForLoop(line)) {
                idx = 1 + findEndFor(file, idx)
            }
        }

        file.getLine(idxIn).apply {
            tokens[0].addAttribute(TokenAttribute.ERROR)
            tokens[0].message = "Missing endfor"
        }

        return -1
    }

    private fun isEndFor(tokens: Tokens): Boolean {
        val firstToken = tokens[0]
        if (firstToken.text().lowercase().equals("endfor")) {
            // error checking
            if (tokens.tokens.size != 1) {
                firstToken.message = "End for takes no params"
                firstToken.addAttribute(TokenAttribute.ERROR)
                return false
            }

            return true
        }
        return false
    }

    private fun isForLoop(tokens: Tokens): Boolean {
        val firstToken = tokens[0]
        if (firstToken.text().lowercase().equals("for")) {
            // error checking
            if (tokens.tokens.size != 2) {
                firstToken.message = "For loops have only one parameter"
                firstToken.addAttribute(TokenAttribute.ERROR)
                return false
            } else {
                val secondToken = tokens[1]
                if (secondToken.type != TokenType.TOK_INTCON) {
                    secondToken.message = "For loops require an int"
                    secondToken.addAttribute(TokenAttribute.ERROR)
                    return false
                }
            }

            return true
        } else if (firstToken.text().equals("forR")) {
            if (tokens.tokens.size != 2) {
                firstToken.message = "For loops have only one parameter"
                firstToken.addAttribute(TokenAttribute.ERROR)
                return false
            } else {
                val secondToken = tokens[1]
                if (secondToken.type != TokenType.TOK_REGISTER && secondToken.type != TokenType.TOK_IDENT) {
                    secondToken.message = "ForR loops require an register"
                    secondToken.addAttribute(TokenAttribute.ERROR)
                    return false
                } else {
                    if (secondToken.type == TokenType.TOK_IDENT) {
                        secondToken.addAttribute(TokenAttribute.REGISTER_LABEL)
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun getStepInType(
        file: TokenizedFile,
        idx: Int,
        line: Int,
    ): StepInType {
        if (visitedMap[Pair(file.uri, idx)] != null) { // Have we jumped/stepped into an existing block?
            val nextBlock = visitedMap[Pair(file.uri, idx)]!!

            return if (nextBlock.loc.line == idx && line == idx) { // we jumped to the start of a block, no split needed
                StepInType.JumpToStartBlock
            } else if (nextBlock.loc.line == idx) { // we stepped into an existing block. create exits and return
                StepInType.StepToStartBlock
            } else { // We jumped into the middle of a block. We must split it and rearrange old entrances and exits
                StepInType.MidBlock
            }
        } else { // This is a new line in the code block.
            return StepInType.NoBlock
        }
    }

    private fun checkFunctionTypesMatchAndCreateIntervals(
        callBlock: CodeNode.CallBlock,
        functionNode: CodeNode.FunctionStart,
        callBlockTokens: Tokens,
        registerLabels: MutableSet<RegisterLabel>,
    ) {
        val callStmt = FunctionSyntaxHelper.parseCall(callBlockTokens)
        if (callStmt.hasErrors()) {
            return
        }
        val args = callStmt.arguments()
        if (args.size != functionNode.params.size) {
            callStmt.callToken().addAttribute(TokenAttribute.ERROR)
            callStmt.callToken().message = "${functionNode.functionName} requires ${functionNode.params.size} parameters"
        } else {
            args.forEach { param ->
                if (!Constants.isRegister(param.text()) && !registerLabels.contains(RegisterLabel(param.text()))) {
                    param.addAttribute(TokenAttribute.ERROR)
                    param.message = "undeclared param"
                }
            }
        }

        callStmt.destinationVariable().ifPresent { dest ->
            if (!Constants.isRegister(dest.text()) && !registerLabels.contains(RegisterLabel(dest.text()))) {
                dest.addAttribute(TokenAttribute.ERROR)
                dest.message = "undeclared param"
            }
        }
    }

    private fun isRegisterVariableAssignment(
        tokens: Tokens,
        registerLabels: MutableSet<RegisterLabel>,
    ): Boolean {
        if (tokens.tokens.size == 4 && tokens[0].text().equals("register") && tokens[2].type == TokenType.TOK_EQ) {
            return true
        } else {
            return false
        }
    }

    private fun checkAndApplyRegisterLabelAttribute(
        tokens: Tokens,
        registerLabels: MutableSet<RegisterLabel>,
    ) {
        tokens.tokens.forEach { token ->
            val label = token.text()
            if (registerLabels.contains(RegisterLabel(label))) {
                val rl = registerLabels.find { registerLabel -> registerLabel.label.equals(label) }
                if (rl != null) {
                    token.addAttribute(TokenAttribute.REGISTER_LABEL)
                    token.addMetadata(TokenAttribute.REGISTER_LABEL, rl.register)
                }
            }
        }
    }

    private fun isRegisterVariableDeclaration(tokens: Tokens): Boolean = tokens[0].type == TokenType.TOK_REGISTER_KEYWORD

    private fun handleRegisterVariableDeclaration(
        tokens: Tokens,
        registerLabels: MutableSet<RegisterLabel>,
    ) {
        if (tokens[0].type == TokenType.TOK_REGISTER_KEYWORD) {
            tokens.tokens.forEach {
                if (it.type == TokenType.TOK_IDENT) {
                    registerLabels.add(RegisterLabel(it.text()))
                    it.addAttribute(TokenAttribute.REGISTER_LABEL)
                } else if (it.type == TokenType.TOK_COMMA) {
                } else if (tokens[0].type == TokenType.TOK_REGISTER_KEYWORD) {
                } else {
                    it.addAttribute(TokenAttribute.ERROR)
                }
            }
        }
    }

    private fun isCall(tokens: Tokens): Boolean {
        if (!FunctionSyntaxHelper.isCall(tokens)) {
            return false
        }

        val callStmt = FunctionSyntaxHelper.parseCall(tokens)
        val targetName = callStmt.targetName()
        if (targetName.isBlank()) {
            return false
        }

        val functionLocation = this.symbolService.getLocation(targetName)
        if (functionLocation != null) {
            return true
        } else {
            callStmt.targetToken().apply {
                addAttribute(TokenAttribute.ERROR)
                message = "${text()} is not a function name"
            }
            return false
        }
    }

    private fun isReturnOrEndFunction(tokens: Tokens): Boolean =
        FunctionSyntaxHelper.isReturn(tokens) || FunctionSyntaxHelper.isEndFunction(tokens)

    private fun useSreg(
        code: CodeNode.CodeBlock,
        line: Int,
    ) {
        if (sReg != null) {
            sReg!!.lineNumber = line
            code.addRead(sReg!!)
        } else {
            code.addRead(Constants.Register.R0, line)
        }

        sReg = null
    }

    private fun useDreg(
        code: CodeNode.CodeBlock,
        line: Int,
    ) {
        if (dReg != null) {
            dReg!!.lineNumber = line
            code.addWrite(dReg!!)
        } else {
            code.addWrite(Constants.Register.R0, line)
        }

        dReg = null
    }

    private fun isLabelDef(tokens: Tokens): Boolean {
        val tokensList = tokens.tokens
        return tokensList.size == 2 && tokensList[0].type == TokenType.TOK_IDENT && tokensList[1].type == TokenType.TOK_COLON
    }
}

private operator fun Tokens.get(i: Int): Token = tokens[i]
