import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.FileService
import dev.secondsun.retro.util.SymbolService
import dev.secondsun.retro.util.TokenAttribute
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.*
import dev.secondsun.sfxoptimizer.graphbuilder.CA65Grapher
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph
import dev.secondsun.sfxoptimizer.graphnode.CodeNode
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * These tests test that code blocks are generated correctly.
 * The tests use the X-GSU lib in the homebrew library
 *
 * This tests also make use of my function mnemonics provided
 * by CA65 macros in the X-GSU library.
 *
 * Each tests will be given a start point in a TokenizedFile
 * and then the code will examine the code block graph genertaed.
 *
 * The first block is the "Start" Block and has a single exit : the
 * "main" method. An exit is a pointer to another code block.
 * Each block (except start) has an entrance and at least one exit (except the End block)
 */
class CodeBlockTests {
    @Test
    fun `does a trivial code block generate a start, end, and code`() {
        val program = """
            iwt r1 , #5 
            stw ( r1 ) 
        """.trimIndent()

        val codeGraph = graph(program)
        assertEquals(3, codeGraph.nodeCount)
        assertTrue(
            codeGraph.start() is
                    CodeNode.Start
        )

        val codeNode: CodeNode.CodeBlock = codeGraph.start().mainMethod()

        assertEquals(2, codeNode.lines.size)
        assertTrue(codeGraph.end() is CodeNode.End)
    }

    @Test
    fun `function blocks`() {
        val program = """
            function somename 
                register output
                from variable1
                to output
                add  #$5
                return  
            endfunction
            
            register input, output
            iwt input, #5
            call somename 
            stop
            
        """.trimMargin()

        val programGraph = graph(program, 8)
        val mainCodeGraph = programGraph
        var functionNode = programGraph.getFunction("somename")
        val inputInterval = mainCodeGraph.startNode.intervals(IntervalKey.LabelKey("input"))

        //youre super pretty all the time <3
        assertTrue(
            functionNode!!.functionBody.start().mainMethod().lines[1].tokens[0].hasAttribute(TokenAttribute.ERROR)
        )
        assertEquals(5, mainCodeGraph.nodeCount) //Call nodes get their own node
        assertNotNull(functionNode)
        assertEquals(3, functionNode.functionBody.nodeCount)


        assertEquals(9, inputInterval!!.start)
        assertEquals(9, inputInterval.end)


    }

    @Test
    fun `test label interval`() {
        val program = """
            register input 
            
            iwt input, #$5
            stw (input)
        """.trimMargin()

        val codeGraph = graph(program)

        val intervalKey = IntervalKey.LabelKey("input")
        val labelInterval = codeGraph.start().intervals(intervalKey)
        assertEquals(2, labelInterval!!.start)
        assertEquals(3, labelInterval.end)


    }

    @Test
    fun `split code node when it is jumped into midway`() {
        val program = """
               iwt r0 , #5 ;-
               
               label:
               beq label
               nop
               
               stop
               """.trimMargin()
        val codeGraph = graph(program)

        println(codeGraph.print())

        val main = codeGraph.startNode.mainMethod()

        assertEquals(5, codeGraph.nodeCount)
        assertEquals(1, main.exits.size)
        //the second node links to itself
        assertEquals(2, main.exits.get(0).exits.size)
        assertEquals(2, main.exits.get(0).entrances.size)
    }


    @Test
    fun `do conditional blocks make everything correctly`() {
        val program = """
               iwt r0 , #5 ;-
               add #0      ;-
               
               label:
               beq next
               nop()
               
               iwt r2 , #5 ;-
               stw ( r2 )  ;-
               
               next:                   ;-
               iwt r3 , #5        ;-
               stw ( r3 )         ;-
               beq label          ;-
               nop              ;-
               
               stop ; comment -
        """.trimIndent()

        val codeGraph = graph(program)
        println(codeGraph.print())

        assertEquals(7, codeGraph.nodeCount)

        val block1 = codeGraph.startNode.mainMethod()
        val block2 = (block1).exits[0] as CodeNode.CodeBlock
        val block3 = (block2).exits[1] as CodeNode.CodeBlock
        val block4 = (block2).exits[0] as CodeNode.CodeBlock
        val block5 = block4.exits[0] as CodeNode.CodeBlock
        val block6 = block5.exits[1] as CodeNode.CodeBlock

        assertEquals(1, block1.exits.size)
        assertEquals(1, block3.exits.size)
        assertEquals(2, block2.exits.size)
        assertEquals(2, block2.entrances.size)
        assertEquals(2, block5.exits.size)
        assertEquals(3, (block2).lines.size)
        assertEquals(5, (block4).lines.size)

    }

    @Test
    fun `code nodes must include empty lines`() {
        val program = """iwt r4, #$5
            
            iwt r6,#$9
            """
        val file = CA65Scanner().tokenize(program)
        val codeGraph = CA65Grapher().graph(file, line = 0)
        assertEquals(3, codeGraph.nodeCount)
        assertEquals(3, codeGraph.startNode.mainMethod().lines.size)

    }

    @Test
    fun `function errors`() {
        val program = """
            function test_funct_bad_param param 1
              nop
              return
            endfunction
            
            function test_funct param1
              nop
              return
            endfunction
            
            
            register param1
            call test_funct param1
            call test_funct 
            call test_funct param2
            call test_funct_bad_param param1
            """

        val codeGraph = graph(program, 12)
        val main = codeGraph.start().main
        val badFunction = codeGraph.getFunction("test_funct_bad_param")

        main.lines[0].tokens.forEach({ assertFalse(it.hasAttribute(TokenAttribute.ERROR), it.message) })
        (main.exits[0] as CodeNode.CallBlock).tokens.tokens.forEach({
            assertFalse(
                it.hasAttribute(TokenAttribute.ERROR),
                it.message
            )
        })
        val call1 = main.exits[0].exits[0].exits[0] as CodeNode.CallBlock
        assertTrue(call1.tokens.tokens[0].hasAttribute(TokenAttribute.ERROR))
        assertEquals("test_funct requires 1 parameters", call1.tokens.tokens[0].message)
        val call2 = call1.exits[0].exits[0] as CodeNode.CallBlock
        assertTrue(call2.tokens.tokens[2].hasAttribute(TokenAttribute.ERROR))
        assertEquals("undeclared param", call2.tokens.tokens[2].message)

        assertTrue(badFunction!!.params[1].hasAttribute(TokenAttribute.ERROR))
        assertEquals("invalid param", badFunction.params[1].message)

    }

    @Test
    fun `does a trivial jump generate 4 code nodes`() {
        val program = """
            iwt r1 , #5 
            stw ( r1 ) 
            bra next
            nop

            iwt r2 , #5 
            stw ( r2 )
            
            next:
            iwt r3 , #5 
            stw ( r3 ) 
        """.trimMargin()

        val codeGraph = graph(program)
        //println(codeGraph.print())
        assertEquals(4, codeGraph.nodeCount)

        val main: CodeNode.CodeBlock = codeGraph.start().mainMethod()

        assertEquals(4, main.lines.size)
        assertEquals(1, main.exits.size)

        val afterJumpNode: CodeNode.CodeBlock = main.exits[0] as CodeNode.CodeBlock

        assertEquals(3, afterJumpNode.lines.size)
        assertEquals(1, afterJumpNode.exits.size)

        assertEquals("r3", afterJumpNode.lines[1].tokens[1].text())

        assertTrue(main.registersUsed.contains(Constants.Register.R1))
        assertTrue(main.registersUsed.contains(Constants.Register.R0))
        assertTrue(afterJumpNode.registersUsed.contains(Constants.Register.R3))
        assertTrue(afterJumpNode.registersUsed.contains(Constants.Register.R0))

        assertEquals(9, afterJumpNode.intervals[IntervalKey.RegisterKey(Constants.Register.R3)]!!.start)
        assertEquals(10, afterJumpNode.intervals[IntervalKey.RegisterKey(Constants.Register.R3)]!!.end)
        assertEquals(10, afterJumpNode.intervals[IntervalKey.RegisterKey(Constants.Register.R0)]!!.start)
        assertEquals(10, afterJumpNode.intervals[IntervalKey.RegisterKey(Constants.Register.R0)]!!.end)

    }

    @Test
    fun `function params are registerLabels`() {
        val program = """
            function test_function param1
              iwt param1, #5
              iwt r3, #6
              return
            endfunction
        
            call test_function r2
        """.trimMargin()

        val codeGraph = graph(program, 5)
        val function = codeGraph.getFunction("test_function")!!.functionBody.startNode
        val main = codeGraph.start()


        val functionInterval = function.intervals(IntervalKey.LabelKey("param1"))
        assertEquals(1, functionInterval?.start)
        assertEquals(1, functionInterval?.end)

        val functionInterval2 = function.intervals(IntervalKey.RegisterKey(Constants.Register.R3))
        assertEquals(2, functionInterval2?.start)
        assertEquals(2, functionInterval2?.end)

        val interval = main.intervals(IntervalKey.RegisterKey(Constants.Register.R2))
        assertEquals(6, interval?.start)
        assertEquals(6, interval?.end)


    }


    /**
     * A graph level interval calculates a liveliness range over an entire graph from
     * start to finish.
     */
    @Test
    fun `basic test of graph register intervals`() {
        val program = """
            function f1
              iwt r2 , #5
              return
            endfunction
            
            iwt r1 , #5 
            stw ( r1 ) 
            bra next
            nop

            iwt r1 , #5 
            stw ( r1 )
            
            next:
            iwt r1 , #5 
            stw ( r1 ) 
            
            call f1
            
        """.trimMargin()


        val codeGraph = graph(program, 5)//assertEquals(1, codeGraph.)

        val main = codeGraph.start()
        val function = codeGraph.getFunction("f1")!!.functionBody.startNode

        val mainInterval = main.intervals(IntervalKey.RegisterKey(Constants.Register.R1))
        val functionInterval = function.intervals(IntervalKey.RegisterKey(Constants.Register.R2))


        assertEquals(5, mainInterval?.start)
        assertEquals(15, mainInterval?.end)

        assertEquals(1, functionInterval?.start)
        assertEquals(1, functionInterval?.end)
    }

    /**
     * A graph level interval calculates a liveliness range over an entire graph from
     * start to finish.
     */
    @Test
    fun `basic test of graph label intervals`() {
        val program = """
            function f1 param1
              iwt param1, #5
              return
            endfunction
            
            register test
            
            iwt test , #5 
            stw ( test ) 
            bra next
            nop

            iwt test , #5 
            stw ( test )
            
            next:
            iwt test , #5 
            stw ( test ) 
            
            call f1 test
            
        """.trimMargin()

        val codeGraph = graph(program, 5)//assertEquals(1, codeGraph.)

        val main = codeGraph.start()
        val function = codeGraph.getFunction("f1")!!.functionBody.startNode

        val mainInterval = main.intervals(IntervalKey.LabelKey("test"))
        val functionInterval = function.intervals(IntervalKey.LabelKey("param1"))


        assertEquals(7, mainInterval?.start)
        assertEquals(19, mainInterval?.end)

        assertEquals(1, functionInterval?.start)
        assertEquals(1, functionInterval?.end)
    }

    private fun graph(program: String, mainStartLine: Int = 0): CodeGraph {
        val file = (CA65Scanner().tokenize(program))
        
        val symbolService = SymbolService()
        symbolService.extractDefinitions(file)
        file.uri = URI.create("./test.sgs")
        val fileService = MockFileService(file)
        return CA65Grapher(symbolService = symbolService, fileService = fileService)
            .graph(file = file, line = mainStartLine)

    }

    @Test
    fun `defining a variable creates register and label intervals`() {
        val program = """
            register l1 = r1
            iwt l1, #$12
            stw ( l1 )
        """.trimMargin()

        val codeGraph = graph(program)
        val block = codeGraph.start().main
        assertEquals(1, block.intervals[IntervalKey.RegisterKey(Constants.Register.R1)]!!.start)
        assertEquals(2, block.intervals[IntervalKey.RegisterKey(Constants.Register.R1)]!!.end)

        assertEquals(1, block.intervals[IntervalKey.LabelKey("l1")]!!.start)
        assertEquals(2, block.intervals[IntervalKey.LabelKey("l1")]!!.end)
    }

    @Test
    fun `test loops`() {
        val program = """
            iwt r8, #$12
            for 5
                from r8
                to r5
                add #$2
            endfor
            
            stw (r8)
            
        """.trimIndent()

        val programGraph = graph(program)

        assertEquals(5, programGraph.nodeCount)
        assertEquals(0,programGraph.startNode.intervals(IntervalKey.RegisterKey(Constants.Register.R8))!!.start)
        assertEquals(7,programGraph.startNode.intervals(IntervalKey.RegisterKey(Constants.Register.R8))!!.end)
        assertEquals(1,programGraph.startNode.intervals(IntervalKey.RegisterKey(Constants.Register.R12))!!.start)
        assertEquals(5,programGraph.startNode.intervals(IntervalKey.RegisterKey(Constants.Register.R12))!!.end)
        assertEquals(1,programGraph.startNode.intervals(IntervalKey.RegisterKey(Constants.Register.R13))!!.start)
        assertEquals(5,programGraph.startNode.intervals(IntervalKey.RegisterKey(Constants.Register.R13))!!.end)
        assertEquals(4,programGraph.startNode.intervals(IntervalKey.RegisterKey(Constants.Register.R5))!!.start)
        assertEquals(4,programGraph.startNode.intervals(IntervalKey.RegisterKey(Constants.Register.R5))!!.end)

    }

    @Test
    fun `handle sreg and dreg`() {
        val program = """
            iwt r5, #$7FFF
            with r5
            asr
            asr
            asr
            asr
        """.trimMargin()

        val codeGraph = graph(program)
        val block = codeGraph.start().main


        assertTrue(block.registersUsed.contains(Constants.Register.R5))
        assertTrue(block.registersUsed.contains(Constants.Register.R0))

        val r5Interval = block.intervals[IntervalKey.RegisterKey(Constants.Register.R5)]!!

        assertEquals(0, r5Interval.start)
        assertEquals(2, r5Interval.end)
        assertEquals(2, r5Interval.writes.size)
        assertEquals(1, r5Interval.reads.size)
        assertEquals(3, block.intervals[IntervalKey.RegisterKey(Constants.Register.R0)]!!.start)
        assertEquals(5, block.intervals[IntervalKey.RegisterKey(Constants.Register.R0)]!!.end)
    }
}

class MockFileService(val file: TokenizedFile) : FileService() {
    override fun readLines(fileUri: URI?): TokenizedFile {
        return file
    }

    override fun find(file: URI?, vararg optionalSearchPaths: URI?): MutableList<URI> {
        return mutableListOf(this.file.uri)
    }
}
