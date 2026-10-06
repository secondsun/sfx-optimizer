open module dev.secondsun.sfxoptimizer {
    requires kotlin.stdlib;
    requires dev.secondsun.retro.util;
    requires org.jetbrains.annotations;
    requires jdk.httpserver;
    requires java.desktop;
    requires java.net.http;

    exports dev.secondsun.sfxoptimizer.graphbuilder;
    exports dev.secondsun.sfxoptimizer.graphnode;
    exports dev.secondsun.sfxoptimizer;
    exports dev.secondsun.sfxoptimizer.viewer;

}