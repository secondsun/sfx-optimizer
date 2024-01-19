package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.FileService
import dev.secondsun.retro.util.Location
import dev.secondsun.retro.util.SymbolService
import dev.secondsun.retro.util.TokenAttribute
import dev.secondsun.retro.util.instruction.GSUInstruction
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.retro.util.vo.Tokens
import java.net.URI

typealias FileName = URI
typealias LineNumber = Int
class CA65Grapher(val symbolService: SymbolService = SymbolService(), val fileService: FileService = FileService()) {

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

    private fun makeNode(file: TokenizedFile, line: Int): CodeNode.CodeBlock {
        val code : CodeNode.CodeBlock = CodeNode.CodeBlock(Location(file.uri(), line,0,0));

        for (idx in line..<file.textLines()) {
            if (visitedMap[Pair(file.uri, idx)] != null) {
                val nextBlock = visitedMap[Pair(file.uri, idx)]!!

                if (nextBlock.loc.line == idx) {
                    code.addExit(nextBlock);
                    nextBlock.addEntrance(code);
                break;
                    } else {
                        val splitBlocks : Pair<CodeNode.CodeBlock, CodeNode.CodeBlock> = nextBlock.split(idx)
                        val block1 = splitBlocks.first
                        val block2 = splitBlocks.second
                        when {
                            block1 is CodeNode.CodeBlock -> {
                                val block1Start = block1.loc.line
                                val block1End = idx
                                for ( lineNu in block1Start..<block1End) {
                                    visitedMap[Pair(block1.loc.filename ,lineNu)] = block1
                                }
                            }
                            block2 is CodeNode.CodeBlock -> {
                                val block2Start = idx
                                val block2End = idx + block2.lines.size
                                for ( lineNu in block2Start..<block2End) {
                                    visitedMap[Pair(block2.loc.filename ,lineNu)] = block2
                                }
                            }
                        }
                    }
            } else {
                visitedMap[Pair(file.uri, idx)] = code;
            }
            val tokens = file.getLine(idx);
            if (tokens != null && tokens.tokens.isNotEmpty()) {

                if (isUnconditionalJump(tokens)) {
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
                        val nextBlock = makeNode(file, destinationLocation.line)

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

                }
                else {
                    code.addLine(tokens)
                }
            }

            if (idx == file.textLines()-1) {//end of file
                code.addExit(CodeNode.End)
            }
        }
        return code
    }



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

    fun start(): CodeNode.Start {
        return startNode
    }




    fun end(): CodeNode.End {
        return end
    }

}

sealed class CodeNode {
    private val _entrances = mutableListOf<CodeNode>()
    private val _exits = mutableListOf<CodeNode>()

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
        private val _lines = mutableListOf<Tokens>()

        val lines get() = _lines.toList()

        fun addLine(entry : Tokens) : CodeBlock {
            _lines.add(entry)
            return this
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
            val block1Loc = loc;
            val block2Loc = Location(loc.filename, idx,0,0);

            val block1 = CodeBlock(block1Loc)
            val block2 = CodeBlock(block2Loc)

            for ( line:Int in loc.line..<idx) {
                block1.addLine(lines[line-loc.line])
            }
            for ( line:Int in idx..<lines.size + loc.line) {
                block2.addLine(lines[line-loc.line])
            }
            block1.addExit(block2)
            block2.addEntrance(block1)
            entrances.forEach({node ->
                node.removeExit(this)
                node.addExit(block1)
            })
            exits.forEach( { node ->
                node.removeEntrance(this)
                node.addEntrance(block2)
            })
            return Pair(block1,block2)
        }
    }
}
