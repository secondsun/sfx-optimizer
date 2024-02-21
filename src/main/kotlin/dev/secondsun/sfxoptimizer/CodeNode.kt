package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.Location
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenAttribute
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

    data class Start(val main: CodeBlock) : CodeNode() {

        fun intervals(key:IntervalKey):Interval? {
                var min = Int.MAX_VALUE
                var max = Int.MIN_VALUE

                traverse(
                    {
                        when(it) {
                            is CodeBlock -> {
                                val interval = it.intervals[key]
                                if (interval != null) {
                                    if (interval.start < min) {
                                        min = interval.start
                                    }
                                    if (interval.end > max) {
                                        max = interval.end
                                    }
                                }
                            }
                            is CallBlock -> {
                                val interval = it.function.functionBody.start().intervals(key)
                                if (interval!= null) {
                                    if (it.line >max) {
                                        max = it.line
                                    }
                                    if (it.line<min) {
                                        min = it.line
                                    }
                                }

                            }
                            else -> {}
                        }
                    }
                )

            if (min == Int.MAX_VALUE) {
                return null
            } else {
                return Interval(key).apply { start = min; end = max }
            }

        }

        fun traverse(visitor: CodeNodeVisitor, visited: MutableSet<CodeNode> = mutableSetOf(), node: CodeNode = this.main) {
            if (visited.contains(node)) {
                return
            }

            node.accept(visitor)
            visited.add(node)
            node.exits.forEach { traverse(visitor, visited, it) }
            if (node is CodeNode.CallBlock) {
                traverse(visitor,visited,node.function)
            } else if (node is CodeNode.Start) {
                traverse(visitor,visited,node.main)
            }
        }

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
                 val key = IntervalKey.LabelKey(token.text())
                 val interval = _intervals[key]?: Interval(key)
                 interval.addWrite(token.lineNumber)
                 _intervals.put(key, interval)
                 if (token.hasAttribute(TokenAttribute.REGISTER_LABEL)) {
                     val register : Constants.Register? = token.getMetadata(TokenAttribute.REGISTER_LABEL);
                     if (register != null) {
                         val key = IntervalKey.RegisterKey(register)
                         val interval = _intervals[key] ?: Interval(key)
                         interval.addRead(token.lineNumber)
                         _intervals.put(key, interval)
                     }
                 }
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
                val key = IntervalKey.LabelKey(token.text())
                val interval = _intervals[key]?: Interval(key)
                interval.addRead(token.lineNumber)
                _intervals.put(key, interval)
                if (token.hasAttribute(TokenAttribute.REGISTER_LABEL)) {
                    val register : Constants.Register? = token.getMetadata(TokenAttribute.REGISTER_LABEL);
                    if (register != null) {
                        val key = IntervalKey.RegisterKey(register)
                        val interval = _intervals[key] ?: Interval(key)
                        interval.addRead(token.lineNumber)
                        _intervals.put(key, interval)
                    }
                }
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

    data class CallBlock(val function: CodeNode.FunctionStart,val line :Int) : CodeNode() {


    }

    data class FunctionStart(val functionName: String, val location: Location, val functionBody: CodeGraph,val params: List<Token>) :
        CodeNode() {
    }
}