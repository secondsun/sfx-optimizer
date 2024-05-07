
 import dev.secondsun.sfxoptimizer.allocate
 import kotlin.test.Ignore
 import kotlin.test.Test

 class RegisterAllocationTest {

    @Test()
    @Ignore
    fun `test reciprocal`() {
        val program = """
            ; In : R0 fixed88 the value to return the reciprocal of
            ; Out : R3 a fixed88 reciprocal
            function reciprocal in
                register dividend, divisor, remainder, quotient
            
                iwt dividend, #$1
                move divisor, in
                move remainder, dividend;
                iwt quotient, #0 ; r0 =  ()
                
                for #$10
                    with remainder
                    add remainder
                    
                    from remainder
                    cmp divisor
                    bmi lp
                        
                    with quotient
                    add quotient
                    with quotient
                    add 1 ;quotient++
                    with remainder
                    sub divisor
                lp: 	
                endfor
                move r3, quotient
                return 
            endfunction
            
            register toRecip
            iwt toRecip, #$0800
            
            call toRecip
            
            stop
            
        """.trimMargin()

        val graph = graph(program,29)
        val out = allocate(graph)
    }




}