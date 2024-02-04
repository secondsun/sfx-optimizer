package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.*
import dev.secondsun.retro.util.instruction.GSUInstruction
import dev.secondsun.retro.util.instruction.Instructions
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.retro.util.vo.Tokens
import dev.secondsun.sfxoptimizer.Constants.register
import java.net.URI



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
        val code = makeNode(file,line);
        val start = CodeNode.Start(code)

        return CodeGraph(start)
    }

    /**
     * Recursive
     * stes though a @param file starting at @param line.
     * You may provide an initial block with @param code
     */
    private fun makeNode(file: TokenizedFile, line: Int, code : CodeNode.CodeBlock = CodeNode.CodeBlock(Location(file.uri(), line,0,0))): CodeNode.CodeBlock {

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

            } else {
                visitedMap[Pair(file.uri, idx)] = code
            }
            val tokens = file.getLine(idx)
            if (tokens != null ) {

                if(isLabelDef(tokens) && code.lines.isNotEmpty()) {
                    val newNode = CodeNode.CodeBlock(Location(file.uri(), idx,0,0));
                    newNode.addEntrance(code)
                    code.addExit(newNode)
                    newNode.addLine(tokens)
                    visitedMap[Pair(file.uri, idx)] = newNode
                    makeNode(file, idx+1, newNode)
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
                        val nextBlock = makeNode(file, destinationLocation.line )

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
                    var nextBlock = makeNode(file, destinationLocation.line)

                    code.addExit(nextBlock)
                    nextBlock.addEntrance(code)

                    //don't take the branch
                    nextBlock = makeNode(file, idx + 2)

                    code.addExit(nextBlock)
                    nextBlock.addEntrance(code)
                    break;
                } else { // Not a jump, handle register and variable intervals
                    val firstToken = tokens[0]
                    if (GSUInstruction.isInstruction(firstToken)) {
                        val instruction = Instructions.values().find { it.instruction.matches(tokens) }
                        if (instruction != null) {
                            when(instruction){
                                Instructions.FROM -> {sReg = (tokens[1])}
                                Instructions.TO ->   {dReg = (tokens[1])}
                                Instructions.WITH -> {
                                    sReg = (tokens[1])
                                    dReg = (tokens[1])
                                }
                                Instructions.JMP -> code.addRead(tokens[1])
                                Instructions.LJMP -> code.addRead(tokens[1])
                                Instructions.BRA -> {}
                                Instructions.IWT_JUMP -> {}
                                Instructions.ADC_REGISTER -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);code.addRead(tokens[1])}
                                Instructions.ADC_CONST -> TODO()
                                Instructions.ADD_REGISTER -> TODO()
                                Instructions.ADD_CONST -> TODO()
                                Instructions.ALT1 -> TODO()
                                Instructions.ALT2 -> TODO()
                                Instructions.ALT3 -> TODO()
                                Instructions.AND_REGISTER -> TODO()
                                Instructions.AND_CONST -> TODO()
                                Instructions.ASR -> {useSreg(code, firstToken.lineNumber);useDreg(code,firstToken.lineNumber);}
                                Instructions.BCC -> TODO()
                                Instructions.BCS -> TODO()
                                Instructions.BEQ -> TODO()
                                Instructions.BGE -> TODO()
                                Instructions.BIC_REGISTER -> TODO()
                                Instructions.BIC_CONST -> TODO()
                                Instructions.BLT -> TODO()
                                Instructions.BMI -> TODO()
                                Instructions.BNE -> TODO()
                                Instructions.BPL -> TODO()
                                Instructions.BVC -> TODO()
                                Instructions.BVS -> TODO()
                                Instructions.CACHE -> TODO()
                                Instructions.CMODE -> TODO()
                                Instructions.CMP -> TODO()
                                Instructions.COLOR -> TODO()
                                Instructions.DEC -> TODO()
                                Instructions.DIV2 -> TODO()
                                Instructions.FMULT -> TODO()

                                Instructions.GETB -> TODO()
                                Instructions.GETBH -> TODO()
                                Instructions.GETBL -> TODO()
                                Instructions.GETBS -> TODO()
                                Instructions.GETC -> TODO()
                                Instructions.HIB -> TODO()
                                Instructions.IBT -> code.addWrite(tokens[1])
                                Instructions.INC -> TODO()
                                Instructions.IWT -> code.addWrite(tokens[1])
                                Instructions.LDB -> TODO()
                                Instructions.LDW -> TODO()
                                Instructions.LINK -> TODO()
                                Instructions.LM -> TODO()
                                Instructions.LMS -> TODO()
                                Instructions.LMULT -> TODO()
                                Instructions.LOB -> TODO()
                                Instructions.LOOP -> TODO()
                                Instructions.LSR -> TODO()
                                Instructions.MERGE -> TODO()
                                Instructions.MOVE -> TODO()
                                Instructions.MOVES -> TODO()
                                Instructions.MULT_REGISTER -> TODO()
                                Instructions.MULT_CONST -> TODO()
                                Instructions.NOP -> TODO()
                                Instructions.NOT -> TODO()
                                Instructions.OR_REGISTER -> TODO()
                                Instructions.OR_CONST -> TODO()
                                Instructions.PLOT -> TODO()
                                Instructions.RAMB -> TODO()
                                Instructions.ROL -> TODO()
                                Instructions.ROMB -> TODO()
                                Instructions.ROR -> TODO()
                                Instructions.RPIX -> TODO()
                                Instructions.SBC -> TODO()
                                Instructions.SBK -> TODO()
                                Instructions.SEX -> TODO()
                                Instructions.SM -> TODO()
                                Instructions.SMS -> TODO()
                                Instructions.STB -> TODO()
                                Instructions.STOP -> TODO()
                                Instructions.STW -> {useSreg(code, firstToken.lineNumber);dReg=null;code.addRead(tokens[2])}
                                Instructions.SUB_REGISTER -> TODO()
                                Instructions.SUB_CONST -> TODO()
                                Instructions.SWAP -> TODO()

                                Instructions.UMULT_REGISTER -> TODO()
                                Instructions.UMULT_CONST -> TODO()

                                Instructions.XOR_REGISTER -> TODO()
                                Instructions.XOR_CONST -> TODO()
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



class CodeGraph(val startNode:CodeNode.Start, val end : CodeNode.End = CodeNode.End) {

    private var _nodeCount = 0
    val nodeCount get() = _nodeCount
    init {
        _nodeCount = 2//start node and end node
        _nodeCount += countChildren(startNode.main)
    }

    private fun countChildren(node : CodeNode.CodeBlock, visited : MutableSet<CodeNode.CodeBlock> = mutableSetOf()) :Int {

        if (visited.contains(node)) {
            return visited.size;
        }
        visited.add(node)

        node.exits.forEach { exitNode ->
            when (exitNode) {
                is CodeNode.Start -> throw IllegalStateException("Start nodes can't be children")
                is CodeNode.CodeBlock -> countChildren(exitNode, visited)
                is CodeNode.End -> {}
            }
        }
        return visited.size
    }

    fun print(node : CodeNode.CodeBlock = startNode.main, visited : MutableSet<CodeNode.CodeBlock> = mutableSetOf(),indent :String = "") :String {

        if (visited.contains(node)) {
            return "";
        }
        val builder = StringBuilder()
        visited.add(node)
        builder.appendLine("---- Node start ---")
        builder.appendLine("Hashcode \t: ${node.hashCode()}")
        builder.appendLine("Entrances \t: ${node.entrances.joinToString(","){it->it.hashCode().toString()}}" )
        builder.appendLine("Exits \t\t: ${node.exits.joinToString(","){it->it.hashCode().toString()}}" )
        builder.appendLine(node.lines.joinToString("") { tokens ->  indent + tokens.line })

        node.exits.forEach { exitNode ->
            when (exitNode) {
                is CodeNode.Start -> throw IllegalStateException("Start nodes can't be children")
                is CodeNode.CodeBlock -> builder.appendLine(print(exitNode, visited, indent + "\t"))
                is CodeNode.End -> {}
            }
        }
        builder.appendLine("---- Node end ---")
        return builder.toString()
    }


    fun start(): CodeNode.Start {
        return startNode
    }




    fun end(): CodeNode.End {
        return end
    }

}

sealed class CodeNode {
    internal var _entrances = mutableListOf<CodeNode>()
    internal var _exits = mutableListOf<CodeNode>()

    val entrances get() = _entrances.toList()
    val exits get() = _exits.toList()

    fun addEntrance(entry : CodeNode) : CodeNode {
        _entrances.add(entry)
        return this
    }

    fun addExit(entry : CodeNode) : CodeNode {
        _exits.add(entry)
        return this
    }

    fun removeEntrance(entry : CodeNode) : CodeNode {
        _entrances.remove(entry)
        return this
    }

    fun removeExit(entry : CodeNode) : CodeNode {
        _exits.remove(entry)
        return this
    }


    enum class Attribute{ VISITED }

    val attributes = mutableSetOf<Attribute>()

    data class Start(val main:CodeNode.CodeBlock) : CodeNode() {
        fun mainMethod(): CodeNode.CodeBlock {
            return main
        }


    }

    data object End : CodeNode()

    class CodeBlock(var loc : Location) : CodeNode() {
        private val _intervals = mutableMapOf<IntervalKey, Interval>()
        val intervals get() = _intervals.toMap()

        val registersUsed: List<Constants.Register> get() = (_intervals.keys.filter { it is IntervalKey.RegisterKey }.map { (it as IntervalKey.RegisterKey).register })
        private var _lines = mutableListOf<Tokens>()

        val lines get() = _lines.toList()

        fun addLine(entry : Tokens) : CodeBlock {
            _lines.add(entry)
            return this
        }


         fun addWrite(token: Token) {
             if (Constants.isRegister(token.text())) {
                 register(token.text())?.let {
                     val key = IntervalKey.RegisterKey(it)
                     val interval = _intervals[key]?:Interval(key)
                     interval.addWrite(token.lineNumber)
                     _intervals.put(key, interval)
                 }

             } else {
                 TODO("Add Label interval")
             }

        }

         fun addWrite(register: Constants.Register, lineNumber:Int) {
             val key = IntervalKey.RegisterKey(register)
             val interval = _intervals[key]?:Interval(key)
             interval.addWrite(lineNumber)
             _intervals.put(key, interval)
        }
         fun addRead(register: Constants.Register, lineNumber:Int) {
             val key = IntervalKey.RegisterKey(register)
             val interval = _intervals[key]?:Interval(key)
             interval.addRead(lineNumber)
             _intervals.put(key, interval)
        }
        fun addRead(token: Token) {
            if (Constants.isRegister(token.text())) {
                register(token.text())?.let {
                    val key = IntervalKey.RegisterKey(it)
                    val interval = _intervals[key]?:Interval(key)
                    interval.addRead(token.lineNumber)
                    _intervals.put(key, interval)
                }

            } else {
                TODO("Add Label interval")
            }
        }


        fun hasAttribute(attr: Attribute): Boolean {
            return attributes.contains(attr)
        }
        fun setAttribute(attr: Attribute){
            attributes.add(attr)
        }

        fun split(idx: Int): Pair<CodeNode.CodeBlock, CodeNode.CodeBlock> {
            //make two new nodes
            //link nodes
            //move this.entrances to newNodes[0]
            //move this.exits to newNode[1]


            val block2Loc = Location(loc.filename, idx,0,0);


            val block2 = CodeBlock(block2Loc)

            for ( line:Int in idx..<lines.size + loc.line) {
                block2.addLine(lines[line-loc.line])
            }

            _lines = mutableListOf<Tokens>().apply {
                addAll(_lines.subList(0,idx-loc.line))
            }


            block2.addEntrance(this)

            exits.forEach( { node ->
                node.removeEntrance(this)
                node.addEntrance(block2)
                block2.addExit(node)
            })

            exits.forEach({removeExit(it)})

            this.addExit(block2)

            return Pair(this,block2)
        }

    }
}
