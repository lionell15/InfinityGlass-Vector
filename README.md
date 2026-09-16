# Infinity Glass Vector v0.1

Targeted to Infinity X SystemUI on POCO X7 Pro (rodin), Android 16.

## What it hooks

`com.android.systemui.statusbar.NotificationShadeDepthController.computeBlurAndZoomOut()`

Infinity X already uses this method to calculate a real SurfaceFlinger background-blur radius. The module raises the requested blur while the shade / Quick Settings are open, while leaving the closed state alone.

It also caps the wallpaper push-back/zoom effect, so the result should look more like a stationary frosted pane.

## Requirements

- Infinity Glass KSU v0.3 should remain enabled.
- Vector 2.2 / LSPosed-compatible framework.
- Scope ONLY: `com.android.systemui`

Do not scope the module to Android Framework / system_server.

## First test

1. Install the APK.
2. Vector > Modules > Infinity Glass Vector.
3. Enable it.
4. Scope only `UI del sistema / com.android.systemui`.
5. Reboot.
6. Open Quick Settings.

If SystemUI behaves incorrectly, disable the module in Vector and reboot.

## Build

GitHub Actions compiles `InfinityGlass-Vector-v0.1.apk` and uploads it as an artifact.
