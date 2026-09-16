package com.leo.infinityglass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Infinity Glass Vector v0.9 refinements.
 *
 * Goal for this build:
 *  - keep the pill border already visible on Wi-Fi/Bluetooth/Send
 *  - add a matching optical rim to the icon-only circular Quick Settings
 *  - fix the v0.8 QS-expansion/scrim hooks using signature-independent hooks
 *  - neutralise the warm/brown QS scrim without disabling Monet globally
 *
 * Loaded before v0.8/base so this class' AFTER getColorForState hook runs last.
 * All hooks are fail-soft and scoped to com.android.systemui only.
 */
public final class InfinityGlassV09Extras implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";

    private static volatile float qsExpansion = 0f;

    private static final AtomicBoolean FIRST_TRACKER = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_SCRIM = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_CIRCLE = new AtomicBoolean(false);

    private static final Map<Object, Boolean> ORIGINAL_BLEND =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final long COLOR_TRANSPARENT = 0L;
    private static final long COLOR_WHITE = packSrgb(0xFFFFFFFF);
    private static final long COLOR_ICE = packSrgb(0xFFF7FCFF);
    private static final long COLOR_COOL = packSrgb(0xFFD8F4FF);
    private static final long COLOR_TEAL = packSrgb(0xFF72DDD8);
    private static final long COLOR_IOS_BLUE = packSrgb(0xFF0A84FF);
    private static final long COLOR_IOS_CYAN = packSrgb(0xFF64D2FF);
    private static final long COLOR_DEEP_BLUE = packSrgb(0xFF0057D9);
    private static final long COLOR_BLACK = packSrgb(0xFF000000);

    private static Constructor<?> tileColorsCtor;
    private static Constructor<?> linearGradientCtor;
    private static Constructor<?> radialGradientCtor;
    private static Method colorBoxMethod;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.9: refinements loading in " + lpparam.packageName);
        installFlexibleQsExpansionTracker(lpparam);
        installFlexibleScrimNeutralizer(lpparam);
        installCircularOpticalRim(lpparam);
    }

    /**
     * v0.8 assumed one exact setQsPosition signature. Infinity X's compiled
     * SystemUI differs, so hook every overload and read the first numeric arg.
     */
    private static void installFlexibleQsExpansionTracker(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.ScrimController",
                    lpparam.classLoader);

            XposedBridge.hookAllMethods(controller, "setQsPosition", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null) return;
                    for (Object arg : param.args) {
                        if (arg instanceof Float || arg instanceof Double) {
                            qsExpansion = clamp(((Number) arg).floatValue(), 0f, 1f);
                            if (FIRST_TRACKER.compareAndSet(false, true)) {
                                XposedBridge.log(TAG + " v0.9: QS_TRACKER_ACTIVE; expansion="
                                        + qsExpansion + " method=" + param.method.getName());
                            }
                            return;
                        }
                    }
                }
            });

            XposedBridge.log(TAG + " v0.9: flexible QS expansion tracker installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.9: QS tracker unavailable: " + t);
        }
    }

    /**
     * Remove the warm Material/Monet wash while QS is expanded. We keep a very
     * light neutral black veil (about 7%) so white controls stay readable.
     */
    private static void installFlexibleScrimNeutralizer(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> scrimView = XposedHelpers.findClass(
                    "com.android.systemui.scrim.ScrimView",
                    lpparam.classLoader);

            XposedBridge.hookAllMethods(scrimView, "setTint", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Integer)) return;

                        Object view = param.thisObject;
                        if (!ORIGINAL_BLEND.containsKey(view)) {
                            try {
                                Object original = XposedHelpers.callMethod(
                                        view, "shouldBlendWithMainColor");
                                if (original instanceof Boolean) {
                                    ORIGINAL_BLEND.put(view, (Boolean) original);
                                }
                            } catch (Throwable ignored) {
                                ORIGINAL_BLEND.put(view, Boolean.TRUE);
                            }
                        }

                        if (qsExpansion > 0.03f) {
                            try {
                                XposedHelpers.callMethod(view, "setBlendWithMainColor", false);
                            } catch (Throwable ignored) {}

                            // Neutral black with low alpha: no wallpaper-derived brown tint.
                            param.args[0] = Integer.valueOf(0x12000000);

                            if (FIRST_SCRIM.compareAndSet(false, true)) {
                                XposedBridge.log(TAG + " v0.9: SCRIM_NEUTRAL_ACTIVE; expansion="
                                        + qsExpansion + " tint=#12000000");
                            }
                        } else {
                            Boolean original = ORIGINAL_BLEND.get(view);
                            if (original != null) {
                                try {
                                    XposedHelpers.callMethod(
                                            view, "setBlendWithMainColor", original.booleanValue());
                                } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " v0.9: scrim frame failed: " + t);
                    }
                }
            });

            XposedBridge.log(TAG + " v0.9: flexible scrim neutralizer installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.9: scrim neutralizer unavailable: " + t);
        }
    }

    /**
     * Infinity X draws icon-only QS controls inside an inner CircleShape and the
     * outer TileExpandable is a transparent 0dp RoundedCornerShape. That is why
     * the physical border used by Wi-Fi/Bluetooth/Send never reaches the small
     * circular buttons. We solve it at the brush level: a radial glass brush
     * concentrates white/cyan luminance at the outer 10-12% of the circle,
     * producing a real-looking optical rim without changing tile geometry.
     */
    private static void installCircularOpticalRim(
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
            final Class<?> linearGradient = XposedHelpers.findClass(
                    "androidx.compose.ui.graphics.LinearGradient", cl);
            final Class<?> color = XposedHelpers.findClass(
                    "androidx.compose.ui.graphics.Color", cl);

            tileColorsCtor = tileColors.getDeclaredConstructor(
                    long.class, long.class, long.class, long.class, long.class,
                    brush, brush, long.class);
            tileColorsCtor.setAccessible(true);

            linearGradientCtor = linearGradient.getDeclaredConstructor(
                    List.class, List.class, long.class, long.class);
            linearGradientCtor.setAccessible(true);

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
                            Object previous = param.getResult();
                            if (previous == null) return;

                            try {
                                boolean iconOnly = (Boolean) param.args[1];
                                if (!iconOnly) return; // preserve the pill tiles exactly as they are

                                Object uiState = param.args[0];
                                int state = XposedHelpers.getIntField(uiState, "state");
                                boolean active = state == 2;
                                boolean unavailable = state == 0;

                                Object ringBrush = active
                                        ? createCircleBrush(true, false)
                                        : createCircleBrush(false, unavailable);

                                // tileBackgroundGradient is unused by the actual visible circle,
                                // but keep it transparent/safe for the outer 0dp container.
                                Object transparentTileBrush = createLinearGradient(
                                        Arrays.asList(COLOR_TRANSPARENT, COLOR_TRANSPARENT),
                                        Arrays.asList(0f, 1f));

                                long icon = unavailable
                                        ? withAlpha(COLOR_WHITE, 128)
                                        : withAlpha(COLOR_WHITE, 254);

                                Object replacement = tileColorsCtor.newInstance(
                                        COLOR_TRANSPARENT,
                                        COLOR_TRANSPARENT,
                                        withAlpha(COLOR_WHITE, unavailable ? 130 : 252),
                                        withAlpha(COLOR_WHITE, unavailable ? 88 : 210),
                                        icon,
                                        ringBrush,
                                        transparentTileBrush,
                                        withAlpha(COLOR_ICE, unavailable ? 58 : 150));
                                param.setResult(replacement);

                                if (FIRST_CIRCLE.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.9: CIRCLE_RIM_ACTIVE; state=" + state
                                            + " active=" + active
                                            + " radial=" + (radialGradientCtor != null));
                                }
                            } catch (Throwable t) {
                                param.setResult(previous);
                                XposedBridge.log(TAG + " v0.9: circle rim frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.9: circular optical rim hook installed; radial="
                    + (radialGradientCtor != null));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.9: unable to install circular rim hook: " + t);
        }
    }

    private static Object createCircleBrush(boolean active, boolean unavailable) throws Exception {
        final List<Long> colors;
        final List<Float> stops;

        if (active) {
            colors = Arrays.asList(
                    withAlpha(COLOR_IOS_BLUE, 236),
                    withAlpha(COLOR_IOS_BLUE, 224),
                    withAlpha(COLOR_DEEP_BLUE, 230),
                    withAlpha(COLOR_IOS_CYAN, 210),
                    withAlpha(COLOR_ICE, 232),
                    withAlpha(COLOR_IOS_CYAN, 188));
            stops = Arrays.asList(0.00f, 0.62f, 0.82f, 0.90f, 0.965f, 1.00f);
        } else if (unavailable) {
            colors = Arrays.asList(
                    withAlpha(COLOR_BLACK, 8),
                    withAlpha(COLOR_TEAL, 5),
                    withAlpha(COLOR_COOL, 18),
                    withAlpha(COLOR_ICE, 74),
                    withAlpha(COLOR_IOS_CYAN, 44));
            stops = Arrays.asList(0.00f, 0.70f, 0.88f, 0.965f, 1.00f);
        } else {
            colors = Arrays.asList(
                    withAlpha(COLOR_BLACK, 7),
                    withAlpha(COLOR_TEAL, 10),
                    withAlpha(COLOR_COOL, 24),
                    withAlpha(COLOR_IOS_CYAN, 82),
                    withAlpha(COLOR_ICE, 198),
                    withAlpha(COLOR_IOS_CYAN, 106));
            stops = Arrays.asList(0.00f, 0.68f, 0.86f, 0.925f, 0.972f, 1.00f);
        }

        if (radialGradientCtor != null) {
            List<Object> boxed = boxColors(colors);
            long centerUnspecified = packOffset(Float.NaN, Float.NaN);
            Class<?>[] p = radialGradientCtor.getParameterTypes();
            if (p.length == 4) {
                return radialGradientCtor.newInstance(
                        boxed, stops, Long.valueOf(centerUnspecified), Float.valueOf(Float.POSITIVE_INFINITY));
            } else if (p.length >= 5) {
                // TileMode.Clamp is encoded as zero in Compose's inline TileMode.
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
        }

        // Fallback: still creates a bright optical edge if RadialGradient ABI differs.
        return createLinearGradient(colors, stops);
    }

    private static Object createLinearGradient(List<Long> colors, List<Float> stops)
            throws Exception {
        List<Object> boxed = boxColors(colors);
        return linearGradientCtor.newInstance(
                boxed,
                stops,
                packOffset(0f, 0f),
                packOffset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY));
    }

    private static List<Object> boxColors(List<Long> values) throws Exception {
        List<Object> out = new ArrayList<>(values.size());
        for (Long value : values) out.add(colorBoxMethod.invoke(null, value.longValue()));
        return out;
    }

    private static long packSrgb(int argb) {
        return (Integer.toUnsignedLong(argb) << 32);
    }

    private static long withAlpha(long color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        long low32 = color & 0xFFFFFFFFL;
        if (low32 != 0L) return packSrgb((a << 24) | 0x00FFFFFF);
        int argb = (int) (color >>> 32);
        return packSrgb((a << 24) | (argb & 0x00FFFFFF));
    }

    private static long packOffset(float x, float y) {
        return ((long) Float.floatToRawIntBits(x) << 32)
                | (Float.floatToRawIntBits(y) & 0xFFFFFFFFL);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
