package com.leo.infinityglass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Infinity Glass Vector v0.10 - adaptive colour correction.
 *
 * v0.7/v0.9 intentionally forced iOS blue/cyan into active icon lenses.
 * The desired behaviour is different: keep the glass/borders inspired by the
 * reference image, but let the hue come from Infinity X / Monet / wallpaper.
 *
 * This hook runs last for TileDefaults#getColorForState. It calls the original
 * unhooked ROM implementation to recover the wallpaper-derived icon colour and
 * icon gradient, then combines those native colours with the glass plate and
 * optical rim produced by the previous Infinity Glass layers.
 *
 * Result:
 *  - no forced iOS blue/cyan inside QS icons
 *  - Wi-Fi/Bluetooth/Send icon lenses recover the ROM/Monet hue
 *  - circular tiles keep an optical rim, but the centre follows the ROM hue
 *  - border itself stays neutral white so it does not tint the wallpaper
 */
public final class InfinityGlassV10AdaptiveColor implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final long COLOR_TRANSPARENT = 0L;
    private static final long COLOR_WHITE = packSrgb(0xFFFFFFFF);
    private static final long COLOR_BLACK = packSrgb(0xFF000000);

    private static final AtomicBoolean FIRST_ADAPTIVE = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_CIRCLE = new AtomicBoolean(false);

    private static Constructor<?> tileColorsCtor;
    private static Constructor<?> radialGradientCtor;
    private static Method colorBoxMethod;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.10: adaptive colour layer loading in " + lpparam.packageName);
        installAdaptiveTileColourHook(lpparam);
    }

    private static void installAdaptiveTileColourHook(
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
            final Class<?> color = XposedHelpers.findClass(
                    "androidx.compose.ui.graphics.Color", cl);

            tileColorsCtor = tileColors.getDeclaredConstructor(
                    long.class, long.class, long.class, long.class, long.class,
                    brush, brush, long.class);
            tileColorsCtor.setAccessible(true);

            colorBoxMethod = color.getDeclaredMethod("box-impl", long.class);
            colorBoxMethod.setAccessible(true);

            try {
                Class<?> radialGradient = XposedHelpers.findClass(
                        "androidx.compose.ui.graphics.RadialGradient", cl);
                for (Constructor<?> c : radialGradient.getDeclaredConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length >= 4
                            && List.class.isAssignableFrom(p[0])
                            && List.class.isAssignableFrom(p[1])
                            && p[2] == long.class
                            && p[3] == float.class) {
                        c.setAccessible(true);
                        radialGradientCtor = c;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                radialGradientCtor = null;
            }

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
                                // Recover the exact native Infinity X / Monet colours, bypassing
                                // every Xposed after-hook registered for this method.
                                Object nativeColors = XposedBridge.invokeOriginalMethod(
                                        param.method, param.thisObject, param.args);
                                if (nativeColors == null) return;

                                Object uiState = param.args[0];
                                boolean iconOnly = (Boolean) param.args[1];
                                int state = XposedHelpers.getIntField(uiState, "state");
                                boolean unavailable = state == 0;

                                // Keep the glass plate, text, tile gradient and outline produced
                                // by the existing Infinity Glass layers.
                                long background = getLong(current, "background", COLOR_TRANSPARENT);
                                long label = getLong(current, "label", withAlpha(COLOR_WHITE, 252));
                                long secondaryLabel = getLong(
                                        current, "secondaryLabel", withAlpha(COLOR_WHITE, 210));
                                long icon = getLong(current, "icon", withAlpha(COLOR_WHITE, 254));
                                long outline = getLong(current, "outline", withAlpha(COLOR_WHITE, 126));
                                Object tileGradient = getObject(current, "tileBackgroundGradient");

                                // Native hue from the ROM. This is the part that follows Monet /
                                // wallpaper, so a warm wallpaper can naturally give the brown tone
                                // seen before, without hard-coding brown into the module.
                                long nativeIconBackground = getLong(
                                        nativeColors, "iconBackground", COLOR_TRANSPARENT);
                                long nativeBackground = getLong(
                                        nativeColors, "background", COLOR_TRANSPARENT);
                                Object nativeIconGradient = getObject(
                                        nativeColors, "iconBackgroundGradient");

                                long adaptiveTone = chooseAdaptiveTone(
                                        nativeIconBackground, nativeBackground);

                                final long iconBackground;
                                final Object iconGradient;

                                if (iconOnly) {
                                    // Circular QS controls need the brush itself to form their rim.
                                    // The centre uses the ROM's own hue; only the final 8-10% is a
                                    // neutral white optical highlight.
                                    iconBackground = COLOR_TRANSPARENT;
                                    iconGradient = createAdaptiveCircleBrush(
                                            adaptiveTone, state, unavailable);

                                    if (FIRST_CIRCLE.compareAndSet(false, true)) {
                                        XposedBridge.log(TAG + " v0.10: CIRCLE_ADAPTIVE_RIM_ACTIVE;"
                                                + " state=" + state
                                                + " tone=" + hexColor(adaptiveTone)
                                                + " radial=" + (radialGradientCtor != null));
                                    }
                                } else {
                                    // Large Wi-Fi/Bluetooth/Send tiles: restore the ROM-provided
                                    // gradient inside the icon lens. This is the cleanest way to
                                    // make the colour track the wallpaper instead of forcing blue.
                                    iconBackground = nativeIconBackground;
                                    iconGradient = nativeIconGradient != null
                                            ? nativeIconGradient
                                            : getObject(current, "iconBackgroundGradient");
                                }

                                Object replacement = tileColorsCtor.newInstance(
                                        background,
                                        iconBackground,
                                        label,
                                        secondaryLabel,
                                        icon,
                                        iconGradient,
                                        tileGradient,
                                        outline);
                                param.setResult(replacement);

                                if (FIRST_ADAPTIVE.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.10: ADAPTIVE_MONET_COLOR_ACTIVE;"
                                            + " state=" + state
                                            + " iconOnly=" + iconOnly
                                            + " nativeIcon=" + hexColor(nativeIconBackground)
                                            + " nativeBg=" + hexColor(nativeBackground)
                                            + " chosen=" + hexColor(adaptiveTone));
                                }
                            } catch (Throwable t) {
                                // Keep the previous glass result if anything in this optional
                                // colour-correction layer differs in a future ROM build.
                                param.setResult(current);
                                XposedBridge.log(TAG + " v0.10: adaptive colour frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.10: adaptive wallpaper/Monet colour hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.10: unable to install adaptive colour hook: " + t);
        }
    }

    private static Object createAdaptiveCircleBrush(
            long tone, int state, boolean unavailable) throws Exception {
        if (radialGradientCtor == null) return null;

        // Do not inject a hue. Every tinted stop below is the native ROM hue;
        // the only non-native colour is the white optical edge.
        final int centreAlpha;
        final int bodyAlpha;
        final int shoulderAlpha;
        final int rimAlpha;
        final int outerAlpha;

        if (state == 2) {
            centreAlpha = 218;
            bodyAlpha = 202;
            shoulderAlpha = 158;
            rimAlpha = 196;
            outerAlpha = 94;
        } else if (unavailable) {
            centreAlpha = 16;
            bodyAlpha = 12;
            shoulderAlpha = 20;
            rimAlpha = 72;
            outerAlpha = 34;
        } else {
            centreAlpha = 38;
            bodyAlpha = 32;
            shoulderAlpha = 38;
            rimAlpha = 156;
            outerAlpha = 72;
        }

        List<Long> colors = Arrays.asList(
                withAlpha(tone, centreAlpha),
                withAlpha(tone, bodyAlpha),
                withAlpha(tone, shoulderAlpha),
                withAlpha(COLOR_BLACK, unavailable ? 4 : 8),
                withAlpha(tone, unavailable ? 18 : 46),
                withAlpha(COLOR_WHITE, rimAlpha),
                withAlpha(tone, outerAlpha));
        List<Float> stops = Arrays.asList(
                0.00f, 0.56f, 0.78f, 0.865f, 0.915f, 0.972f, 1.00f);

        List<Object> boxed = boxColors(colors);
        long centerUnspecified = packOffset(Float.NaN, Float.NaN);
        Class<?>[] p = radialGradientCtor.getParameterTypes();

        if (p.length == 4) {
            return radialGradientCtor.newInstance(
                    boxed,
                    stops,
                    Long.valueOf(centerUnspecified),
                    Float.valueOf(Float.POSITIVE_INFINITY));
        }

        Object[] args = new Object[p.length];
        args[0] = boxed;
        args[1] = stops;
        args[2] = Long.valueOf(centerUnspecified);
        args[3] = Float.valueOf(Float.POSITIVE_INFINITY);
        for (int i = 4; i < p.length; i++) {
            if (p[i] == int.class) args[i] = Integer.valueOf(0);
            else if (p[i] == boolean.class) args[i] = Boolean.FALSE;
            else args[i] = null;
        }
        return radialGradientCtor.newInstance(args);
    }

    private static long chooseAdaptiveTone(long iconBackground, long background) {
        if (isUsableSrgb(iconBackground)) return iconBackground;
        if (isUsableSrgb(background)) return background;
        // Warm neutral fallback only if the ROM returns no usable colour at all.
        return packSrgb(0xFF8A6858);
    }

    private static boolean isUsableSrgb(long color) {
        if ((color & 0xFFFFFFFFL) != 0L) return false;
        int argb = (int) (color >>> 32);
        return ((argb >>> 24) & 0xFF) > 8;
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

    private static List<Object> boxColors(List<Long> values) throws Exception {
        List<Object> out = new ArrayList<>(values.size());
        for (Long value : values) {
            out.add(colorBoxMethod.invoke(null, value.longValue()));
        }
        return out;
    }

    private static long packSrgb(int argb) {
        return (Integer.toUnsignedLong(argb) << 32);
    }

    private static long withAlpha(long color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        // Infinity X's normal Material colours arrive as packed sRGB (low 32 bits = 0).
        // Preserve their RGB instead of replacing it with a fixed blue/cyan.
        if ((color & 0xFFFFFFFFL) == 0L) {
            int argb = (int) (color >>> 32);
            return packSrgb((a << 24) | (argb & 0x00FFFFFF));
        }
        return color;
    }

    private static String hexColor(long color) {
        if ((color & 0xFFFFFFFFL) != 0L) return "packed-wide";
        int argb = (int) (color >>> 32);
        return String.format("#%08X", argb);
    }

    private static long packOffset(float x, float y) {
        return ((long) Float.floatToRawIntBits(x) << 32)
                | (Float.floatToRawIntBits(y) & 0xFFFFFFFFL);
    }
}
