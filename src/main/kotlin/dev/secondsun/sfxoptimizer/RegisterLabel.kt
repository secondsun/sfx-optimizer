package dev.secondsun.sfxoptimizer

class RegisterLabel(val label : String, var register : Constants.Register? = null) {

    override fun equals(other: Any?): Boolean {
        if (other is RegisterLabel)
            return label.contentEquals(other.label)
        return false
    }



    override fun hashCode(): Int {
        return label.hashCode()
    }
}
