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
 * Infinity Glass Vector v0.4
 * Target: Infinity X / Android 16 / POCO X7 Pro (rodin)
 *
 * v0.4 keeps the validated 168 px real SurfaceFlinger background blur from v0.2/v0.3,
 * but changes the Quick Settings tiles from Material-tinted translucent cards into neutral
 * glass plates inspired by the iOS 26 Liquid Glass visual language.
 *
 * The glass illusion is produced with:
 *  - low-alpha neutral plate fill
 *  - sharp top-left specular highlight
 *  - translucent middle body
 *  - darker optical falloff
 *  - subtle lower-right secondary reflection
 *  - brighter floating icon insert for dual-target tiles
 *
 * No SystemUI APK is replaced. Hooks are fail-soft.
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

        XposedBridge.log(TAG + " v0.4: loading in " + lpparam.packageName);
        installDepthBlurHook(lpparam);
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

                                // Preserve the already validated curve: 168 px at full expansion
                                // when Infinity X reports baseMax=80 px.
                                float multiplier = 1.25f + (0.85f * smoothStep(expansion));
                                int desiredBlur = Math.round(baseMaxBlur * multiplier);
                                int forcedBlur = Math.max(oldBlur, desiredBlur);

                                // Keep the wallpaper stationary behind the glass surface.
                                float forcedZoom = 0f;

                                Object newPair = oldPair.getClass()
                                        .getConstructor(Object.class, Object.class)
                                        .newInstance(Integer.valueOf(forcedBlur), Float.valueOf(forcedZoom));
                                param.setResult(newPair);

                                if (FIRST_FORCED_FRAME.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.4: BLUR_ACTIVE; shade=" + shade
                                            + " qs=" + qs
                                            + " oldBlur=" + oldBlur
                                            + " baseMax=" + baseMaxBlur
                                            + " forcedBlur=" + forcedBlur
                                            + " oldZoom=" + oldZoom);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.4: blur frame hook failed: " + t);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + " v0.4: depth-controller hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.4: unable to install blur hook: " + t);
        }
    }

    /**
     * Neutral Liquid-Glass-style plates for Compose Quick Settings tiles.
     *
     * Unlike v0.3, the plate body no longer inherits the Material You surface tint. That was
     * responsible for the matte brown appearance in the user's screenshot. The wallpaper can
     * still tint the glass naturally through the real blur underneath it.
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

                                // android.service.quicksettings.Tile:
                                // unavailable=0, inactive=1, active=2
                                final int plateAlpha;
                                final int edgeHot;
                                final int edgeShoulder;
                                final int upperBody;
                                final int centerBody;
                                final int lowerBody;
                                final int lowerReflection;
                                final int endReflection;
                                final int iconHot;
                                final int iconBody;
                                final int iconLow;
                                final int outlineAlpha;

                                if (state == 2) {
                                    // Active: more luminous but still transparent, similar to a
                                    // selected Liquid Glass control rather than a solid accent pill.
                                    plateAlpha = 34;
                                    edgeHot = 182;
                                    edgeShoulder = 118;
                                    upperBody = 70;
                                    centerBody = 46;
                                    lowerBody = 24;
                                    lowerReflection = 56;
                                    endReflection = 96;
                                    iconHot = 188;
                                    iconBody = 92;
                                    iconLow = 38;
                                    outlineAlpha = 132;
                                } else if (state == 1) {
                                    // Inactive: very clear neutral glass with a crisp rim.
                                    plateAlpha = 20;
                                    edgeHot = 132;
                                    edgeShoulder = 82;
                                    upperBody = 48;
                                    centerBody = 30;
                                    lowerBody = 15;
                                    lowerReflection = 38;
                                    endReflection = 70;
                                    iconHot = 132;
                                    iconBody = 62;
                                    iconLow = 24;
                                    outlineAlpha = 92;
                                } else {
                                    plateAlpha = 10;
                                    edgeHot = 66;
                                    edgeShoulder = 44;
                                    upperBody = 26;
                                    centerBody = 16;
                                    lowerBody = 8;
                                    lowerReflection = 20;
                                    endReflection = 36;
                                    iconHot = 70;
                                    iconBody = 36;
                                    iconLow = 14;
                                    outlineAlpha = 48;
                                }

                                // Neutral plate base. Wallpaper color now comes from the blurred
                                // scene behind it, not from Material You's qsTileColor.
                                long background = withAlpha(COLOR_WHITE, plateAlpha);

                                // Seven-stop diagonal optical profile. The first two stops create
                                // a sharp specular rim; the middle falls away like thicker glass;
                                // the last two stops create a weaker reflected edge.
                                List<Long> tileColors = new ArrayList<>();
                                tileColors.add(withAlpha(COLOR_WHITE, edgeHot));
                                tileColors.add(withAlpha(COLOR_WHITE, edgeShoulder));
                                tileColors.add(withAlpha(COLOR_WHITE, upperBody));

                                // Keep a tiny amount of the active system accent inside the center
                                // only. Inactive plates stay fully neutral.
                                if (state == 2) {
                                    tileColors.add(mixTowardWhite(originalBackground, 0.84f, centerBody));
                                } else {
                                    tileColors.add(withAlpha(COLOR_WHITE, centerBody));
                                }

                                tileColors.add(withAlpha(COLOR_WHITE, lowerBody));
                                tileColors.add(withAlpha(COLOR_WHITE, lowerReflection));
                                tileColors.add(withAlpha(COLOR_WHITE, endReflection));

                                Object tileGradient = createGradient(
                                        tileColors,
                                        Arrays.asList(0.00f, 0.045f, 0.13f, 0.42f, 0.72f, 0.92f, 1.00f));

                                // Raised circular/square icon insert: brighter and optically denser
                                // than the surrounding plate, like Control Center controls.
                                Object iconGradient = createGradient(
                                        Arrays.asList(
                                                withAlpha(COLOR_WHITE, iconHot),
                                                withAlpha(COLOR_WHITE, iconBody),
                                                withAlpha(COLOR_WHITE, iconLow),
                                                withAlpha(COLOR_WHITE, Math.max(12, iconBody / 2))
                                        ),
                                        Arrays.asList(0.00f, 0.12f, 0.72f, 1.00f));

                                // For large dual-target tiles, keeping iconBackground transparent is
                                // required by Infinity X for the full tileBackgroundGradient path.
                                long iconBackground = dualTarget && !iconOnly
                                        ? COLOR_TRANSPARENT
                                        : withAlpha(COLOR_WHITE, Math.max(12, plateAlpha + 8));

                                long label;
                                long secondaryLabel;
                                long iconColor;
                                if (state == 0) {
                                    label = withAlpha(COLOR_WHITE, 132);
                                    secondaryLabel = withAlpha(COLOR_WHITE, 92);
                                    iconColor = withAlpha(COLOR_WHITE, 132);
                                } else {
                                    label = withAlpha(COLOR_WHITE, 250);
                                    secondaryLabel = withAlpha(COLOR_WHITE, 208);
                                    iconColor = withAlpha(COLOR_WHITE, 252);
                                }

                                long outline = withAlpha(COLOR_WHITE, outlineAlpha);

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
                                    XposedBridge.log(TAG + " v0.4: LIQUID_PLATE_ACTIVE; state=" + state
                                            + " dual=" + dualTarget
                                            + " iconOnly=" + iconOnly
                                            + " plateAlpha=" + plateAlpha
                                            + " edgeHot=" + edgeHot
                                            + " outlineAlpha=" + outlineAlpha);
                                }
                            } catch (Throwable t) {
                                param.setResult(original);
                                XposedBridge.log(TAG + " v0.4: tile glass frame failed: " + t);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + " v0.4: liquid plate hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.4: unable to install liquid plate hook: " + t);
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
