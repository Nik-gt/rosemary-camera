# rosemary-camera

Original base project for **Poco M5S** (M2101K7AG, "rosemary", MediaTek Helio G99).

## Why this fork?

Standard Android Camera2 4K30 on Poco M5S with MIUI 14 produces stuttered/laggy video —
the HAL drops frames or the EIS buffer overflows.

## The hack: `SCALER_CROP_REGION`

```kotlin
val crop = Rect(0, 434, 4624, 3038)
requestBuilder.set(CaptureRequest.SCALER_CROP_REGION, crop)
```

This crops the sensor output to **4624×3038** (exactly the 4K EIS viewport of the OV64B sensor
used in rosemary). The MTK HAL's EIS engine works on this cropped region and produces
smooth 4K30 video — no dropped frames, no macroblocking.

**Key constants:**

| Constant | Value | Purpose |
|----------|-------|---------|
| `SCALER_CROP_REGION` | `Rect(0, 434, 4624, 3038)` | 4K viewport for stable EIS |
| `SessionConfiguration` | `0x8004` | MTK-specific session config |
| `CONTROL_AE_TARGET_FPS_RANGE` | `Range(30, 30)` | Locks at 30fps, prevents HAL dropping to 20 |
| `setCaptureRate(15.0)` | extra buffers | Night/Ultra modes (not frame rate) |

## Structure

```
app/src/main/java/com/camera/eisenabler/
  MainActivity.kt          — camera preview + recording logic
  BackgroundRecordService.kt — foreground service (WakeLock, notification)
  CameraWidgetProvider.kt   — home-screen widget (1080p, 4K)

app/src/main/res/layout/
  activity_main.xml         — 8 mode buttons + zoom + record
  widget_layout.xml         — home widget
```

## Modes (all from one session)

| # | Mode | Crop | Notes |
|---|------|------|-------|
| 1 | 4K | 4624×3038 | Default, EIS enabled |
| 2 | Night | 4624×3038 | + ColorSpaceTransform (green-only matrix) |
| 3 | Night1.5x | center-crop of 4K | 1.5× zoom via center-crop |
| 4 | Night4K | 4624×3038 | Same crop, green matrix |
| 5 | NightColor | 4624×3038 | Color (no matrix), night vendor tags |
| 6 | 1080p | 3464×2384 | Center-crop of 4K → 1080p |
| 7 | Zoom1.5x | center-crop of 4K | 1.5× zoom only |
| 8 | Zoom2.0x | center-crop of 4K | 2.0× zoom only |

## Build on another PC

1. Copy project
2. Edit `local.properties`: `sdk.dir=D:\Program\Java\Android SDK` (or your path)
3. `gradle assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

**Branches:**
- `master` — source code only (no APK)
- `releases` — signed APK (debug-keystore signed, for testing)

## License

No license file — free to use, modify, fork.
