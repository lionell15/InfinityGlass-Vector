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
 * Infinity Glass Vector v0.5
 * Target: Infinity X / Android 16 / POCO X7 Pro (rodin)
 *
 * v0.5 keeps the validated real SurfaceFlinger blur and changes strategy:
 *  1) neutralize Infinity X CustomColorScheme.qsTileColor so Monet/Material You does not
 *     keep painting the QS surfaces brown/opaque;
 *  2) make each Compose tile almost transparent;
 *  3) concentrate the visual energy at the optical edges: a hot top-left rim,
 *     transparent body, inner dark falloff and weaker lower-right reflection.
 *
 * This is still a runtime Vector/Xposed module. It replaces no SystemUI APK and every hook
 * is fail-soft so a missing ROM method falls back to the original result.
 */
public final class InfinityGlassHook implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final AtomicBoolean FIRST_FORCED_FRAME = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_GLASS_TILE = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_MONET_NEUTRAL = new AtomicBoolean(false);

    private static final long COLOR_TRANSPARENT = 0L;
    private static final long COLOR_WHITE = packSrgb(0xFFFFFFFF);
    private static final long COLOR_ICE = packSrgb(0xFFF2FAFF);
    private static final long COLOR_COOL = packSrgb(0xFFDDF2FF);
    private static final long COLOR_BLACK = packSrgb(0xFF000000);

    private static Constructor<?> tileColorsCtor;
    private static Constructor<?> linearGradientCtor;
    private static Method colorBoxMethod;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.5: loading in " + lpparam.packageName);
        installDepthBlurHook(lpparam);
        installMonetNeutralizerHook(lpparam);
        installTileGlassHook(lpparam);
    }

    /** Real background blur already validated on the user's exact Infinity X build. */
    private static void installDepthBlurHook(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> depthController = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.NotificationShadeDepthController",
                    lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(
                    depthController,
                    "computeBlurAndZoomOut",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                float shade = safeFloatField(param.thisObject, "shadeExpansion");
                                float qs = safeFloatField(param.thisObject, "qsPanelExpansion");
                                float expansion = clamp(Math.max(shade, qs), 0f, 1f);
                                if (expansion < 0.025f) return;

                                Object oldPair = param.getResult();
                                if (oldPair == null) return;

                                Object oldFirst = XposedHelpers.callMethod(oldPair, "getFirst");
                                Object oldSecond = XposedHelpers.callMethod(oldPair, "getSecond");
                                int oldBlur = oldFirst instanceof Number
                                        ? ((Number) oldFirst).intValue() : 0;
                                float oldZoom = oldSecond instanceof Number
                                        ? ((Number) oldSecond).floatValue() : 0f;

                                Object blurUtils = XposedHelpers.getObjectField(
                                        param.thisObject, "blurUtils");
                                float baseMaxBlur = readMaxBlur(blurUtils);
                                if (baseMaxBlur <= 0f) return;

                                // Keep the curve already proven stable on this rodin build:
                                // baseMax=80 -> 168 px at full expansion.
                                float multiplier = 1.25f + (0.85f * smoothStep(expansion));
                                int desiredBlur = Math.round(baseMaxBlur * multiplier);
                                int forcedBlur = Math.max(oldBlur, desiredBlur);

                                // Liquid glass reads better when the scene behind it stays still.
                                float forcedZoom = 0f;

                                Object newPair = oldPair.getClass()
                                        .getConstructor(Object.class, Object.class)
                                        .newInstance(Integer.valueOf(forcedBlur), Float.valueOf(forcedZoom));
                                param.setResult(newPair);

                                if (FIRST_FORCED_FRAME.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.5: BLUR_ACTIVE; shade=" + shade
                                            + " qs=" + qs
                                            + " oldBlur=" + oldBlur
                                            + " baseMax=" + baseMaxBlur
                                            + " forcedBlur=" + forcedBlur
                                            + " oldZoom=" + oldZoom);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.5: blur frame hook failed: " + t);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + " v0.5: depth-controller hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.5: unable to install blur hook: " + t);
        }
    }

    /**
     * Infinity X uses CustomColorScheme.qsTileColor in tiles, brightness, ringer and footer
     * controls. That value is sourced from surface_effect_1/2 and therefore carries Monet's
     * wallpaper tint. Replace only this QS-specific surface with a very low-alpha neutral white.
     */
    private static void installMonetNeutralizerHook(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> customScheme = XposedHelpers.findClass(
                    "com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomColorScheme",
                    lpparam.classLoader);

            int hooked = 0;
            for (final Method method : customScheme.getDeclaredMethods()) {
                if (!method.getName().startsWith("getQsTileColor")) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // Compose Color inline class is represented as a packed long.
                            param.setResult(Long.valueOf(withAlpha(COLOR_WHITE, 10)));
                            if (FIRST_MONET_NEUTRAL.compareAndSet(false, true)) {
                                XposedBridge.log(TAG
                                        + " v0.5: MONET_QS_NEUTRALIZED; method="
                                        + method.getName() + " alpha=10");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " v0.5: Monet neutralizer frame failed: " + t);
                        }
                    }
                });
                hooked++;
            }

            XposedBridge.log(TAG + " v0.5: Monet QS neutralizer installed; methods=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.5: unable to install Monet QS neutralizer: " + t);
        }
    }

    /**
     * Creates edge-weighted neutral glass plates. The center is deliberately much clearer than
     * v0.4; the top-left rim and lower-right echo carry the shape instead of a matte body fill.
     */
    private static void installTileGlassHook(final XC_LoadPackage.LoadPackageParam lpparam) {
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
                            Object original = param.getResult();
                            if (original == null) return;

                            try {
                                Object uiState = param.args[0];
                                boolean iconOnly = (Boolean) param.args[1];
                                int state = XposedHelpers.getIntField(uiState, "state");
                                boolean dualTarget = XposedHelpers.getBooleanField(
                                        uiState, "handlesSecondaryClick");

                                // android.service.quicksettings.Tile:
                                // unavailable=0, inactive=1, active=2
                                final int plateAlpha;
                                final int edgeHot;
                                final int edgeShoulder;
                                final int bodyAlpha;
                                final int innerShadow;
                                final int returnEdge;
                                final int tailEdge;
                                final int iconHot;
                                final int iconBody;
                                final int iconShadow;
                                final int outlineAlpha;

                                if (state == 2) {
                                    // Active plate: icy and more luminous without becoming opaque.
                                    plateAlpha = 12;
                                    edgeHot = 224;
                                    edgeShoulder = 104;
                                    bodyAlpha = 26;
                                    innerShadow = 24;
                                    returnEdge = 78;
                                    tailEdge = 34;
                                    iconHot = 224;
                                    iconBody = 96;
                                    iconShadow = 26;
                                    outlineAlpha = 156;
                                } else if (state == 1) {
                                    // Inactive plate: almost clear, shape carried by the rim.
                                    plateAlpha = 7;
                                    edgeHot = 178;
                                    edgeShoulder = 72;
                                    bodyAlpha = 16;
                                    innerShadow = 18;
                                    returnEdge = 52;
                                    tailEdge = 22;
                                    iconHot = 176;
                                    iconBody = 68;
                                    iconShadow = 20;
                                    outlineAlpha = 118;
                                } else {
                                    plateAlpha = 4;
                                    edgeHot = 92;
                                    edgeShoulder = 42;
                                    bodyAlpha = 9;
                                    innerShadow = 12;
                                    returnEdge = 30;
                                    tailEdge = 14;
                                    iconHot = 92;
                                    iconBody = 40;
                                    iconShadow = 14;
                                    outlineAlpha = 62;
                                }

                                // Barely-visible neutral body: no Material/Monet tile tint.
                                long background = withAlpha(COLOR_WHITE, plateAlpha);

                                /*
                                 * Eight-stop optical profile:
                                 *   0-6%    hot icy rim
                                 *   6-20%   quick transparency falloff
                                 *   20-62%  clear body
                                 *   62-78%  tiny dark internal refraction cue
                                 *   92-100% weaker reflected opposite edge
                                 *
                                 * Infinity X clips this brush to the rounded tile shape, so the
                                 * narrow end bands visually read as glass edge highlights rather
                                 * than as a full matte gradient.
                                 */
                                Object tileGradient = createGradient(
                                        Arrays.asList(
                                                withAlpha(COLOR_ICE, edgeHot),
                                                withAlpha(COLOR_COOL, edgeShoulder),
                                                withAlpha(COLOR_WHITE, bodyAlpha),
                                                withAlpha(COLOR_WHITE, Math.max(3, bodyAlpha / 2)),
                                                withAlpha(COLOR_BLACK, innerShadow),
                                                withAlpha(COLOR_WHITE, Math.max(2, bodyAlpha / 3)),
                                                withAlpha(COLOR_ICE, returnEdge),
                                                withAlpha(COLOR_WHITE, tailEdge)
                                        ),
                                        Arrays.asList(
                                                0.00f, 0.055f, 0.16f, 0.44f,
                                                0.67f, 0.82f, 0.955f, 1.00f));

                                // The dual-target icon insert behaves like a smaller raised lens.
                                Object iconGradient = createGradient(
                                        Arrays.asList(
                                                withAlpha(COLOR_ICE, iconHot),
                                                withAlpha(COLOR_COOL, iconBody),
                                                withAlpha(COLOR_WHITE, Math.max(10, iconBody / 2)),
                                                withAlpha(COLOR_BLACK, iconShadow),
                                                withAlpha(COLOR_WHITE, Math.max(12, iconBody / 3))
                                        ),
                                        Arrays.asList(0.00f, 0.09f, 0.42f, 0.76f, 1.00f));

                                // Infinity X requires transparent iconBackground on large dual
                                // targets in order to paint tileBackgroundGradient across the whole tile.
                                long iconBackground = dualTarget && !iconOnly
                                        ? COLOR_TRANSPARENT
                                        : withAlpha(COLOR_WHITE, Math.max(7, plateAlpha + 5));

                                long label;
                                long secondaryLabel;
                                long iconColor;
                                if (state == 0) {
                                    label = withAlpha(COLOR_WHITE, 132);
                                    secondaryLabel = withAlpha(COLOR_WHITE, 92);
                                    iconColor = withAlpha(COLOR_WHITE, 132);
                                } else {
                                    label = withAlpha(COLOR_WHITE, 252);
                                    secondaryLabel = withAlpha(COLOR_WHITE, 212);
                                    iconColor = withAlpha(COLOR_WHITE, 252);
                                }

                                // Modern Infinity X tiles do not always draw this outline field,
                                // but keep it bright for classic/icon paths that do consume it.
                                long outline = withAlpha(COLOR_ICE, outlineAlpha);

                                Object replacement = tileColorsCtor.newInstance(
                                        background,
                                        iconBackground,
                                        label,
                                        secondaryLabel,
                                        iconColor,
                                        iconGradient,
                                        tileGradient,
                                        outline);
                                param.setResult(replacement);

                                if (FIRST_GLASS_TILE.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.5: OPTICAL_GLASS_ACTIVE; state=" + state
                                            + " dual=" + dualTarget
                                            + " iconOnly=" + iconOnly
                                            + " plateAlpha=" + plateAlpha
                                            + " edgeHot=" + edgeHot
                                            + " innerShadow=" + innerShadow
                                            + " outlineAlpha=" + outlineAlpha);
                                }
                            } catch (Throwable t) {
                                param.setResult(original);
                                XposedBridge.log(TAG + " v0.5: optical glass frame failed: " + t);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + " v0.5: optical glass tile hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.5: unable to install optical glass hook: " + t);
        }
    }

    private static Object createGradient(List<Long> colorValues, List<Float> stops)
            throws Exception {
        List<Object> boxedColors = new ArrayList<>(colorValues.size());
        for (Long color : colorValues) {
            boxedColors.add(boxColor(color.longValue()));
        }

        // Compose Offset packs X in high float bits and Y in low float bits.
        // Infinity values make the brush automatically span each tile's actual size.
        long start = packOffset(0f, 0f);
        long end = packOffset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        return linearGradientCtor.newInstance(boxedColors, stops, start, end);
    }

    private static Object boxColor(long value) throws Exception {
        return colorBoxMethod.invoke(null, value);
    }

    /** Compose sRGB Color packs ARGB into the high 32 bits of the inline long. */
    private static long packSrgb(int argb) {
        return (Integer.toUnsignedLong(argb) << 32);
    }

    private static long withAlpha(long color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        long low32 = color & 0xFFFFFFFFL;
        if (low32 != 0L) {
            return packSrgb((a << 24) | 0x00FFFFFF);
        }
        int argb = (int) (color >>> 32);
        int rgb = argb & 0x00FFFFFF;
        return packSrgb((a << 24) | rgb);
    }

    private static long packOffset(float x, float y) {
        return ((long) Float.floatToRawIntBits(x) << 32)
                | (Float.floatToRawIntBits(y) & 0xFFFFFFFFL);
    }

    private static float safeFloatField(Object obj, String name) {
        try {
            return XposedHelpers.getFloatField(obj, name);
        } catch (Throwable ignored) {
            Object value = XposedHelpers.getObjectField(obj, name);
            return value instanceof Number ? ((Number) value).floatValue() : 0f;
        }
    }

    private static float readMaxBlur(Object blurUtils) {
        if (blurUtils == null) return 0f;
        try {
            Object result = XposedHelpers.callMethod(blurUtils, "getMaxBlurRadius");
            if (result instanceof Number) return ((Number) result).floatValue();
        } catch (Throwable ignored) {}
        try {
            return XposedHelpers.getFloatField(blurUtils, "maxBlurRadius");
        } catch (Throwable ignored) {}
        return 0f;
    }

    private static float smoothStep(float x) {
        x = clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
