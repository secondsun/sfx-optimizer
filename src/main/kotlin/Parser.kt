package dev.secondsun.sfxoptimizer

object Constants {
    val instructions = arrayListOf(null)
    enum class Register(val token : String) {
         R0("R0"),
         R1("R1"),
         R2("R2"),
         R3("R3"),
         R4("R4"),
         R5("R5"),
         R6("R6"),
         R7("R7"),
         R8("R8"),
         R9("R9"),
        R10("R10"),
        R11("R11"),
        R12("R12"),
        R13("R13"),
        R14("R14"),
        R15("R15")
    }



    fun register(token:String) : Register? {
        return Register.values().find { token.uppercase().equals(it.token) }
    }

    fun isRegister(token:String): Boolean {
        return register(token) != null
    }
}

class ParseResult {

}