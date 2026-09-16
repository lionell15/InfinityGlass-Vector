package com.leo.infinityglass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Infinity Glass Vector v0.3
 * Target: Infinity X / Android 16 / POCO X7 Pro (rodin)
 *
 * Layer 1: keeps the validated v0.2 real SurfaceFlinger background blur.
 * Layer 2: hooks Infinity X's Compose TileDefaults.getColorForState() and replaces
 *          each QS tile surface with a low-alpha, multi-stop specular glass brush.
 *
 * All hooks are fail-soft: if a ROM class changes, SystemUI keeps the stock result.
 */
public final class InfinityGlassHook implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final AtomicBoolean FIRST_FORCED_FRAME = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_GLASS_TILE = new AtomicBoolean(false);

    private static final long COLOR_TRANSPARENT = 0L;
    private static final long COLOR_WHITE = packSrgb(0xFFFFFFFF);

    private static Constructor<?> tileColorsCtor;
    private static Constructor<?> linearGradientCtor;
    private static Method colorBoxMethod;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.3: loading in " + lpparam.packageName);
        installDepthBlurHook(lpparam);
        installTileGlassHook(lpparam);
    }

    /** Keeps the v0.2 blur path that was validated on the user's exact SystemUI build. */
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

                                // Same validated target as v0.2: 168 px when baseMax is 80 px.
                                float multiplier = 1.25f + (0.85f * smoothStep(expansion));
                                int desiredBlur = Math.round(baseMaxBlur * multiplier);
                                int forcedBlur = Math.max(oldBlur, desiredBlur);

                                // iOS-like glass reads better when the wallpaper stays stationary.
                                float forcedZoom = 0f;

                                Object newPair = oldPair.getClass()
                                        .getConstructor(Object.class, Object.class)
                                        .newInstance(Integer.valueOf(forcedBlur), Float.valueOf(forcedZoom));
                                param.setResult(newPair);

                                if (FIRST_FORCED_FRAME.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.3: BLUR_ACTIVE; shade=" + shade
                                            + " qs=" + qs
                                            + " oldBlur=" + oldBlur
                                            + " baseMax=" + baseMaxBlur
                                            + " forcedBlur=" + forcedBlur
                                            + " oldZoom=" + oldZoom);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.3: blur frame hook failed: " + t);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + " v0.3: depth-controller hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.3: unable to install blur hook: " + t);
        }
    }

    /**
     * Replaces Compose TileColors with translucent, diagonal specular gradients.
     * This is what turns the matte QS cards into visible glass plates.
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

                                long originalBackground = XposedHelpers.getLongField(
                                        original, "background");
                                long originalIconBackground = XposedHelpers.getLongField(
                                        original, "iconBackground");

                                final int baseAlpha;
                                final int highlightAlpha;
                                final int midAlpha;
                                final int lowAlpha;
                                final int edgeAlpha;
                                final int outlineAlpha;
                                final int iconHighlightAlpha;
                                final int iconMidAlpha;
                                final int iconLowAlpha;

                                // android.service.quicksettings.Tile: unavailable=0, inactive=1, active=2
                                if (state == 2) {
                                    baseAlpha = 28;          // 11% base tint
                                    highlightAlpha = 112;   // 44% specular highlight
                                    midAlpha = 72;          // 28% glass body
                                    lowAlpha = 48;          // 19% lower body
                                    edgeAlpha = 18;         // 7% falloff
                                    outlineAlpha = 104;     // 41% bright rim
                                    iconHighlightAlpha = 148;
                                    iconMidAlpha = 88;
                                    iconLowAlpha = 28;
                                } else if (state == 1) {
                                    baseAlpha = 20;          // 8% base tint
                                    highlightAlpha = 76;    // 30% highlight
                                    midAlpha = 44;          // 17% glass body
                                    lowAlpha = 30;          // 12% lower body
                                    edgeAlpha = 10;         // 4% falloff
                                    outlineAlpha = 72;      // 28% rim
                                    iconHighlightAlpha = 104;
                                    iconMidAlpha = 58;
                                    iconLowAlpha = 18;
                                } else {
                                    baseAlpha = 12;
                                    highlightAlpha = 42;
                                    midAlpha = 26;
                                    lowAlpha = 16;
                                    edgeAlpha = 6;
                                    outlineAlpha = 42;
                                    iconHighlightAlpha = 54;
                                    iconMidAlpha = 32;
                                    iconLowAlpha = 10;
                                }

                                long base = withAlpha(originalBackground, baseAlpha);

                                // Four-stop diagonal glass body: bright top-left reflection,
                                // tinted translucent center, and soft lower-right falloff.
                                Object tileGradient = createGradient(
                                        mixTowardWhite(originalBackground, 0.88f, highlightAlpha),
                                        mixTowardWhite(originalBackground, 0.42f, midAlpha),
                                        withAlpha(originalBackground, lowAlpha),
                                        mixTowardWhite(originalBackground, 0.78f, edgeAlpha),
                                        Arrays.asList(0.00f, 0.18f, 0.62f, 1.00f));

                                long iconTintSource = originalIconBackground != COLOR_TRANSPARENT
                                        ? originalIconBackground : originalBackground;
                                Object iconGradient = createGradient(
                                        mixTowardWhite(iconTintSource, 0.92f, iconHighlightAlpha),
                                        mixTowardWhite(iconTintSource, 0.50f, iconMidAlpha),
                                        mixTowardWhite(iconTintSource, 0.82f, iconLowAlpha),
                                        null,
                                        Arrays.asList(0.00f, 0.42f, 1.00f));

                                // Keeping iconBackground exactly transparent lets Infinity X draw
                                // the full tileBackgroundGradient even on large dual-target tiles;
                                // the separate iconGradient still paints the raised circular insert.
                                long iconBackground = dualTarget && !iconOnly
                                        ? COLOR_TRANSPARENT
                                        : withAlpha(originalIconBackground, baseAlpha);

                                long label;
                                long secondaryLabel;
                                long iconColor;
                                if (state == 0) {
                                    label = withAlpha(COLOR_WHITE, 128);
                                    secondaryLabel = withAlpha(COLOR_WHITE, 92);
                                    iconColor = withAlpha(COLOR_WHITE, 128);
                                } else {
                                    label = withAlpha(COLOR_WHITE, 244);
                                    secondaryLabel = withAlpha(COLOR_WHITE, 196);
                                    iconColor = withAlpha(COLOR_WHITE, 246);
                                }

                                long outline = withAlpha(COLOR_WHITE, outlineAlpha);

                                Object replacement = tileColorsCtor.newInstance(
                                        base,
                                        iconBackground,
                                        label,
                                        secondaryLabel,
                                        iconColor,
                                        iconGradient,
                                        tileGradient,
                                        outline);
                                param.setResult(replacement);

                                if (FIRST_GLASS_TILE.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.3: TILE_GLASS_ACTIVE; state=" + state
                                            + " dual=" + dualTarget
                                            + " iconOnly=" + iconOnly
                                            + " highlightAlpha=" + highlightAlpha
                                            + " outlineAlpha=" + outlineAlpha);
                                }
                            } catch (Throwable t) {
                                // Fail-soft: keep the stock TileColors result for this frame.
                                param.setResult(original);
                                XposedBridge.log(TAG + " v0.3: tile glass frame failed: " + t);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + " v0.3: tile glass hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.3: unable to install tile glass hook: " + t);
        }
    }

    private static Object createGradient(
            long c1, long c2, long c3, Long c4, List<Float> stops) throws Exception {
        final List<Object> colors;
        if (c4 == null) {
            colors = Arrays.asList(boxColor(c1), boxColor(c2), boxColor(c3));
        } else {
            colors = Arrays.asList(boxColor(c1), boxColor(c2), boxColor(c3), boxColor(c4));
        }

        // Compose Offset packs X in the high float bits and Y in the low float bits.
        long start = packOffset(0f, 0f);
        long end = packOffset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        return linearGradientCtor.newInstance(colors, stops, start, end);
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
            // Material/SystemUI colors used here are normally sRGB. For an unexpected
            // wide-gamut value, fall back to neutral white rather than creating invalid bits.
            return packSrgb((a << 24) | 0x00FFFFFF);
        }
        int argb = (int) (color >>> 32);
        int rgb = argb & 0x00FFFFFF;
        return packSrgb((a << 24) | rgb);
    }

    private static long mixTowardWhite(long color, float amount, int alpha) {
        float t = clamp(amount, 0f, 1f);
        long low32 = color & 0xFFFFFFFFL;
        int argb = low32 == 0L ? (int) (color >>> 32) : 0xFFFFFFFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        r = Math.round(r + (255 - r) * t);
        g = Math.round(g + (255 - g) * t);
        b = Math.round(b + (255 - b) * t);
        int a = Math.max(0, Math.min(255, alpha));
        return packSrgb((a << 24) | (r << 16) | (g << 8) | b);
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
