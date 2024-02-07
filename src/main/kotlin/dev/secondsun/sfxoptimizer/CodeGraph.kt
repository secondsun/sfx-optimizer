package dev.secondsun.sfxoptimizer

class CodeGraph(val startNode: CodeNode.Start, val end : CodeNode.End = CodeNode.End) {

    private var _nodeCount = 0
    val nodeCount get() = _nodeCount
    init {
        _nodeCount = 2//start node and end node
        _nodeCount += countChildren(startNode.main)
    }


    fun traverse(visitor: CodeNodeVisitor, visited: MutableSet<CodeNode> = mutableSetOf(), node: CodeNode = this.startNode) {
        node.accept(visitor)
        visited.add(node)
        node.exits.forEach { traverse(visitor, visited, it) }
    }

    private fun countChildren(node : CodeNode, visited : MutableSet<CodeNode> = mutableSetOf()) :Int {

        if (visited.contains(node)) {
            return visited.size;
        }
        visited.add(node)

        node.exits.forEach { exitNode ->
            when (exitNode) {
                is CodeNode.Start -> throw IllegalStateException("Start nodes can't be children")
                is CodeNode.CodeBlock, is CodeNode.CallBlock -> countChildren(exitNode, visited)
                is CodeNode.End -> {}
            }
        }
        return visited.size
    }

    fun print(node : CodeNode.CodeBlock = startNode.main, visited : MutableSet<CodeNode> = mutableSetOf(), indent :String = "") :String {

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
                is CodeNode.CallBlock -> builder.appendLine("Call ${exitNode.function.functionName}")
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