package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.*
import dev.secondsun.retro.util.instruction.GSUInstruction
import dev.secondsun.retro.util.instruction.Instructions
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.retro.util.vo.Tokens
import java.net.URI
import kotlin.collections.MutableSet


typealias FileName = URI
typealias LineNumber = Int
class CA65Grapher(val symbolService: SymbolService = SymbolService(), val fileService: FileService = FileService()) {

    private var sReg: Token? = null
    private var dReg: Token? = null

    val visitedMap = mutableMapOf<Pair<FileName, LineNumber>, CodeNode.CodeBlock>()

    private fun isConditionalJump(tokens: Tokens): Boolean {
        return GSUInstruction.conditionalJumpInstructions.stream().anyMatch { it.matches(tokens) };
    }

    private fun isUnconditionalJump(tokens: Tokens): Boolean {
        return GSUInstruction.unconditionalJumpInstructions.stream().anyMatch { it.matches(tokens) };
    }

    fun graph(file: TokenizedFile, line: Int): CodeGraph {
        val mainNode = makeNode(file,line);
        val start = CodeNode.Start(mainNode)
        val programGraph = CodeGraph(start)
        programGraph.traverse({node ->
            when(node) {
                is CodeNode.FunctionStart -> programGraph.addFunction(node.functionName, node)
                else -> {}
            }
        })

        return programGraph
    }

    private fun graphFunction(functionName : String): CodeNode.FunctionStart {
        val location = symbolService.getLocation(functionName)

        val functionBody = graph(fileService.readLines(location.filename), location.line+1)
        val functionNode = CodeNode.FunctionStart(functionName, location, functionBody)
        return functionNode;
    }

    /**
     * Recursive
     * steps though a @param file starting at @param line.
     * You may provide an initial block with @param code
     */
    private fun makeNode(file: TokenizedFile, line: Int, code : CodeNode.CodeBlock = CodeNode.CodeBlock(Location(file.uri(), line,0,0)), registerLabels: MutableSet<String> = mutableSetOf<String>()): CodeNode.CodeBlock {

        for (idx in line..<file.textLines()) {
            if (visitedMap[Pair(file.uri, idx)] != null) { //Have we jumped/stepped into an existing block?
                val nextBlock = visitedMap[Pair(file.uri, idx)]!!

                if (nextBlock.loc.line == idx && line == idx) {//we jumped to the start of a block, no split needed
                    return nextBlock
                } else if (nextBlock.loc.line == idx ) { //we stepped into an existing block. create exits and return
                    code.addExit(nextBlock)
                    nextBlock.addEntrance(code)
                    return code
                } else { //We jumped into the middle of a block. We must split it and rearrange old entrances and exits
                    val splitBlocks : Pair<CodeNode.CodeBlock, CodeNode.CodeBlock> = nextBlock.split(idx)

                    val block2 = splitBlocks.second
                    val block2Start = idx
                    val block2End = idx + block2.lines.size
                    for ( lineNu in block2Start..<block2End) {
                        visitedMap[Pair(block2.loc.filename ,lineNu)] = block2
                    }
                    return block2
                }

            } else { //This is a new line in the code block.
                visitedMap[Pair(file.uri, idx)] = code
            }
            val tokens = file.getLine(idx)
            if (tokens != null ) {

                if(isLabelDef(tokens) && code.lines.isNotEmpty()) {//label definitions start a new block
                    //Close current block and start a new one.
                    val newNode = CodeNode.CodeBlock(Location(file.uri(), idx,0,0));
                    newNode.addEntrance(code)
                    code.addExit(newNode)
                    newNode.addLine(tokens)
                    visitedMap[Pair(file.uri, idx)] = newNode
                    makeNode(file, idx+1, newNode, registerLabels)
                    return code
                } else if (isUnconditionalJump(tokens)) {
                    code.addLine(tokens)
                    if (idx == file.textLines()) {
                        tokens.tokens[0].addAttribute(TokenAttribute.ERROR)
                        tokens.tokens[0].message = "Unexpected end of file, branch instructions are always followed by an instruction"
                    } else {
                        val nextLine = file.getLine(idx+ 1) ;
                        code.addLine(nextLine)
                        val dest = tokens.tokens[1]
                        //import next token
                        //create exits
                        val destinationLocation = symbolService.getLocation(dest.text())
                        val nextBlock = makeNode(file, destinationLocation.line, registerLabels =  registerLabels )

                        code.addExit(nextBlock)
                        nextBlock.addEntrance(code)
                    }
                    break;
                } else if (isConditionalJump(tokens)) {
                    code.addLine(tokens)
                    val nextLine = file.getLine(idx+ 1) ;
                    code.addLine(nextLine)

                    // take the branch
                    var dest = tokens.tokens[1]
                    //import next token
                    //create exits
                    var destinationLocation = symbolService.getLocation(dest.text())
                    var nextBlock = makeNode(file, destinationLocation.line, registerLabels =  registerLabels )

                    code.addExit(nextBlock)
                    nextBlock.addEntrance(code)

                    //don't take the branch
                    nextBlock = makeNode(file, idx + 2, registerLabels =  registerLabels )

                    code.addExit(nextBlock)
                    nextBlock.addEntrance(code)
                    break;
                } else if (isReturnOrEndFunction(tokens)) {//handle return
                    code.addLine(tokens)
                    code.addExit(CodeNode.End)
                    break;
                } else if (isCall(tokens)) {
                    val functionNode = graphFunction(tokens.tokens[1].text().trim());
                    val callBlock = CodeNode.CallBlock(functionNode, tokens.tokens[0].lineNumber)
                    code.addExit(callBlock)
                    callBlock.addEntrance(code)

                    val nextBlock = makeNode(file, idx + 1, registerLabels =  registerLabels )

                    callBlock.addExit(nextBlock)
                    nextBlock.addEntrance(callBlock)
                    break;
                } else if (isRegisterVariableDeclaration(tokens)) {
                    handleRegisterVariableDeclaration(tokens, registerLabels)
                    code.addLine(tokens)
                } else { // Not a jump, handle register and variable intervals
                    val firstToken = tokens[0]
                    if (GSUInstruction.isInstruction(firstToken)) {
                        checkAndApplyRegisterLabelAttribute(tokens, registerLabels);
                        val instruction = Instructions.entries.find { it.instruction.matches(tokens) }
                        if (instruction != null) {
                            when(instruction){
                                Instructions.FROM -> {sReg = (tokens[1])}
                                Instructions.TO ->   {dReg = (tokens[1])}
                                Instructions.WITH -> {
                                    sReg = (tokens[1])
                                    dReg = (tokens[1])
                                }
                                Instructions.JMP -> {sReg = null;dReg = null;code.addRead(tokens[1])}
                                Instructions.LJMP -> {sReg = null;dReg = null;code.addRead(tokens[1])}
                                Instructions.BRA -> {}
                                Instructions.IWT_JUMP -> {sReg = null;dReg = null;}
                                Instructions.ADC_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.ADC_CONST -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.ADD_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.ADD_CONST -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.ALT1 -> {}
                                Instructions.ALT2 -> {}
                                Instructions.ALT3 -> {}
                                Instructions.AND_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.AND_CONST -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.ASR -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.BCC -> {}
                                Instructions.BCS -> {}
                                Instructions.BEQ -> {}
                                Instructions.BGE -> {}
                                Instructions.BIC_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.BIC_CONST -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.BLT -> {}
                                Instructions.BMI -> {}
                                Instructions.BNE -> {}
                                Instructions.BPL -> {}
                                Instructions.BVC -> {}
                                Instructions.BVS -> {}
                                Instructions.CACHE -> {sReg = null;dReg = null}
                                Instructions.CMODE -> {useSreg(code, firstToken.lineNumber);dReg = null}
                                Instructions.CMP -> {useSreg(code, firstToken.lineNumber);dReg = null}
                                Instructions.COLOR -> {useSreg(code, firstToken.lineNumber);dReg = null}
                                Instructions.DEC -> {sReg = null;dReg = null;code.addRead(tokens[1]);code.addWrite(tokens[1])}
                                Instructions.DIV2 -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.FMULT -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(Constants.Register.R6, firstToken.lineNumber)}

                                Instructions.GETB -> {sReg = null;useDreg(code, firstToken.lineNumber); code.addRead(Constants.Register.R14, firstToken.lineNumber)}
                                Instructions.GETBH -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber); code.addRead(Constants.Register.R14, firstToken.lineNumber)}
                                Instructions.GETBL -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber); code.addRead(Constants.Register.R14, firstToken.lineNumber)}
                                Instructions.GETBS ->{sReg = null;useDreg(code, firstToken.lineNumber); code.addRead(Constants.Register.R14, firstToken.lineNumber)}
                                Instructions.GETC -> {sReg = null;dReg = null; code.addRead(Constants.Register.R14, firstToken.lineNumber)}
                                Instructions.HIB -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.IBT -> {sReg = null;dReg = null;code.addWrite(tokens[1])}
                                Instructions.INC -> {sReg = null;dReg = null;code.addRead(tokens[1]);code.addWrite(tokens[1])}
                                Instructions.IWT -> {sReg = null;dReg = null;code.addWrite(tokens[1])}
                                Instructions.LDB -> {sReg = null;useDreg(code, firstToken.lineNumber);code.addRead(tokens[2]); }
                                Instructions.LDW -> {sReg = null;useDreg(code, firstToken.lineNumber);code.addRead(tokens[2]); }
                                Instructions.LINK -> {sReg = null;dReg = null;code.addWrite(Constants.Register.R11, firstToken.lineNumber)}
                                Instructions.LM -> {sReg = null;dReg = null;code.addWrite(tokens[1])}
                                Instructions.LMS -> {sReg = null;dReg = null;code.addWrite(tokens[1])}
                                Instructions.LMULT -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(Constants.Register.R6, firstToken.lineNumber);code.addWrite(Constants.Register.R4, firstToken.lineNumber)}
                                Instructions.LOB -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.LOOP -> {code.addRead(Constants.Register.R13, firstToken.lineNumber);code.addWrite(Constants.Register.R12, firstToken.lineNumber)}
                                Instructions.LSR -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.MERGE -> {useDreg(code, firstToken.lineNumber);sReg = null;code.addRead(Constants.Register.R7, firstToken.lineNumber);code.addRead(Constants.Register.R8, firstToken.lineNumber)}
                                Instructions.MOVE -> {sReg = null;dReg = null;code.addWrite(tokens[1]);code.addRead(tokens[3])}
                                Instructions.MOVES -> {sReg = null;dReg = null;code.addWrite(tokens[1]);}
                                Instructions.MULT_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.MULT_CONST -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.NOP -> {sReg = null;dReg = null;}
                                Instructions.NOT ->  {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.OR_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.OR_CONST ->    {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.PLOT -> {code.addRead(Constants.Register.R1, firstToken.lineNumber);code.addRead(Constants.Register.R2, firstToken.lineNumber) }
                                Instructions.RAMB -> {useSreg(code, firstToken.lineNumber); dReg=null }
                                Instructions.ROL -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.ROMB -> {useSreg(code, firstToken.lineNumber); dReg=null }
                                Instructions.ROR -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.RPIX -> {sReg = null; useDreg(code,firstToken.lineNumber);code.addRead(Constants.Register.R2, firstToken.lineNumber);code.addRead(Constants.Register.R1, firstToken.lineNumber)}
                                Instructions.SBC -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.SBK -> {useSreg(code, firstToken.lineNumber);dReg = null}
                                Instructions.SEX -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.SM -> {sReg = null;dReg = null;code.addRead(tokens[5])}
                                Instructions.SMS -> {sReg = null;dReg = null;code.addRead(tokens[2])}
                                Instructions.STB -> {useSreg(code, firstToken.lineNumber);dReg=null;code.addRead(tokens[2])}
                                Instructions.STOP -> {sReg = null;dReg = null;}
                                Instructions.STW -> {useSreg(code, firstToken.lineNumber);dReg=null;code.addRead(tokens[2])}
                                Instructions.SUB_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.SUB_CONST ->    {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.SWAP -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}

                                Instructions.UMULT_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.UMULT_CONST ->    {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}

                                Instructions.XOR_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.XOR_CONST ->    {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
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

            if (idx == file.textLines()-1) {//end of file
                code.addExit(CodeNode.End)
            }
        }
        return code
    }

    private fun checkAndApplyRegisterLabelAttribute(tokens: Tokens, registerLabels: MutableSet<String>) {
        tokens.tokens.forEach {
            if (registerLabels.contains(it.text())) {
                it.addAttribute(TokenAttribute.REGISTER_LABEL)
            }
        }
    }

    private fun isRegisterVariableDeclaration(tokens: Tokens): Boolean {
        return tokens[0].type == TokenType.TOK_REGISTER_KEYWORD;
    }

    private fun handleRegisterVariableDeclaration(tokens: Tokens, registerLabels: MutableSet<String>) {
        if (tokens[0].type == TokenType.TOK_REGISTER_KEYWORD) {
            tokens.tokens.forEach {
                if (it.type == TokenType.TOK_IDENT) {
                    registerLabels.add(it.text())
                    it.addAttribute(TokenAttribute.REGISTER_LABEL)
                } else if (it.type == TokenType.TOK_COMMA) {}
                else {
                    it.addAttribute(TokenAttribute.ERROR)
                }
            }
        }
    }

    private fun isCall(tokens: Tokens): Boolean {
        return tokens.tokens.size == 2 && tokens.tokens[0].text().trim().lowercase().equals("call")
    }

    private fun isReturnOrEndFunction(tokens: Tokens): Boolean {
        val tokensList = tokens.tokens
        if (tokensList.size != 1) {
            return false;
        }
        val token = tokens[0].text().trim().lowercase()
        return token.equals("endfunction") || token.equals("return")
    }

    private fun useSreg(code:CodeNode.CodeBlock, line:Int) {

        if (sReg != null) {
            sReg!!.lineNumber = line
            code.addRead(sReg!!)
        } else {
            code.addRead(Constants.Register.R0, line)
        }

        sReg = null
    }

    private fun useDreg(code:CodeNode.CodeBlock, line:Int) {
        if (dReg != null) {
            dReg!!.lineNumber = line
            code.addWrite(dReg!!)
        } else {
            code.addWrite(Constants.Register.R0, line)
        }

        dReg = null
    }


    private fun isLabelDef(tokens: Tokens): Boolean {
        val tokensList =  tokens.tokens;
        return tokensList.size ==2 && tokensList[0].type == TokenType.TOK_IDENT && tokensList[1].type == TokenType.TOK_COLON
    }


}

private operator fun Tokens.get(i: Int): Token {
    return tokens[i]
}



