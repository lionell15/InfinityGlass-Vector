package com.leo.infinityglass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
 * Infinity Glass Vector v0.6
 * Target: Infinity X / Android 16 / POCO X7 Pro (rodin)
 *
 * v0.6 is based on the exact SystemUI APK from the device. In that APK the
 * CustomColorScheme source logic has been compiler-inlined into TileDefaults,
 * so there is no runtime CustomColorScheme class to hook. Instead we bypass
 * Material/Monet at the final tile surface itself.
 *
 * Layers:
 *  1) validated real SurfaceFlinger blur (168 px at full shade)
 *  2) optical low-alpha tile gradient
 *  3) force TileExpandable outer surface fully transparent
 *  4) add a real 1 dp Compose border around modern tiles
 *
 * No SystemUI APK replacement. All hooks are fail-soft.
 */
public final class InfinityGlassHook implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final AtomicBoolean FIRST_FORCED_FRAME = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_GLASS_TILE = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_CLEAR_SURFACE = new AtomicBoolean(false);

    private static final long COLOR_TRANSPARENT = 0L;
    private static final long COLOR_WHITE = packSrgb(0xFFFFFFFF);
    private static final long COLOR_ICE = packSrgb(0xFFF4FBFF);
    private static final long COLOR_COOL = packSrgb(0xFFDDEEFF);
    private static final long COLOR_BLACK = packSrgb(0xFF000000);

    private static Constructor<?> tileColorsCtor;
    private static Constructor<?> linearGradientCtor;
    private static Method colorBoxMethod;
    private static Method borderColorMethod;
    private static Object transparentColorLambda;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.6: loading in " + lpparam.packageName);
        installDepthBlurHook(lpparam);
        installTileGlassHook(lpparam);
        installTransparentSurfaceAndBorderHook(lpparam);
    }

    private static void installDepthBlurHook(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> depthController = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.NotificationShadeDepthController",
                    lpparam.classLoader);

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

                                float multiplier = 1.25f + (0.85f * smoothStep(expansion));
                                int desiredBlur = Math.round(baseMaxBlur * multiplier);
                                int forcedBlur = Math.max(oldBlur, desiredBlur);

                                Object newPair = oldPair.getClass()
                                        .getConstructor(Object.class, Object.class)
                                        .newInstance(Integer.valueOf(forcedBlur), Float.valueOf(0f));
                                param.setResult(newPair);

                                if (FIRST_FORCED_FRAME.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.6: BLUR_ACTIVE; shade=" + shade
                                            + " qs=" + qs
                                            + " oldBlur=" + oldBlur
                                            + " baseMax=" + baseMaxBlur
                                            + " forcedBlur=" + forcedBlur
                                            + " oldZoom=" + oldZoom);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.6: blur frame hook failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.6: depth-controller hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.6: unable to install blur hook: " + t);
        }
    }

    /**
     * Final TileColors replacement. This already bypasses Monet for tile body colors,
     * so v0.6 no longer tries to hook the non-existent runtime CustomColorScheme class.
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
                                    plateAlpha = 4;
                                    edgeHot = 230;
                                    edgeShoulder = 92;
                                    bodyAlpha = 14;
                                    innerShadow = 18;
                                    returnEdge = 70;
                                    tailEdge = 28;
                                    iconHot = 232;
                                    iconBody = 82;
                                    iconShadow = 22;
                                    outlineAlpha = 164;
                                } else if (state == 1) {
                                    plateAlpha = 0;
                                    edgeHot = 188;
                                    edgeShoulder = 56;
                                    bodyAlpha = 8;
                                    innerShadow = 12;
                                    returnEdge = 42;
                                    tailEdge = 16;
                                    iconHot = 184;
                                    iconBody = 52;
                                    iconShadow = 16;
                                    outlineAlpha = 126;
                                } else {
                                    plateAlpha = 0;
                                    edgeHot = 96;
                                    edgeShoulder = 32;
                                    bodyAlpha = 4;
                                    innerShadow = 8;
                                    returnEdge = 24;
                                    tailEdge = 10;
                                    iconHot = 96;
                                    iconBody = 30;
                                    iconShadow = 10;
                                    outlineAlpha = 66;
                                }

                                long background = plateAlpha == 0
                                        ? COLOR_TRANSPARENT
                                        : withAlpha(COLOR_WHITE, plateAlpha);

                                Object tileGradient = createGradient(
                                        Arrays.asList(
                                                withAlpha(COLOR_ICE, edgeHot),
                                                withAlpha(COLOR_COOL, edgeShoulder),
                                                withAlpha(COLOR_WHITE, bodyAlpha),
                                                withAlpha(COLOR_WHITE, Math.max(1, bodyAlpha / 2)),
                                                withAlpha(COLOR_BLACK, innerShadow),
                                                withAlpha(COLOR_WHITE, Math.max(1, bodyAlpha / 3)),
                                                withAlpha(COLOR_ICE, returnEdge),
                                                withAlpha(COLOR_WHITE, tailEdge)
                                        ),
                                        Arrays.asList(
                                                0.00f, 0.028f, 0.075f, 0.37f,
                                                0.66f, 0.84f, 0.972f, 1.00f));

                                Object iconGradient = createGradient(
                                        Arrays.asList(
                                                withAlpha(COLOR_ICE, iconHot),
                                                withAlpha(COLOR_COOL, iconBody),
                                                withAlpha(COLOR_WHITE, Math.max(6, iconBody / 3)),
                                                withAlpha(COLOR_BLACK, iconShadow),
                                                withAlpha(COLOR_WHITE, Math.max(8, iconBody / 4))
                                        ),
                                        Arrays.asList(0.00f, 0.055f, 0.42f, 0.77f, 1.00f));

                                long iconBackground = dualTarget && !iconOnly
                                        ? COLOR_TRANSPARENT
                                        : withAlpha(COLOR_WHITE, state == 2 ? 8 : 3);

                                long label = state == 0
                                        ? withAlpha(COLOR_WHITE, 132)
                                        : withAlpha(COLOR_WHITE, 252);
                                long secondaryLabel = state == 0
                                        ? withAlpha(COLOR_WHITE, 92)
                                        : withAlpha(COLOR_WHITE, 212);
                                long iconColor = state == 0
                                        ? withAlpha(COLOR_WHITE, 132)
                                        : withAlpha(COLOR_WHITE, 252);
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
                                    XposedBridge.log(TAG + " v0.6: OPTICAL_GLASS_ACTIVE; state=" + state
                                            + " dual=" + dualTarget
                                            + " iconOnly=" + iconOnly
                                            + " plateAlpha=" + plateAlpha
                                            + " edgeHot=" + edgeHot
                                            + " outlineAlpha=" + outlineAlpha);
                                }
                            } catch (Throwable t) {
                                param.setResult(original);
                                XposedBridge.log(TAG + " v0.6: optical glass frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.6: optical glass tile hook installed");
            XposedBridge.log(TAG + " v0.6: MONET_TILE_PATH_BYPASSED; exact ROM has inlined color logic");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.6: unable to install optical glass hook: " + t);
        }
    }

    /**
     * Key v0.6 change: remove the matte Surface painted by TileExpandable itself.
     * The optical gradient remains in the tile content, while a real Compose border
     * gives the plate a crisp physical edge.
     */
    private static void installTransparentSurfaceAndBorderHook(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final ClassLoader cl = lpparam.classLoader;
            final Class<?> tileKt = XposedHelpers.findClass(
                    "com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt", cl);
            final Class<?> function0 = XposedHelpers.findClass(
                    "kotlin.jvm.functions.Function0", cl);
            final Class<?> function3 = XposedHelpers.findClass(
                    "kotlin.jvm.functions.Function3", cl);
            final Class<?> shape = XposedHelpers.findClass(
                    "androidx.compose.ui.graphics.Shape", cl);
            final Class<?> modifier = XposedHelpers.findClass(
                    "androidx.compose.ui.Modifier", cl);
            final Class<?> composer = XposedHelpers.findClass(
                    "androidx.compose.runtime.Composer", cl);
            final Class<?> borderKt = XposedHelpers.findClass(
                    "androidx.compose.foundation.BorderKt", cl);

            if (colorBoxMethod == null) {
                Class<?> color = XposedHelpers.findClass(
                        "androidx.compose.ui.graphics.Color", cl);
                colorBoxMethod = color.getDeclaredMethod("box-impl", long.class);
                colorBoxMethod.setAccessible(true);
            }

            for (Method m : borderKt.getDeclaredMethods()) {
                if (m.getName().equals("border-xT4_qwU") && m.getParameterTypes().length == 4) {
                    m.setAccessible(true);
                    borderColorMethod = m;
                    break;
                }
            }

            transparentColorLambda = Proxy.newProxyInstance(
                    function0.getClassLoader(),
                    new Class<?>[] { function0 },
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("invoke".equals(name)) return boxColor(COLOR_TRANSPARENT);
                        if ("toString".equals(name)) return "InfinityGlassTransparentColor";
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                        return null;
                    });

            XposedHelpers.findAndHookMethod(
                    tileKt,
                    "TileExpandable",
                    function0,
                    shape,
                    function0,
                    boolean.class,
                    modifier,
                    function3,
                    composer,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                boolean classicStyle = (Boolean) param.args[3];
                                if (classicStyle) return;

                                // Kill the matte Material surface completely.
                                param.args[0] = transparentColorLambda;

                                Object shapeObj = param.args[1];
                                Object mod = param.args[4];
                                boolean borderApplied = false;

                                if (borderColorMethod != null && shapeObj != null && mod != null) {
                                    String shapeText = String.valueOf(shapeObj);
                                    // Infinity X uses a zero-corner outer placeholder for one
                                    // special circle path. Skip that placeholder to avoid a box rim.
                                    boolean zeroCornerPlaceholder = shapeText.contains("0.0.dp")
                                            && !shapeText.contains("50.0");
                                    if (!zeroCornerPlaceholder) {
                                        Object bordered = borderColorMethod.invoke(
                                                null,
                                                mod,
                                                Float.valueOf(1.0f),
                                                Long.valueOf(withAlpha(COLOR_ICE, 108)),
                                                shapeObj);
                                        if (bordered != null) {
                                            param.args[4] = bordered;
                                            borderApplied = true;
                                        }
                                    }
                                }

                                if (FIRST_CLEAR_SURFACE.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.6: CLEAR_SURFACE_ACTIVE; border="
                                            + borderApplied + " shape=" + String.valueOf(shapeObj));
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.6: clear-surface frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.6: transparent TileExpandable + real border hook installed; borderMethod="
                    + (borderColorMethod != null));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.6: unable to install transparent-surface hook: " + t);
        }
    }

    private static Object createGradient(List<Long> colorValues, List<Float> stops)
            throws Exception {
        List<Object> boxedColors = new ArrayList<>(colorValues.size());
        for (Long color : colorValues) boxedColors.add(boxColor(color.longValue()));
        long start = packOffset(0f, 0f);
        long end = packOffset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        return linearGradientCtor.newInstance(boxedColors, stops, start, end);
    }

    private static Object boxColor(long value) throws Exception {
        return colorBoxMethod.invoke(null, value);
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
