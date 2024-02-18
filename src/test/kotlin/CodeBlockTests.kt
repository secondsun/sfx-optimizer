import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.FileService
import dev.secondsun.retro.util.SymbolService
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(3, codeGraph.nodeCount);
        assertTrue(codeGraph.start() is CodeNode.Start);

        val codeNode: CodeNode.CodeBlock = codeGraph.start().mainMethod();

        assertEquals(2, codeNode.lines.size);
        assertTrue(codeGraph.end() is CodeNode.End);
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

        assertEquals(5, mainCodeGraph.nodeCount) //Call nodes get their own node

        var functionNode = programGraph.getFunction("somename")
        assertNotNull(functionNode)
        assertEquals(3, functionNode!!.functionBody.nodeCount)


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
        assertEquals(3, labelInterval!!.end)


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

        assertEquals(5, codeGraph.nodeCount);
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

        val codeGraph  = graph(program)
        println(codeGraph.print())

        assertEquals(7, codeGraph.nodeCount);

        val block1 = codeGraph.startNode.mainMethod() as CodeNode.CodeBlock
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
        assertEquals(3, codeGraph.nodeCount);
        assertEquals(3, codeGraph.startNode.mainMethod().lines.size)

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
        assertEquals(4, codeGraph.nodeCount);

        val main: CodeNode.CodeBlock = codeGraph.start().mainMethod();

        assertEquals(4, main.lines.size);
        assertEquals(1, main.exits.size);

        val afterJumpNode: CodeNode.CodeBlock = main.exits[0] as CodeNode.CodeBlock;

        assertEquals(3, afterJumpNode.lines.size);
        assertEquals(1, afterJumpNode.exits.size);

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


        assertEquals(5,mainInterval?.start)
        assertEquals(15,mainInterval?.end)

        assertEquals(1, functionInterval?.start)
        assertEquals(1,functionInterval?.end)


        //TODO("Implement")
    }

    private fun graph(program: String, main: Int = 0): CodeGraph {
        val file = (CA65Scanner().tokenize(program))
        val symbolService = SymbolService()
        symbolService.extractDefinitions(file)
        file.uri = URI.create("./test.sgs")
        val fileService = MockFileService(file)
        return  CA65Grapher(symbolService = symbolService, fileService = fileService).graph(file = file, line = main)

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

class MockFileService(val file:TokenizedFile) : FileService() {
    override fun readLines(fileUri: URI?): TokenizedFile {
        return file
    }

    override fun find(file: URI?, vararg optionalSearchPaths: URI?): MutableList<URI> {
        return mutableListOf(this.file.uri)
    }
}
