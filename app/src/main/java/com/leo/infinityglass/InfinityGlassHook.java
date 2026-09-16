package com.leo.infinityglass;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Infinity Glass Vector v0.2
 * Target: Infinity X / Android 16 / POCO X7 Pro (rodin)
 *
 * Infinity X clamps its normal shade blur to BlurUtils.maxBlurRadius before
 * applyBlur(). This hook runs AFTER computeBlurAndZoomOut(), so it can request
 * a larger real SurfaceFlinger background blur without replacing SystemUI.apk.
 */
public final class InfinityGlassHook implements IXposedHookLoadPackage {
    private static final String TAG = "InfinityGlass";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final AtomicBoolean FIRST_FORCED_FRAME = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " v0.2: loading in " + lpparam.packageName);

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

                                // 1.25x while opening -> 2.10x at fully expanded QS.
                                // This deliberately bypasses Infinity X's internal max-radius clamp.
                                float multiplier = 1.25f + (0.85f * smoothStep(expansion));
                                int desiredBlur = Math.round(baseMaxBlur * multiplier);
                                int forcedBlur = Math.max(oldBlur, desiredBlur);

                                // Keep the wallpaper stationary instead of pushing it backwards.
                                float forcedZoom = 0f;

                                Object newPair = oldPair.getClass()
                                        .getConstructor(Object.class, Object.class)
                                        .newInstance(Integer.valueOf(forcedBlur), Float.valueOf(forcedZoom));
                                param.setResult(newPair);

                                if (FIRST_FORCED_FRAME.compareAndSet(false, true)) {
                                    XposedBridge.log(TAG + " v0.2: ACTIVE; shade=" + shade
                                            + " qs=" + qs
                                            + " oldBlur=" + oldBlur
                                            + " baseMax=" + baseMaxBlur
                                            + " forcedBlur=" + forcedBlur
                                            + " oldZoom=" + oldZoom);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " v0.2: frame hook failed: " + t);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + " v0.2: depth-controller hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " v0.2: unable to install hook: " + t);
        }
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
