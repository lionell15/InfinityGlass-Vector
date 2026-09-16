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
 * Infinity Glass Vector v0.7
 * Target: Infinity X / Android 16 / POCO X7 Pro (rodin)
 *
 * v0.7 is tuned against the user's iOS 26 Control Center reference:
 *  - lighter global blur so background colour survives through the glass
 *  - almost clear neutral/teal inactive glass
 *  - vivid iOS-blue active icon lenses
 *  - thin cool-white/cyan physical rim with a faint secondary glow
 *  - matte Material surface remains fully bypassed
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
    private static final long COLOR_ICE = packSrgb(0xFFF7FCFF);
    private static final long COLOR_COOL = packSrgb(0xFFD8F4FF);
    private static final long COLOR_GLASS_TEAL = packSrgb(0xFF88E8E1);
    private static final long COLOR_IOS_BLUE = packSrgb(0xFF0A84FF);
    private static final long COLOR_IOS_CYAN = packSrgb(0xFF64D2FF);
    private static final long COLOR_DEEP_BLUE = packSrgb(0xFF0057D9);
    private static final long COLOR_BLACK = packSrgb(0xFF000000);

    private static Constructor<?> tileColorsCtor;
    private static Constructor<?> linearGradientCtor;
    private static Method colorBoxMethod;
    private static Method borderColorMethod;
    private static Object transparentColorLambda;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.7: loading in " + lpparam.packageName);
        installDepthBlurHook(lpparam);
        installTileGlassHook(lpparam);
        installTransparentSurfaceAndBorderHook(lpparam);
    }

    /**
     * The reference image keeps more colour/detail behind the glass than v0.6.
     * With baseMax=80, full expansion now targets about 104 px instead of 168 px.
     */
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

                                float multiplier = 1.05f + (0.25f * smoothStep(expansion));
                                int desiredBlur = Math.round(baseMaxBlur * multiplier);
                                int forcedBlur = Math.max(oldBlur, desiredBlur);

                                Object newPair = oldPair.getClass()
                                        .getConstructor(Object.class, Object.class)
                                        .newInstance(Integer.valueOf(forcedBlur), Float.valueOf(0f));
                                param.setResult(newPair);

                                if (FIRST_FORCED_FRAME.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.7: BLUR_ACTIVE; shade=" + shade
                                            + " qs=" + qs
                                            + " oldBlur=" + oldBlur
                                            + " baseMax=" + baseMaxBlur
                                            + " forcedBlur=" + forcedBlur
                                            + " oldZoom=" + oldZoom);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.7: blur frame hook failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.7: depth-controller hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.7: unable to install blur hook: " + t);
        }
    }

    /**
     * Final TileColors replacement. Active icon lenses become iOS blue while the
     * plate itself remains clear glass. Inactive controls keep a cool teal/white
     * optical tint rather than Material You's brown surface tint.
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

                                final boolean active = state == 2;
                                final boolean unavailable = state == 0;

                                // Plate body: deliberately subtle so the blurred wallpaper remains visible.
                                long background;
                                if (active && iconOnly) {
                                    // The visible circular control is painted from iconGradient below.
                                    background = COLOR_TRANSPARENT;
                                } else if (active) {
                                    background = withAlpha(COLOR_COOL, 10);
                                } else if (unavailable) {
                                    background = COLOR_TRANSPARENT;
                                } else {
                                    background = withAlpha(COLOR_GLASS_TEAL, 7);
                                }

                                Object tileGradient;
                                if (active) {
                                    tileGradient = createGradient(
                                            Arrays.asList(
                                                    withAlpha(COLOR_ICE, 188),
                                                    withAlpha(COLOR_IOS_CYAN, 62),
                                                    withAlpha(COLOR_COOL, 22),
                                                    withAlpha(COLOR_GLASS_TEAL, 14),
                                                    withAlpha(COLOR_BLACK, 10),
                                                    withAlpha(COLOR_COOL, 8),
                                                    withAlpha(COLOR_IOS_CYAN, 54),
                                                    withAlpha(COLOR_ICE, 96)
                                            ),
                                            Arrays.asList(
                                                    0.00f, 0.035f, 0.10f, 0.38f,
                                                    0.67f, 0.84f, 0.968f, 1.00f));
                                } else {
                                    tileGradient = createGradient(
                                            Arrays.asList(
                                                    withAlpha(COLOR_ICE, unavailable ? 70 : 148),
                                                    withAlpha(COLOR_IOS_CYAN, unavailable ? 20 : 42),
                                                    withAlpha(COLOR_GLASS_TEAL, unavailable ? 4 : 13),
                                                    withAlpha(COLOR_WHITE, unavailable ? 2 : 6),
                                                    withAlpha(COLOR_BLACK, unavailable ? 5 : 9),
                                                    withAlpha(COLOR_COOL, unavailable ? 2 : 5),
                                                    withAlpha(COLOR_IOS_CYAN, unavailable ? 14 : 34),
                                                    withAlpha(COLOR_ICE, unavailable ? 34 : 74)
                                            ),
                                            Arrays.asList(
                                                    0.00f, 0.035f, 0.10f, 0.40f,
                                                    0.68f, 0.85f, 0.97f, 1.00f));
                                }

                                // Circle/icon lens. Active controls now get the familiar iOS blue.
                                Object iconGradient;
                                if (active) {
                                    iconGradient = createGradient(
                                            Arrays.asList(
                                                    withAlpha(COLOR_IOS_CYAN, 248),
                                                    withAlpha(COLOR_IOS_BLUE, 232),
                                                    withAlpha(COLOR_IOS_BLUE, 220),
                                                    withAlpha(COLOR_DEEP_BLUE, 236),
                                                    withAlpha(COLOR_IOS_CYAN, 196)
                                            ),
                                            Arrays.asList(0.00f, 0.10f, 0.46f, 0.82f, 1.00f));
                                } else {
                                    iconGradient = createGradient(
                                            Arrays.asList(
                                                    withAlpha(COLOR_ICE, unavailable ? 70 : 150),
                                                    withAlpha(COLOR_IOS_CYAN, unavailable ? 18 : 44),
                                                    withAlpha(COLOR_GLASS_TEAL, unavailable ? 5 : 18),
                                                    withAlpha(COLOR_BLACK, unavailable ? 7 : 12),
                                                    withAlpha(COLOR_ICE, unavailable ? 26 : 58)
                                            ),
                                            Arrays.asList(0.00f, 0.08f, 0.44f, 0.78f, 1.00f));
                                }

                                // For large dual-target controls, the icon lens is drawn separately by LargeTileContent.
                                long iconBackground = dualTarget && !iconOnly
                                        ? COLOR_TRANSPARENT
                                        : (active
                                                ? withAlpha(COLOR_IOS_BLUE, 176)
                                                : withAlpha(COLOR_GLASS_TEAL, unavailable ? 1 : 5));

                                long label = unavailable
                                        ? withAlpha(COLOR_WHITE, 128)
                                        : withAlpha(COLOR_WHITE, 252);
                                long secondaryLabel = unavailable
                                        ? withAlpha(COLOR_WHITE, 86)
                                        : withAlpha(COLOR_WHITE, 210);
                                long iconColor = unavailable
                                        ? withAlpha(COLOR_WHITE, 128)
                                        : withAlpha(COLOR_WHITE, 254);
                                long outline = withAlpha(COLOR_ICE, unavailable ? 58 : (active ? 168 : 126));

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
                                    XposedBridge.log(TAG + " v0.7: IOS_GLASS_ACTIVE; state=" + state
                                            + " active=" + active
                                            + " dual=" + dualTarget
                                            + " iconOnly=" + iconOnly);
                                }
                            } catch (Throwable t) {
                                param.setResult(original);
                                XposedBridge.log(TAG + " v0.7: iOS glass frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.7: iOS-style tile glass hook installed");
            XposedBridge.log(TAG + " v0.7: MONET_TILE_PATH_BYPASSED");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.7: unable to install tile glass hook: " + t);
        }
    }

    /**
     * Keep the actual Compose Surface transparent and draw a two-stage optical rim:
     * a faint 1.45 dp glow plus a crisp 0.65 dp cool-white edge.
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

                                param.args[0] = transparentColorLambda;

                                Object shapeObj = param.args[1];
                                Object mod = param.args[4];
                                boolean borderApplied = false;

                                if (borderColorMethod != null && shapeObj != null && mod != null) {
                                    String shapeText = String.valueOf(shapeObj);
                                    boolean zeroCornerPlaceholder = shapeText.contains("0.0.dp")
                                            && !shapeText.contains("50.0");
                                    if (!zeroCornerPlaceholder) {
                                        Object glow = borderColorMethod.invoke(
                                                null,
                                                mod,
                                                Float.valueOf(1.45f),
                                                Long.valueOf(withAlpha(COLOR_IOS_CYAN, 34)),
                                                shapeObj);
                                        Object crisp = borderColorMethod.invoke(
                                                null,
                                                glow != null ? glow : mod,
                                                Float.valueOf(0.65f),
                                                Long.valueOf(withAlpha(COLOR_ICE, 136)),
                                                shapeObj);
                                        if (crisp != null) {
                                            param.args[4] = crisp;
                                            borderApplied = true;
                                        }
                                    }
                                }

                                if (FIRST_CLEAR_SURFACE.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.7: CLEAR_SURFACE_ACTIVE; doubleBorder="
                                            + borderApplied + " shape=" + String.valueOf(shapeObj));
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.7: clear-surface frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.7: transparent surface + dual optical border installed; borderMethod="
                    + (borderColorMethod != null));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.7: unable to install transparent-surface hook: " + t);
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
