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
 * Infinity Glass Vector v0.11 - circular rim refinement.
 *
 * v0.10 restored adaptive Infinity X / Monet colours, but the icon-only
 * circular controls still had a thick radial ring that looked embossed.
 * v0.11 keeps the adaptive colour and changes only the circular glass lens:
 *
 *  - roughly half the visible rim thickness of v0.10
 *  - no black inner-shadow stop, so circles no longer look recessed
 *  - neutral white highlight instead of cyan/blue edge colouring
 *  - centre hue still comes from Infinity X / Monet / wallpaper
 *  - active state is brighter, but never receives a hard-coded blue hue
 *
 * Large Wi-Fi/Bluetooth/Send tiles are intentionally left untouched.
 */
public final class InfinityGlassV11CircleRefinement implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final long COLOR_TRANSPARENT = 0L;
    private static final long COLOR_WHITE = packSrgb(0xFFFFFFFF);

    private static final AtomicBoolean FIRST_REFINED = new AtomicBoolean(false);

    private static Constructor<?> tileColorsCtor;
    private static Constructor<?> radialGradientCtor;
    private static Method colorBoxMethod;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.11: circle refinement loading in " + lpparam.packageName);
        installRefinedCircleHook(lpparam);
    }

    private static void installRefinedCircleHook(
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
                                boolean iconOnly = (Boolean) param.args[1];
                                if (!iconOnly) return;

                                Object nativeColors = XposedBridge.invokeOriginalMethod(
                                        param.method, param.thisObject, param.args);
                                if (nativeColors == null) return;

                                Object uiState = param.args[0];
                                int state = XposedHelpers.getIntField(uiState, "state");
                                boolean unavailable = state == 0;

                                long nativeIconBackground = getLong(
                                        nativeColors, "iconBackground", COLOR_TRANSPARENT);
                                long nativeBackground = getLong(
                                        nativeColors, "background", COLOR_TRANSPARENT);
                                long adaptiveTone = chooseAdaptiveTone(
                                        nativeIconBackground, nativeBackground);

                                Object refinedBrush = createThinAdaptiveRim(
                                        adaptiveTone, state, unavailable);
                                if (refinedBrush == null) return;

                                // Preserve every other v0.10/v0.9 glass characteristic. Only
                                // replace the circular lens fill/rim that was visually too thick.
                                long background = getLong(
                                        current, "background", COLOR_TRANSPARENT);
                                long label = getLong(
                                        current, "label", withAlpha(COLOR_WHITE, 252));
                                long secondaryLabel = getLong(
                                        current, "secondaryLabel", withAlpha(COLOR_WHITE, 210));
                                long icon = getLong(
                                        current, "icon", withAlpha(COLOR_WHITE, 254));
                                long outline = getLong(
                                        current, "outline", withAlpha(COLOR_WHITE, 112));
                                Object tileGradient = getObject(
                                        current, "tileBackgroundGradient");

                                Object replacement = tileColorsCtor.newInstance(
                                        background,
                                        COLOR_TRANSPARENT,
                                        label,
                                        secondaryLabel,
                                        icon,
                                        refinedBrush,
                                        tileGradient,
                                        outline);
                                param.setResult(replacement);

                                if (FIRST_REFINED.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG
                                            + " v0.11: THIN_ADAPTIVE_CIRCLE_RIM_ACTIVE; state="
                                            + state
                                            + " tone=" + hexColor(adaptiveTone)
                                            + " radial=" + (radialGradientCtor != null)
                                            + " noInnerShadow=true");
                                }
                            } catch (Throwable t) {
                                param.setResult(current);
                                XposedBridge.log(TAG + " v0.11: circle refinement frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.11: thin adaptive circle-rim hook installed; radial="
                    + (radialGradientCtor != null));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.11: unable to install circle refinement: " + t);
        }
    }

    /**
     * The visible highlight occupies only the outer ~3-4% of the radius.
     * v0.10 began building its rim around ~91.5%, which produced the thick,
     * recessed/3D look. There is deliberately no black shadow stop here.
     */
    private static Object createThinAdaptiveRim(
            long tone, int state, boolean unavailable) throws Exception {
        if (radialGradientCtor == null) return null;

        final int centreAlpha;
        final int bodyAlpha;
        final int shoulderAlpha;
        final int whiteSoft;
        final int whiteHot;
        final int outerTone;

        if (state == 2) {
            // Brighter native colour for active state, but still wallpaper/Monet derived.
            centreAlpha = 176;
            bodyAlpha = 164;
            shoulderAlpha = 118;
            whiteSoft = 68;
            whiteHot = 128;
            outerTone = 58;
        } else if (unavailable) {
            centreAlpha = 6;
            bodyAlpha = 5;
            shoulderAlpha = 8;
            whiteSoft = 22;
            whiteHot = 44;
            outerTone = 12;
        } else {
            // Inactive circles remain almost transparent, like the large glass pills.
            centreAlpha = 18;
            bodyAlpha = 16;
            shoulderAlpha = 26;
            whiteSoft = 46;
            whiteHot = 96;
            outerTone = 26;
        }

        List<Long> colors = Arrays.asList(
                withAlpha(tone, centreAlpha),
                withAlpha(tone, bodyAlpha),
                withAlpha(tone, shoulderAlpha),
                withAlpha(COLOR_WHITE, whiteSoft),
                withAlpha(COLOR_WHITE, whiteHot),
                withAlpha(tone, outerTone));

        // Thin optical edge: the highlight is concentrated in the last 2-3%.
        List<Float> stops = Arrays.asList(
                0.00f,
                0.90f,
                0.955f,
                0.978f,
                0.990f,
                1.00f);

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
        // Neutral warm fallback only when the ROM gives us no usable packed colour.
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
