package com.leo.infinityglass;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Infinity Glass Vector v0.14 - uniform Quick Settings icon tint.
 *
 * Keeps every glass/background/gradient decision made by the older layers and
 * only normalises the foreground icon colour so Bluetooth, Internet, Send and
 * the circular QS controls use the same white tone. Unavailable tiles remain
 * slightly dimmer for state readability.
 */
public final class InfinityGlassV14UniformIcons implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final long COLOR_TRANSPARENT = 0L;
    private static final long COLOR_WHITE = packSrgb(0xFFFFFFFF);

    private static final AtomicBoolean FIRST_UNIFIED = new AtomicBoolean(false);
    private static Constructor<?> tileColorsCtor;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.14: uniform QS icon layer loading in "
                + lpparam.packageName);
        installUniformIconHook(lpparam);
    }

    private static void installUniformIconHook(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final ClassLoader cl = lpparam.classLoader;
            final Class<?> tileDefaults = XposedHelpers.findClass(
                    "com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults", cl);
            final Class<?> tileUiState = XposedHelpers.findClass(
                    "com.android.systemui.qs.panels.ui.viewmodel.TileUiState", cl);
            final Class<?> composer = XposedHelpers.findClass(
                    "androidx.compose.runtime.Composer", cl);
            final Class<?> tileColors = XposedHelpers.findClass(
                    "com.android.systemui.qs.panels.ui.compose.infinitegrid.TileColors", cl);
            final Class<?> brush = XposedHelpers.findClass(
                    "androidx.compose.ui.graphics.Brush", cl);

            tileColorsCtor = tileColors.getDeclaredConstructor(
                    long.class, long.class, long.class, long.class, long.class,
                    brush, brush, long.class);
            tileColorsCtor.setAccessible(true);

            XposedHelpers.findAndHookMethod(
                    tileDefaults,
                    "getColorForState",
                    tileUiState,
                    boolean.class,
                    composer,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object current = param.getResult();
                            if (current == null) return;

                            try {
                                Object uiState = param.args[0];
                                int state = XposedHelpers.getIntField(uiState, "state");

                                // Same neutral-white tint for every available QS icon.
                                // State 0 stays dimmer so genuinely unavailable controls remain clear.
                                long unifiedIcon = withAlpha(COLOR_WHITE,
                                        state == 0 ? 150 : 252);

                                long background = getLong(current, "background", COLOR_TRANSPARENT);
                                long iconBackground = getLong(
                                        current, "iconBackground", COLOR_TRANSPARENT);
                                long label = getLong(current, "label", withAlpha(COLOR_WHITE, 252));
                                long secondaryLabel = getLong(
                                        current, "secondaryLabel", withAlpha(COLOR_WHITE, 208));
                                long outline = getLong(
                                        current, "outline", withAlpha(COLOR_WHITE, 112));
                                Object iconGradient = getObject(
                                        current, "iconBackgroundGradient");
                                Object tileGradient = getObject(
                                        current, "tileBackgroundGradient");

                                Object replacement = tileColorsCtor.newInstance(
                                        background,
                                        iconBackground,
                                        label,
                                        secondaryLabel,
                                        unifiedIcon,
                                        iconGradient,
                                        tileGradient,
                                        outline);
                                param.setResult(replacement);

                                if (FIRST_UNIFIED.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG
                                            + " v0.14: UNIFORM_QS_ICONS_ACTIVE; availableAlpha=252"
                                            + " unavailableAlpha=150"
                                            + " tone=#FFFFFFFF");
                                }
                            } catch (Throwable t) {
                                param.setResult(current);
                                XposedBridge.log(TAG
                                        + " v0.14: uniform icon frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.14: uniform QS icon hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.14: unable to install uniform icon hook: " + t);
        }
    }

    private static long getLong(Object obj, String field, long fallback) {
        try {
            return XposedHelpers.getLongField(obj, field);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object getObject(Object obj, String field) {
        try {
            return XposedHelpers.getObjectField(obj, field);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long packSrgb(int argb) {
        return (Integer.toUnsignedLong(argb) << 32);
    }

    private static long withAlpha(long color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        if ((color & 0xFFFFFFFFL) == 0L) {
            int argb = (int) (color >>> 32);
            return packSrgb((a << 24) | (argb & 0x00FFFFFF));
        }
        return color;
    }
}
