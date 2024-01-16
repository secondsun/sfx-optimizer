package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.retro.util.vo.Tokens

class CA65Grapher {
    private fun isConditionalJump(tokens: Tokens): Boolean {
        TODO("Not yet implemented")
    }

    fun graph(file: TokenizedFile, line: Int): CodeGraph {
        val code : CodeNode.CodeBlock = CodeNode.CodeBlock()
        for (idx in 0..<file.textLines()) {
            val tokens = file.getLine(idx);
            if (tokens != null && !tokens.tokens.isEmpty()) {
                if (isUnconditionalJump(tokens)) {
                    //import next token
                    //create exits
                } else if (isConditionalJump(tokens)) {

                }
                else {
                    code.addLine(tokens)
                }
            }
        }
        val start = CodeNode.Start(code)
        code.addExit(CodeNode.End)
        return CodeGraph(start)
    }

    private fun isUnconditionalJump(tokens: Tokens): Boolean {

    }

}

class CodeGraph(val startNode:CodeNode.Start, val end : CodeNode.End = CodeNode.End) {

    private var _nodeCount = 0
    val nodeCount get() = _nodeCount
    init {
        _nodeCount = 2//start node and end node
        _nodeCount += countChildren(startNode.main)
    }

    private fun countChildren(node : CodeNode.CodeBlock):Int {
        if (node.hasAttribute(CodeNode.Attribute.VISITED)) {
            return 0
        }

        node.setAttribute(CodeNode.Attribute.VISITED)

        var count = node.exits.size

        node.exits.forEach { exitNode ->
            when (exitNode) {
                is CodeNode.Start -> throw IllegalStateException("Start nodes can't be children")
                is CodeNode.CodeBlock -> count += countChildren(exitNode)
                is CodeNode.End -> {}
            }
        }
        return count
    }

    fun start(): CodeNode.Start {
        return startNode
    }




    fun end(): CodeNode.End {
        return end
    }

}

sealed class CodeNode {

    enum class Attribute{ VISITED }

    val attributes = mutableSetOf<Attribute>()

    data class Start(val main:CodeNode.CodeBlock) : CodeNode() {
        fun mainMethod(): CodeNode.CodeBlock {
            return main
        }
    }

    data object End : CodeNode()

    class CodeBlock : CodeNode() {
        private val _entrances = mutableListOf<CodeNode>()
        private val _exits = mutableListOf<CodeNode>()
        private val _lines = mutableListOf<Tokens>()

        val lines get() = _lines.toList()
        val entrances get() = _entrances.toList()
        val exits get() = _exits.toList()

        fun addEntrance(entry : CodeNode) : CodeBlock {
            _entrances.add(entry)
            return this
        }

        fun addExit(entry : CodeNode) : CodeBlock {
            _exits.add(entry)
            return this
        }

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
    }
}
