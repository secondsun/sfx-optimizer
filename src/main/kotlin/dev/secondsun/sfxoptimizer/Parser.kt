package dev.secondsun.sfxoptimizer

object Constants {
    val instructions = arrayListOf(null)
    enum class Register(val label : String) {
         R0("r0"),
         R1("r1"),
         R2("r2"),
         R3("r3"),
         R4("r4"),
         R5("r5"),
         R6("r6"),
         R7("r7"),
         R8("r8"),
         R9("r9"),
        R10("r10"),
        R11("r11"),
        R12("r12"),
        R13("r13"),
        R14("r14"),
        R15("r15")

    }



    fun register(token:String) : Register? {
        return Register.values().find { token.trim().lowercase().equals(it.label) }
    }

    fun isRegister(token:String): Boolean {
        return register(token) != null
    }
}

class ParseResult {

}