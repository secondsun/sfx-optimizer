open module dev.secondsun.sfxoptimizer {
    requires kotlin.stdlib;
    requires dev.secondsun.retro.util;
    requires org.jetbrains.annotations;
    requires jdk.httpserver;

    exports dev.secondsun.sfxoptimizer;
    exports dev.secondsun.sfxoptimizer.analysis;
    exports dev.secondsun.sfxoptimizer.compiler;
    exports dev.secondsun.sfxoptimizer.macro;
    exports dev.secondsun.sfxoptimizer.solver;
    exports dev.secondsun.sfxoptimizer.stack;
}
