package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.Location
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.vo.Tokens

sealed class CodeNode {

    private var _entrances = mutableListOf<CodeNode>()
    private var _exits = mutableListOf<CodeNode>()


    val entrances get() = _entrances.toList()
    val exits get() = _exits.toList()


    fun accept(visitor: CodeNodeVisitor) {
        visitor.visit(this)
    }

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

    data class Start(val main: CodeNode.CodeBlock) : CodeNode() {
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
                 Constants.register(token.text())?.let {
                     val key = IntervalKey.RegisterKey(it)
                     val interval = _intervals[key]?: Interval(key)
                     interval.addWrite(token.lineNumber)
                     _intervals.put(key, interval)
                 }

             } else {
                 TODO("Add Label interval")
             }

        }

         fun addWrite(register: Constants.Register, lineNumber:Int) {
             val key = IntervalKey.RegisterKey(register)
             val interval = _intervals[key]?: Interval(key)
             interval.addWrite(lineNumber)
             _intervals.put(key, interval)
        }
         fun addRead(register: Constants.Register, lineNumber:Int) {
             val key = IntervalKey.RegisterKey(register)
             val interval = _intervals[key]?: Interval(key)
             interval.addRead(lineNumber)
             _intervals.put(key, interval)
        }
        fun addRead(token: Token) {
            if (Constants.isRegister(token.text())) {
                Constants.register(token.text())?.let {
                    val key = IntervalKey.RegisterKey(it)
                    val interval = _intervals[key]?: Interval(key)
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


            val block2Loc = Location(loc.filename, idx, 0, 0);


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

    data class CallBlock(val function: CodeNode.FunctionStart) : CodeNode() {


    }

    data class FunctionStart(val functionName: String, val location: Location, val functionBody: CodeGraph) {

    }
}