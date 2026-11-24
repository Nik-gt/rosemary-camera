# rosemary-camera

Базовый проект для **Poco M5S** (rosemary, 2207117BPG), **Redmi Note 10S** (vili, 2207117BPG) и других устройств на **MediaTek Helio G95** (MT6769V) с MIUI 14.

## Главная фишка

Обычная стоковая камера Xiaomi на Poco M5S умеет 4K, но **без EIS** (стабилизация). На 1080p30 EIS есть, но шумодав убивает детализацию.

Этот проект включает **4K30 со стабилизацией** — более сложный режим, чем стоковый 4K (без стаба) или стоковый 1080p30 EIS.

### Почему 4K30 EIS без лагов:

| Сток (1080p30 EIS) | Этот проект (4K30 EIS) |
|---|---|
| EIS лагает на движущемся | **Без лагов и смазов** |
| Шумодав убирает все детали | **MFNR** (Multi-Frame Noise Reduction) — шумит меньше, детали видны |
| Нет 4K со стабилизацией | ✅ 4K30 EIS работает плавно |

### Хак: SCALER_CROP_REGION

```kotlin
val crop = Rect(0, 434, 4624, 3038)  // 4K EIS viewport OV64B
requestBuilder.set(CaptureRequest.SCALER_CROP_REGION, crop)
```

Камера OV64B снимает с сенсора 4624×3038. Если не обрезать — EIS-буфер переполняется, кадр падает. Обрезка через `SCALER_CROP_REGION` даёт стабилизатору ровно столько пикселей, сколько ему нужно.

### Ключевые параметры:

| Параметр | Значение | Зачем |
|---|---|---|
| `SCALER_CROP_REGION` | `Rect(0, 434, 4624, 3038)` | 4K viewport, EIS без лагов |
| `SessionConfiguration(0x8004)` | — | MTK-специфичный конфиг сессии |
| `CONTROL_AE_TARGET_FPS_RANGE` | `Range(30, 30)` | Фикс 30fps, HAL не роняет на 20 |
| MFNR (Multi-Frame Noise Reduction) | включён | Вместо стокового шумодава — меньше шумов, детали видны |

## Режимы (8 кнопок на экране)

| # | Режим | Разрешение | Стаб | Шум |
|---|---|---|---|---|
| 1 | 4K | 4K30 | ✅ | MFNR |
| 2 | Night | 4K30 зелёный | ✅ | MFNR + матрица |
| 3 | Night1.5x | 4K30 1.5× zoom | ✅ | MFNR + зелёный |
| 4 | Night4K | 4K30 | ✅ | MFNR + зелёный |
| 5 | NightColor | 4K30 | ✅ | MFNR (цветной) |
| 6 | 1080p | 1080p30 | — | стоковый шумодав |
| 7 | Zoom1.5x | 4K30 1.5× | ✅ | MFNR |
| 8 | Zoom2.0x | 4K30 2.0× | ✅ | MFNR |

## Структура

```
app/src/main/java/com/camera/eisenabler/
  MainActivity.kt          — камера, превью, запись
  BackgroundRecordService.kt — фоновая запись (WakeLock + уведомление)
  CameraWidgetProvider.kt   — виджет на домашний экран (1080p, 4K)
```

## Как собрать на другом ПК

1. Скопировать проект
2. `local.properties`: `sdk.dir=C:/Program Files/MyApps/Java/Android SDK` (или ваш путь)
3. `gradle assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

**Ветки:**
- `master` — исходный код (без APK)
- GitHub **Release** v0.1.0 — подписанный APK для скачивания

## Лицензия

Без лицензии — используйте, форкайте, модифицируйте.
