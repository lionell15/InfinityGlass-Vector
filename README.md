# Infinity Glass Vector v0.3

Targeted to Infinity X SystemUI on POCO X7 Pro (`rodin`), Android 16.

## v0.3

The module now combines two layers:

1. **Real SurfaceFlinger background blur** by hooking `NotificationShadeDepthController.computeBlurAndZoomOut()`.
2. **iOS 26-inspired glass plates** by hooking Infinity X's Compose `TileDefaults.getColorForState()` and replacing Quick Settings tile colors with low-alpha, multi-stop diagonal specular gradients.

The glass preset keeps text/icons bright, uses stronger highlights on active controls, softer glass on inactive controls, and a separate raised-glass gradient for dual-target icon inserts.

## Requirements

- Keep Infinity Glass KSU v0.3 enabled.
- Vector 2.2 / LSPosed-compatible framework.
- Scope ONLY: `com.android.systemui`.

Do not scope the module to Android Framework / `system_server`.

## Install

1. Install `InfinityGlass-Vector-v0.3.apk` over v0.2.
2. Vector > Modules > Infinity Glass Vector.
3. Enable it.
4. Scope only `UI del sistema / com.android.systemui`.
5. Reboot.
6. Open Quick Settings and check Vector logs for:
   - `InfinityGlass v0.3: BLUR_ACTIVE`
   - `InfinityGlass v0.3: TILE_GLASS_ACTIVE`

If SystemUI behaves incorrectly, disable the module in Vector and reboot. All v0.3 hooks are fail-soft and the module does not replace `SystemUI.apk`.

## Build

GitHub Actions compiles `InfinityGlass-Vector-v0.3.apk` and uploads it as an artifact.
