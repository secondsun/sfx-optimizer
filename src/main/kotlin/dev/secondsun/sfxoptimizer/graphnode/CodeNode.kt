package dev.secondsun.sfxoptimizer.graphnode

import dev.secondsun.retro.util.Location
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenAttribute
import dev.secondsun.retro.util.vo.Tokens
import dev.secondsun.sfxoptimizer.graphbuilder.CodeNodeVisitor
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey

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

        fun intervals(key: IntervalKey): Interval? {
                var min = Int.MAX_VALUE
                var max = Int.MIN_VALUE

                traverse(
                    { codeNode ->
                        when(codeNode) {
                            is CodeBlock -> {
                                val interval = codeNode.intervals[key]
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
                                when(key) {
                                  is IntervalKey.RegisterKey -> {

                                      //add all registers used in function
                                      val interval = codeNode.function.functionBody.start().intervals(key)
                                      if (interval != null) {
                                          if (codeNode.line > max) {
                                              max = codeNode.line
                                          }
                                          if (codeNode.line < min) {
                                              min = codeNode.line
                                          }
                                      }

                                      //add all registers passed as params
                                      if (codeNode.tokens.tokens.size >2) {
                                          val params = codeNode.tokens.tokens.subList(2, codeNode.tokens.tokens.size)
                                          params
                                              .filter({param -> Constants.isRegister(param.text()) })
                                              .filter { param -> param.text().equals(key.register.label) }
                                              .forEach({
                                                  if (codeNode.line > max) {
                                                      max = codeNode.line
                                                  }
                                                  if (codeNode.line < min) {
                                                      min = codeNode.line
                                                  }
                                              })
                                      }
                                  }

                                    is IntervalKey.LabelKey -> {
                                        if (codeNode.tokens.tokens.size >2) {
                                            val params = codeNode.tokens.tokens.subList(2, codeNode.tokens.tokens.size)
                                            params
                                                .filter { param -> param.text().equals(key.label) }
                                                .forEach({
                                                    if (codeNode.line > max) {
                                                        max = codeNode.line
                                                    }
                                                    if (codeNode.line < min) {
                                                        min = codeNode.line
                                                    }
                                                })
                                        }
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
            if (node is CallBlock) {
                traverse(visitor,visited,node.function)
            } else if (node is Start) {
                traverse(visitor,visited,node.main)
            }
        }

        fun mainMethod(): CodeBlock {
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

        fun split(idx: Int): Pair<CodeBlock, CodeBlock> {
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

    data class CallBlock(val function: FunctionStart, val line :Int, val tokens:Tokens) : CodeNode() {


    }

    data class FunctionStart(val functionName: String, val location: Location, val functionBody: CodeGraph, val params: List<Token>) :
        CodeNode() {
    }
}