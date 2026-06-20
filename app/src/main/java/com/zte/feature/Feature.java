package com.zte.feature;

/**
 * Stub for the ZTE framework class com.zte.feature.Feature, which lives in
 * BOOTCLASSPATH on stock RedMagic / nubia firmware but is MISSING on custom
 * ROMs. GameAssist resolves it statically from:
 *   - com.zte.shared.wrapper.ZteFeatureWrapper.<clinit>
 *   - com.zte.shared.wrapper.DisplayManagerWrapper
 *   - com.zte.gameassist.ext.Constants
 * Without it, class init throws NoClassDefFoundError and poisons
 * ZteFeatureWrapper -> SystemMgr -> the whole assist board, killing the process
 * in GameAssistApplication.onCreate.
 *
 * This stub is compiled into the LSPosed module dex and injected into the
 * GameAssist classloader (see Hook.injectFeatureStub) so resolution succeeds.
 *
 * All methods are static and use only primitive / String types, so they are
 * safe to serve across classloaders.
 *
 * Feature flags default to OFF, EXCEPT ZTE_FEATURE_MAGIC_GAME_ASSIST which must
 * be ON: SystemMgr.ENABLE_GAME_ASSIST is derived from it and gates the assist
 * board logic in DefaultCommander. RedMagic-hardware flags (gamepad / handle /
 * key-map / mouse-map) stay OFF: there is no such hardware here and enabling
 * them would pull in more missing ZTE classes.
 */
public class Feature {

    public static String get(String key) {
        return "";
    }

    public static String get(String key, String def) {
        return def;
    }

    public static boolean getBoolean(String key, boolean def) {
        if (key != null && key.equals("ZTE_FEATURE_MAGIC_GAME_ASSIST")) {
            return true;
        }
        return def;
    }

    public static int getInt(String key, int def) {
        return def;
    }

    public static long getLong(String key, long def) {
        return def;
    }
}
