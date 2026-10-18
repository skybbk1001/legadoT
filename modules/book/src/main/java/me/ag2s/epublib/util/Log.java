package me.ag2s.epublib.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * android.util.Log 的纯 JVM 替代实现，保持原调用点不变。
 */
@SuppressWarnings("unused")
public final class Log {

    private static final Logger LOGGER = Logger.getLogger("me.ag2s.epublib");

    private Log() {
    }

    public static int v(String tag, String msg) {
        LOGGER.finer(tag + ": " + msg);
        return 0;
    }

    public static int d(String tag, String msg) {
        LOGGER.fine(tag + ": " + msg);
        return 0;
    }

    public static int i(String tag, String msg) {
        LOGGER.info(tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        LOGGER.warning(tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        LOGGER.log(Level.WARNING, tag + ": " + msg, tr);
        return 0;
    }

    public static int e(String tag, String msg) {
        LOGGER.severe(tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        LOGGER.log(Level.SEVERE, tag + ": " + msg, tr);
        return 0;
    }
}
