package com.leo.infinityglass;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

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
 * Infinity Glass Vector v0.8 extras.
 *
 * Runs before the v0.7 base hook (see xposed_init), so its AFTER hook for
 * TileDefaults runs last and can refine icon-only/circular tiles.
 *
 * Adds:
 *  - QS-only neutral scrim (removes the brown/Monet wash without disabling Monet globally)
 *  - translucent media-card background with optical double rim
 *  - sharper iOS-like circular tile lenses
 *  - ringer slider glass/blue theme
 *  - best-effort brightness SliderColors neutralisation
 *
 * All hooks are fail-soft and affect com.android.systemui only.
 */
public final class InfinityGlassV08Extras implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";

    private static volatile float qsExpansion = 0f;

    private static final AtomicBoolean FIRST_SCRIM = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_MEDIA = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_CIRCLE = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_RINGER = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_BRIGHTNESS = new AtomicBoolean(false);

    private static final long COLOR_TRANSPARENT = 0L;
    private static final long COLOR_WHITE = packSrgb(0xFFFFFFFF);
    private static final long COLOR_ICE = packSrgb(0xFFF7FCFF);
    private static final long COLOR_COOL = packSrgb(0xFFD9F4FF);
    private static final long COLOR_TEAL = packSrgb(0xFF72DDD8);
    private static final long COLOR_IOS_BLUE = packSrgb(0xFF0A84FF);
    private static final long COLOR_IOS_CYAN = packSrgb(0xFF64D2FF);
    private static final long COLOR_DEEP_BLUE = packSrgb(0xFF0057D9);
    private static final long COLOR_BLACK = packSrgb(0xFF000000);

    private static Constructor<?> tileColorsCtor;
    private static Constructor<?> linearGradientCtor;
    private static Method colorBoxMethod;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.8: extras loading in " + lpparam.packageName);
        installQsExpansionTracker(lpparam);
        installScrimNeutralizer(lpparam);
        installMediaGlass(lpparam);
        installCircleTileLens(lpparam);
        installRingerGlass(lpparam);
        installBrightnessGlass(lpparam);
    }

    private static void installQsExpansionTracker(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.ScrimController", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    controller,
                    "setQsPosition",
                    float.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object value = param.args[0];
                            if (value instanceof Number) {
                                qsExpansion = clamp(((Number) value).floatValue(), 0f, 1f);
                            }
                        }
                    });
            XposedBridge.log(TAG + " v0.8: QS expansion tracker installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.8: QS expansion tracker unavailable: " + t);
        }
    }

    /**
     * Infinity X scrims can keep a wallpaper-derived brown tint even after tile
     * surfaces are transparent. While QS is open, bypass that tint and use only a
     * very light neutral-black veil. When QS closes, restore normal blending.
     */
    private static void installScrimNeutralizer(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> scrimView = XposedHelpers.findClass(
                    "com.android.systemui.scrim.ScrimView", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    scrimView,
                    "setTint",
                    int.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                View view = (View) param.thisObject;
                                String name = resourceEntryName(view);
                                boolean behind = "scrim_behind".equals(name);
                                boolean notifications = "scrim_notifications".equals(name);
                                if (!behind && !notifications) return;

                                if (qsExpansion > 0.025f) {
                                    XposedHelpers.setBooleanField(param.thisObject,
                                            "mBlendWithMainColor", false);
                                    int maxAlpha = behind ? 24 : 16;
                                    int a = Math.max(8, Math.round(maxAlpha * qsExpansion));
                                    param.args[0] = Integer.valueOf(Color.argb(a, 4, 9, 13));
                                    if (FIRST_SCRIM.compareAndSet(false, true)) {
                                        XposedBridge.log(TAG + " v0.8: SCRIM_NEUTRAL_ACTIVE; id="
                                                + name + " alpha=" + a);
                                    }
                                } else {
                                    XposedHelpers.setBooleanField(param.thisObject,
                                            "mBlendWithMainColor", true);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.8: scrim frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.8: QS scrim neutralizer installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.8: scrim neutralizer unavailable: " + t);
        }
    }

    /**
     * Media controls use IlluminationDrawable. During QS expansion we lower the
     * drawable body alpha, neutralize its RGB and draw an optical double rim.
     * The original paint is restored after every frame, so lockscreen media keeps
     * its normal behaviour.
     */
    private static void installMediaGlass(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> illumination = XposedHelpers.findClass(
                    "com.android.systemui.media.controls.ui.drawable.IlluminationDrawable",
                    lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    illumination,
                    "draw",
                    Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (qsExpansion <= 0.025f) return;
                            try {
                                Paint paint = (Paint) XposedHelpers.getObjectField(
                                        param.thisObject, "paint");
                                if (paint == null) return;
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "ig08_old_color", paint.getColor());
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "ig08_old_alpha", paint.getAlpha());

                                // Neutral frosted body; let wallpaper colours show through.
                                paint.setColor(Color.rgb(226, 240, 247));
                                paint.setAlpha(44 + Math.round(14f * qsExpansion));
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.8: media pre-draw failed: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (qsExpansion <= 0.025f) return;
                            try {
                                Canvas canvas = (Canvas) param.args[0];
                                Drawable drawable = (Drawable) param.thisObject;
                                Rect b = drawable.getBounds();
                                if (canvas != null && b != null && b.width() > 0 && b.height() > 0) {
                                    float radius = readCornerRadius(param.thisObject);
                                    float min = Math.min(b.width(), b.height());

                                    Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
                                    glow.setStyle(Paint.Style.STROKE);
                                    glow.setStrokeWidth(Math.max(2f, min * 0.010f));
                                    glow.setColor(Color.argb(34, 100, 210, 255));
                                    float gInset = glow.getStrokeWidth() / 2f;
                                    canvas.drawRoundRect(
                                            b.left + gInset, b.top + gInset,
                                            b.right - gInset, b.bottom - gInset,
                                            Math.max(0f, radius - gInset),
                                            Math.max(0f, radius - gInset), glow);

                                    Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
                                    rim.setStyle(Paint.Style.STROKE);
                                    rim.setStrokeWidth(Math.max(1.2f, min * 0.0045f));
                                    rim.setColor(Color.argb(124, 245, 252, 255));
                                    float rInset = gInset + rim.getStrokeWidth();
                                    canvas.drawRoundRect(
                                            b.left + rInset, b.top + rInset,
                                            b.right - rInset, b.bottom - rInset,
                                            Math.max(0f, radius - rInset),
                                            Math.max(0f, radius - rInset), rim);

                                    if (FIRST_MEDIA.compareAndSet(false, true)) {
                                        XposedBridge.log(TAG + " v0.8: MEDIA_GLASS_ACTIVE; radius="
                                                + radius + " alpha=" + (44 + Math.round(14f * qsExpansion)));
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.8: media post-draw failed: " + t);
                            } finally {
                                try {
                                    Paint paint = (Paint) XposedHelpers.getObjectField(
                                            param.thisObject, "paint");
                                    Object oldColor = XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "ig08_old_color");
                                    Object oldAlpha = XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "ig08_old_alpha");
                                    if (paint != null && oldColor instanceof Integer) {
                                        paint.setColor((Integer) oldColor);
                                    }
                                    if (paint != null && oldAlpha instanceof Integer) {
                                        paint.setAlpha((Integer) oldAlpha);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.8: media glass hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.8: media glass unavailable: " + t);
        }
    }

    /**
     * v0.7 already handles large plates. This late AFTER hook specifically
     * refines iconOnly circles so their body is clearer and the specular bands
     * carry the lens shape instead of a broad matte fill.
     */
    private static void installCircleTileLens(final XC_LoadPackage.LoadPackageParam lpparam) {
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
                            try {
                                boolean iconOnly = (Boolean) param.args[1];
                                if (!iconOnly) return;

                                Object uiState = param.args[0];
                                int state = XposedHelpers.getIntField(uiState, "state");
                                boolean active = state == 2;
                                boolean unavailable = state == 0;

                                Object iconGradient;
                                if (active) {
                                    iconGradient = createGradient(
                                            Arrays.asList(
                                                    withAlpha(COLOR_IOS_CYAN, 255),
                                                    withAlpha(COLOR_IOS_BLUE, 238),
                                                    withAlpha(COLOR_IOS_BLUE, 220),
                                                    withAlpha(COLOR_DEEP_BLUE, 232),
                                                    withAlpha(COLOR_IOS_CYAN, 205),
                                                    withAlpha(COLOR_ICE, 118)),
                                            Arrays.asList(0.00f, 0.07f, 0.42f, 0.78f, 0.955f, 1.00f));
                                } else {
                                    iconGradient = createGradient(
                                            Arrays.asList(
                                                    withAlpha(COLOR_ICE, unavailable ? 72 : 176),
                                                    withAlpha(COLOR_IOS_CYAN, unavailable ? 18 : 46),
                                                    withAlpha(COLOR_WHITE, unavailable ? 3 : 9),
                                                    withAlpha(COLOR_TRANSPARENT, 0),
                                                    withAlpha(COLOR_BLACK, unavailable ? 4 : 7),
                                                    withAlpha(COLOR_IOS_CYAN, unavailable ? 12 : 32),
                                                    withAlpha(COLOR_ICE, unavailable ? 30 : 82)),
                                            Arrays.asList(0.00f, 0.035f, 0.12f, 0.48f, 0.74f, 0.965f, 1.00f));
                                }

                                long iconBg = active
                                        ? withAlpha(COLOR_IOS_BLUE, 156)
                                        : COLOR_TRANSPARENT;
                                long label = unavailable
                                        ? withAlpha(COLOR_WHITE, 128)
                                        : withAlpha(COLOR_WHITE, 252);
                                long secondary = unavailable
                                        ? withAlpha(COLOR_WHITE, 86)
                                        : withAlpha(COLOR_WHITE, 208);
                                long icon = unavailable
                                        ? withAlpha(COLOR_WHITE, 128)
                                        : withAlpha(COLOR_WHITE, 254);
                                long outline = withAlpha(COLOR_ICE,
                                        unavailable ? 50 : (active ? 170 : 116));

                                Object replacement = tileColorsCtor.newInstance(
                                        COLOR_TRANSPARENT,
                                        iconBg,
                                        label,
                                        secondary,
                                        icon,
                                        iconGradient,
                                        null,
                                        outline);
                                param.setResult(replacement);

                                if (FIRST_CIRCLE.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.8: CIRCLE_GLASS_ACTIVE; state="
                                            + state + " active=" + active);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.8: circle lens frame failed: " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.8: circular lens hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.8: circular lens unavailable: " + t);
        }
    }

    /** Glassify the sound/ringer slider theme without touching Material globally. */
    private static void installRingerGlass(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> theme = XposedHelpers.findClass(
                    "com.android.systemui.qs.tiles.impl.ringer.QSTileRingerTheme",
                    lpparam.classLoader);
            int hooked = 0;
            for (final Method method : theme.getDeclaredMethods()) {
                final String n = method.getName();
                final long value;
                if (n.startsWith("getNeutralBg")) {
                    value = withAlpha(COLOR_COOL, 14);
                } else if (n.startsWith("getActiveBg") || n.startsWith("getDndBg")) {
                    value = withAlpha(COLOR_IOS_BLUE, 224);
                } else if (n.startsWith("getActiveIcon") || n.startsWith("getNeutralIcon")
                        || n.startsWith("getDndIcon")) {
                    value = withAlpha(COLOR_WHITE, 252);
                } else {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(Long.valueOf(value));
                        if (FIRST_RINGER.compareAndSet(false, true)) {
                            XposedBridge.log(TAG + " v0.8: RINGER_GLASS_ACTIVE; method=" + n);
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " v0.8: ringer glass hooks=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.8: ringer glass unavailable: " + t);
        }
    }

    /**
     * Best-effort mutation of the QS brightness SliderColors object. The field
     * names are stable Material3 properties; if this ROM's Compose build differs,
     * the hook simply logs and leaves stock colours intact.
     */
    private static void installBrightnessGlass(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> brightnessKt = XposedHelpers.findClass(
                    "com.android.systemui.brightness.ui.compose.BrightnessSliderKt",
                    lpparam.classLoader);
            int hooked = 0;
            for (final Method method : brightnessKt.getDeclaredMethods()) {
                if (!method.getName().startsWith("colors")) continue;
                if (!"androidx.compose.material3.SliderColors".equals(
                        method.getReturnType().getName())) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object colors = param.getResult();
                        if (colors == null) return;
                        try {
                            setLongFieldIfPresent(colors, "thumbColor", withAlpha(COLOR_WHITE, 246));
                            setLongFieldIfPresent(colors, "activeTrackColor", withAlpha(COLOR_WHITE, 224));
                            setLongFieldIfPresent(colors, "activeTickColor", withAlpha(COLOR_BLACK, 190));
                            setLongFieldIfPresent(colors, "inactiveTrackColor", withAlpha(COLOR_COOL, 20));
                            setLongFieldIfPresent(colors, "inactiveTickColor", withAlpha(COLOR_WHITE, 226));
                            setLongFieldIfPresent(colors, "disabledThumbColor", withAlpha(COLOR_WHITE, 110));
                            setLongFieldIfPresent(colors, "disabledActiveTrackColor", withAlpha(COLOR_WHITE, 92));
                            setLongFieldIfPresent(colors, "disabledActiveTickColor", withAlpha(COLOR_BLACK, 90));
                            setLongFieldIfPresent(colors, "disabledInactiveTrackColor", withAlpha(COLOR_COOL, 10));
                            setLongFieldIfPresent(colors, "disabledInactiveTickColor", withAlpha(COLOR_WHITE, 100));
                            if (FIRST_BRIGHTNESS.compareAndSet(false, true)) {
                                XposedBridge.log(TAG + " v0.8: BRIGHTNESS_GLASS_ACTIVE; method="
                                        + method.getName());
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " v0.8: brightness color frame failed: " + t);
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " v0.8: brightness color hooks=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.8: brightness glass unavailable: " + t);
        }
    }

    private static void setLongFieldIfPresent(Object obj, String field, long value) {
        try {
            XposedHelpers.setLongField(obj, field, value);
        } catch (Throwable ignored) {}
    }

    private static String resourceEntryName(View view) {
        try {
            if (view == null || view.getId() == View.NO_ID) return "";
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static float readCornerRadius(Object drawable) {
        try {
            Object value = XposedHelpers.callMethod(drawable, "getCornerRadius");
            if (value instanceof Number) return ((Number) value).floatValue();
        } catch (Throwable ignored) {}
        try {
            return XposedHelpers.getFloatField(drawable, "cornerRadius");
        } catch (Throwable ignored) {}
        return 28f;
    }

    private static Object createGradient(List<Long> colorValues, List<Float> stops)
            throws Exception {
        List<Object> boxed = new ArrayList<>(colorValues.size());
        for (Long color : colorValues) boxed.add(boxColor(color.longValue()));
        long start = packOffset(0f, 0f);
        long end = packOffset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        return linearGradientCtor.newInstance(boxed, stops, start, end);
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
