package com.leo.infinityglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Infinity Glass Vector v0.13 - media + brightness + volume glass.
 *
 * Keeps the v0.12 tile/circle styling untouched and extends the same visual
 * language to Infinity X's custom QS brightness module, volume module and
 * media player:
 *
 *  - low-fill adaptive/wallpaper-derived slider body
 *  - thin neutral optical rim, matching the long QS pills
 *  - active slider fill remains visible but translucent
 *  - no forced blue/cyan palette
 *  - media card uses its native adaptive hue at very low alpha + thin rim
 *
 * Everything is scoped to com.android.systemui and is fail-soft.
 */
public final class InfinityGlassV13MediaSliders implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final int MODE_NONE = 0;
    private static final int MODE_BRIGHTNESS = 1;
    private static final int MODE_VOLUME = 2;

    private static final long COLOR_WHITE = packSrgb(0xFFFFFFFF);
    private static final long COLOR_WARM_FALLBACK = packSrgb(0xFF8A6858);

    private static volatile float shadeExpansion = 0f;

    private static final ThreadLocal<Integer> MODULE_MODE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> BG_COUNT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> LAST_BG_INDEX = new ThreadLocal<>();
    private static final ThreadLocal<Object> LAST_CLIP_SHAPE = new ThreadLocal<>();
    private static final ThreadLocal<Long> ADAPTIVE_TONE = new ThreadLocal<>();

    private static final AtomicBoolean FIRST_BRIGHTNESS = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_VOLUME = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_MEDIA = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_GRADIENT = new AtomicBoolean(false);

    private static Method borderColorMethod;
    private static Constructor<?> solidColorCtor;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.13: media/sliders glass loading in " + lpparam.packageName);
        installShadeTracker(lpparam);
        installComposeInfrastructure(lpparam);
        installInfinitySliderScopes(lpparam);
        installMediaGlass(lpparam);
    }

    /** Track the real shade/QS expansion from the controller already used by our blur hook. */
    private static void installShadeTracker(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> depth = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.NotificationShadeDepthController",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    depth,
                    "computeBlurAndZoomOut",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            float shade = safeFloatField(param.thisObject, "shadeExpansion");
                            float qs = safeFloatField(param.thisObject, "qsPanelExpansion");
                            shadeExpansion = clamp(Math.max(shade, qs), 0f, 1f);
                        }
                    });
            XposedBridge.log(TAG + " v0.13: shade tracker installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.13: shade tracker unavailable: " + t);
        }
    }

    /**
     * Hooks Compose's clip/background/gradient helpers only while one of Infinity X's
     * two custom vertical slider composables is synchronously composing.
     */
    private static void installComposeInfrastructure(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        try {
            Class<?> borderKt = XposedHelpers.findClass(
                    "androidx.compose.foundation.BorderKt", cl);
            for (Method m : borderKt.getDeclaredMethods()) {
                if (m.getName().equals("border-xT4_qwU")
                        && m.getParameterTypes().length == 4) {
                    m.setAccessible(true);
                    borderColorMethod = m;
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.13: border helper unavailable: " + t);
        }

        try {
            Class<?> solid = XposedHelpers.findClass(
                    "androidx.compose.ui.graphics.SolidColor", cl);
            solidColorCtor = solid.getDeclaredConstructor(long.class);
            solidColorCtor.setAccessible(true);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.13: SolidColor unavailable: " + t);
        }

        // Capture the most recent RoundedCornerShape before the outer background is drawn.
        try {
            Class<?> clipKt = XposedHelpers.findClass(
                    "androidx.compose.ui.draw.ClipKt", cl);
            for (Method m : clipKt.getDeclaredMethods()) {
                if (!m.getName().equals("clip") || m.getParameterTypes().length != 2) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mode() == MODE_NONE) return;
                        if (param.args != null && param.args.length >= 2 && param.args[1] != null) {
                            LAST_CLIP_SHAPE.set(param.args[1]);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.13: clip capture unavailable: " + t);
        }

        // Lower alpha of native Infinity X background colors, while preserving their hue.
        try {
            Class<?> backgroundKt = XposedHelpers.findClass(
                    "androidx.compose.foundation.BackgroundKt", cl);
            for (final Method m : backgroundKt.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();

                if (m.getName().startsWith("background-")
                        && p.length == 3
                        && p[1] == long.class) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int currentMode = mode();
                            if (currentMode == MODE_NONE) return;
                            int index = nextBackgroundIndex();
                            LAST_BG_INDEX.set(index);

                            long nativeColor = ((Number) param.args[1]).longValue();
                            long tone = usableSrgb(nativeColor)
                                    ? nativeColor : adaptiveTone();
                            if (usableSrgb(nativeColor)) ADAPTIVE_TONE.set(nativeColor);

                            int alpha;
                            if (index == 0) {
                                alpha = currentMode == MODE_BRIGHTNESS ? 17 : 19;
                            } else {
                                alpha = currentMode == MODE_BRIGHTNESS ? 44 : 50;
                            }
                            param.args[1] = Long.valueOf(withAlpha(tone, alpha));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (mode() == MODE_NONE) return;
                            Integer index = LAST_BG_INDEX.get();
                            if (index == null || index.intValue() != 0) return;
                            Object result = param.getResult();
                            Object shape = LAST_CLIP_SHAPE.get();
                            if (shape == null && param.args != null && param.args.length >= 3) {
                                shape = param.args[2];
                            }
                            Object rimmed = addOpticalRim(result, shape, adaptiveTone());
                            if (rimmed != null) param.setResult(rimmed);
                            logSliderActive(mode());
                        }
                    });
                }

                // The active volume/brightness segment can use a Brush. Replace it with a
                // very light adaptive solid brush so it does not become a white/matte block.
                if (m.getName().equals("background$default")
                        && p.length >= 4
                        && "androidx.compose.ui.graphics.Brush".equals(p[1].getName())) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int currentMode = mode();
                            if (currentMode == MODE_NONE || solidColorCtor == null) return;
                            int index = nextBackgroundIndex();
                            LAST_BG_INDEX.set(index);
                            long tone = adaptiveTone();
                            int alpha = currentMode == MODE_BRIGHTNESS ? 42 : 48;
                            try {
                                param.args[1] = solidColorCtor.newInstance(
                                        Long.valueOf(withAlpha(tone, alpha)));
                            } catch (Throwable ignored) {}
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (mode() == MODE_NONE) return;
                            Integer index = LAST_BG_INDEX.get();
                            if (index == null || index.intValue() != 0) return;
                            Object shape = LAST_CLIP_SHAPE.get();
                            if (shape == null && param.args != null && param.args.length >= 3) {
                                shape = param.args[2];
                            }
                            Object rimmed = addOpticalRim(
                                    param.getResult(), shape, adaptiveTone());
                            if (rimmed != null) param.setResult(rimmed);
                            logSliderActive(mode());
                        }
                    });
                }
            }
            XposedBridge.log(TAG + " v0.13: Compose glass background hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.13: background hooks unavailable: " + t);
        }

        // Infinity X builds its active slider fill from a vertical gradient. While the
        // custom slider is composing, convert that to a translucent adaptive brush.
        try {
            final Class<?> brushCompanion = XposedHelpers.findClass(
                    "androidx.compose.ui.graphics.Brush$Companion", cl);
            for (final Method m : brushCompanion.getDeclaredMethods()) {
                if (!m.getName().startsWith("verticalGradient")) continue;
                if (m.getParameterTypes().length < 1
                        || !List.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int currentMode = mode();
                        if (currentMode == MODE_NONE || solidColorCtor == null) return;
                        try {
                            long tone = toneFromColorList(param.args[0]);
                            if (usableSrgb(tone)) ADAPTIVE_TONE.set(tone);
                            else tone = adaptiveTone();
                            int alpha = currentMode == MODE_BRIGHTNESS ? 52 : 48;
                            param.setResult(solidColorCtor.newInstance(
                                    Long.valueOf(withAlpha(tone, alpha))));
                            if (FIRST_GRADIENT.compareAndSet(false, true)) {
                                XposedBridge.log(TAG + " v0.13: ADAPTIVE_SLIDER_FILL_ACTIVE; tone="
                                        + hexColor(tone));
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " v0.13: gradient frame failed: " + t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.13: gradient hook unavailable: " + t);
        }
    }

    private static void installInfinitySliderScopes(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookSliderComposable(
                lpparam,
                "com.android.systemui.qs.panels.ui.compose.InfinityBrightnessModuleKt",
                "InfinityBrightnessModule",
                MODE_BRIGHTNESS);
        hookSliderComposable(
                lpparam,
                "com.android.systemui.qs.panels.ui.compose.InfinityVolumeModuleKt",
                "InfinityVolumeModule",
                MODE_VOLUME);
    }

    private static void hookSliderComposable(
            final XC_LoadPackage.LoadPackageParam lpparam,
            String className,
            String methodName,
            final int targetMode) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, lpparam.classLoader);
            int hooked = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        MODULE_MODE.set(targetMode);
                        BG_COUNT.set(Integer.valueOf(0));
                        LAST_BG_INDEX.remove();
                        LAST_CLIP_SHAPE.remove();
                        ADAPTIVE_TONE.set(COLOR_WARM_FALLBACK);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        MODULE_MODE.remove();
                        BG_COUNT.remove();
                        LAST_BG_INDEX.remove();
                        LAST_CLIP_SHAPE.remove();
                        ADAPTIVE_TONE.remove();
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " v0.13: slider scope " + methodName + " hooks=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.13: slider scope unavailable for " + methodName + ": " + t);
        }
    }

    /** Media card: keep the original adaptive hue, but turn the card into low-fill glass. */
    private static void installMediaGlass(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> illumination = XposedHelpers.findClass(
                    "com.android.systemui.media.controls.ui.drawable.IlluminationDrawable",
                    lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    illumination,
                    "draw",
                    Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (shadeExpansion <= 0.025f) return;
                            try {
                                Paint paint = (Paint) XposedHelpers.getObjectField(
                                        param.thisObject, "paint");
                                if (paint == null) return;
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "ig13_old_color", paint.getColor());
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "ig13_old_alpha", paint.getAlpha());

                                int nativeColor = paint.getColor();
                                paint.setColor(nativeColor);
                                paint.setAlpha(20 + Math.round(10f * shadeExpansion));
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.13: media pre-draw failed: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (shadeExpansion <= 0.025f) return;
                            try {
                                Canvas canvas = (Canvas) param.args[0];
                                Drawable drawable = (Drawable) param.thisObject;
                                Rect b = drawable.getBounds();
                                if (canvas != null && b != null && b.width() > 0 && b.height() > 0) {
                                    float radius = readCornerRadius(param.thisObject);
                                    Object oldColorObj = XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "ig13_old_color");
                                    int oldColor = oldColorObj instanceof Integer
                                            ? ((Integer) oldColorObj).intValue() : Color.WHITE;

                                    // Very faint hue-matched halo.
                                    Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
                                    halo.setStyle(Paint.Style.STROKE);
                                    halo.setStrokeWidth(2.0f);
                                    halo.setColor(Color.argb(
                                            30,
                                            Color.red(oldColor),
                                            Color.green(oldColor),
                                            Color.blue(oldColor)));
                                    float hInset = 1.0f;
                                    canvas.drawRoundRect(
                                            b.left + hInset,
                                            b.top + hInset,
                                            b.right - hInset,
                                            b.bottom - hInset,
                                            Math.max(0f, radius - hInset),
                                            Math.max(0f, radius - hInset),
                                            halo);

                                    // Crisp neutral optical edge, matching the QS plate borders.
                                    Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
                                    rim.setStyle(Paint.Style.STROKE);
                                    rim.setStrokeWidth(1.25f);
                                    rim.setColor(Color.argb(104, 255, 255, 255));
                                    float rInset = 2.0f;
                                    canvas.drawRoundRect(
                                            b.left + rInset,
                                            b.top + rInset,
                                            b.right - rInset,
                                            b.bottom - rInset,
                                            Math.max(0f, radius - rInset),
                                            Math.max(0f, radius - rInset),
                                            rim);

                                    if (FIRST_MEDIA.compareAndSet(false, true)) {
                                        XposedBridge.log(TAG + " v0.13: MEDIA_PLATE_GLASS_ACTIVE; alpha="
                                                + (20 + Math.round(10f * shadeExpansion))
                                                + " radius=" + radius);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.13: media post-draw failed: " + t);
                            } finally {
                                try {
                                    Paint paint = (Paint) XposedHelpers.getObjectField(
                                            param.thisObject, "paint");
                                    Object oldColor = XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "ig13_old_color");
                                    Object oldAlpha = XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "ig13_old_alpha");
                                    if (paint != null && oldColor instanceof Integer) {
                                        paint.setColor(((Integer) oldColor).intValue());
                                    }
                                    if (paint != null && oldAlpha instanceof Integer) {
                                        paint.setAlpha(((Integer) oldAlpha).intValue());
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    });

            XposedBridge.log(TAG + " v0.13: media plate hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.13: media plate unavailable: " + t);
        }
    }

    private static Object addOpticalRim(Object modifier, Object shape, long tone) {
        if (modifier == null || shape == null || borderColorMethod == null) return modifier;
        try {
            Object glow = borderColorMethod.invoke(
                    null,
                    modifier,
                    Float.valueOf(1.10f),
                    Long.valueOf(withAlpha(tone, 22)),
                    shape);
            Object crisp = borderColorMethod.invoke(
                    null,
                    glow != null ? glow : modifier,
                    Float.valueOf(0.62f),
                    Long.valueOf(withAlpha(COLOR_WHITE, 104)),
                    shape);
            return crisp != null ? crisp : modifier;
        } catch (Throwable ignored) {
            return modifier;
        }
    }

    private static void logSliderActive(int currentMode) {
        if (currentMode == MODE_BRIGHTNESS
                && FIRST_BRIGHTNESS.compareAndSet(false, true)) {
            XposedBridge.log(TAG + " v0.13: BRIGHTNESS_PLATE_GLASS_ACTIVE; lowFill=true border=true");
        } else if (currentMode == MODE_VOLUME
                && FIRST_VOLUME.compareAndSet(false, true)) {
            XposedBridge.log(TAG + " v0.13: VOLUME_PLATE_GLASS_ACTIVE; lowFill=true border=true");
        }
    }

    private static int mode() {
        Integer value = MODULE_MODE.get();
        return value == null ? MODE_NONE : value.intValue();
    }

    private static int nextBackgroundIndex() {
        Integer value = BG_COUNT.get();
        int index = value == null ? 0 : value.intValue();
        BG_COUNT.set(Integer.valueOf(index + 1));
        return index;
    }

    private static long adaptiveTone() {
        Long value = ADAPTIVE_TONE.get();
        return value != null && usableSrgb(value.longValue())
                ? value.longValue() : COLOR_WARM_FALLBACK;
    }

    private static long toneFromColorList(Object value) {
        try {
            if (!(value instanceof List)) return COLOR_WARM_FALLBACK;
            List<?> list = (List<?>) value;
            for (Object item : list) {
                if (item == null) continue;
                try {
                    long color = XposedHelpers.getLongField(item, "value");
                    if (usableSrgb(color)) return color;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return COLOR_WARM_FALLBACK;
    }

    private static boolean usableSrgb(long color) {
        if ((color & 0xFFFFFFFFL) != 0L) return false;
        int argb = (int) (color >>> 32);
        return ((argb >>> 24) & 0xFF) > 8;
    }

    private static long packSrgb(int argb) {
        return (Integer.toUnsignedLong(argb) << 32);
    }

    private static long withAlpha(long color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        if (usableSrgb(color)) {
            int argb = (int) (color >>> 32);
            return packSrgb((a << 24) | (argb & 0x00FFFFFF));
        }
        int fallback = (int) (COLOR_WARM_FALLBACK >>> 32);
        return packSrgb((a << 24) | (fallback & 0x00FFFFFF));
    }

    private static String hexColor(long color) {
        if (!usableSrgb(color)) return "packed-wide";
        return String.format("#%08X", (int) (color >>> 32));
    }

    private static float safeFloatField(Object obj, String name) {
        try {
            return XposedHelpers.getFloatField(obj, name);
        } catch (Throwable ignored) {
            try {
                Object value = XposedHelpers.getObjectField(obj, name);
                return value instanceof Number ? ((Number) value).floatValue() : 0f;
            } catch (Throwable ignoredAgain) {
                return 0f;
            }
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
