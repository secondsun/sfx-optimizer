package dev.secondsun.sfxoptimizer


data class Interval(val key:IntervalKey, val start:Int, val end:Int)

sealed interface IntervalKey {
    class RegisterKey(val register:Constants.Register):IntervalKey
    class LabelKey(val label:String):IntervalKey
}
