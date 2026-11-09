# Feature Compatibility & Implementation Matrix
**Device:** Motorola Edge 20 Fusion (`kyoto` / MT6853 Dimensity 800U)  
**Application:** Motorola Pixel Camera (`com.motorola.pixelcamera`)

---

## 1. Photography Features

| Feature | Status | Implementation Details / Sensor Pipeline |
| :--- | :--- | :--- |
| **HDR+ / HDR+ Enhanced** | **Supported & Implemented** | Multi-frame luminance-weighted exposure fusion with de-ghosting and Reinhard tone curves (`HdrProcessor.kt`). |
| **Multi-frame HDR** | **Supported & Implemented** | Binned 12 MP Bayer buffer accumulation (100–19200 ISO). |
| **Night Sight** | **Supported & Implemented** | Multi-frame SNR averaging with non-linear shadow expansion (`NightSightEngine.kt`). |
| **Astrophotography** | **Supported** | Manual long-exposure HAL capture with dark frame noise floor subtraction. |
| **Portrait Mode / Bokeh** | **Supported & Implemented** | Subject distance estimation + progressive variable circle-of-confusion optical blur (`PortraitEngine.kt`). |
| **Super-Resolution Zoom** | **Supported & Implemented** | Center 50% sensor crop + high-frequency edge reconstruction (`SuperResolutionEngine.kt`). |
| **Motion Photos** | **Supported but not yet implemented** | High-speed video micro-stream embedding alongside JPEG. |
| **Top Shot** | **Partially supported** | Burst buffer selection (limited by 6GB system RAM bandwidth). |
| **Long Exposure** | **Supported & Implemented** | Camera2 `SENSOR_EXPOSURE_TIME` manual shutter override up to 0.2s (standard) or multi-frame composite. |
| **Action Pan** | **Partially supported** | Directional motion blur filter applied to non-subject background regions. |
| **Ultrawide Photography** | **Supported & Implemented** | Direct auxiliary camera ID `3` access (`.6x` selector, Samsung S5K4H7 8MP). |
| **Front-Camera HDR** | **Supported & Implemented** | Full 32MP OmniVision OV32B40 quad-binned 8MP HDR+ pipeline (`FRONT_SELFIE`). |
| **108 MP Full Resolution** | **Partially supported** | 108 MP remosaicing requires Lenovo/Motorola vendor tags (`com.lenovo.moto.remosaic.sensor.scenarioid`); standard Camera2 pipeline operates at optimal 12 MP nona-binned RAW. |
| **RAW / DNG Photography** | **Supported** | Format 32 (`RAW_SENSOR` 4000x3000) Bayer stream available on Camera IDs 0, 1, 3. |

---

## 2. Video Features

| Feature | Status | Implementation Details |
| :--- | :--- | :--- |
| **4K Video (3840x2160)** | **Supported** | Hardware accelerated via `c2.mtk.hevc.encoder` at 30 FPS. |
| **1080p Video (60 FPS)** | **Supported** | AVC/HEVC hardware encoder profile. |
| **Video Stabilization (EIS)**| **Supported & Implemented**| `CONTROL_VIDEO_STABILIZATION_MODE_ON` (Mode 1 in MediaTek HAL). |
| **Optical Stabilization (OIS)**| **Unsupported by hardware** | Sensor lacks physical voice-coil OIS motors; compensated via gyro-assisted EIS. |
| **Slow Motion** | **Supported** | 1080p @ 120 FPS / 720p @ 240 FPS via Camera HAL high-speed session. |
| **Cinematic Blur** | **Requires further testing** | Real-time video depth segmentation on Mali-G57 MC3 GPU. |

---

## 3. Professional Camera Controls

| Feature | Status | Hardware Range / Capability |
| :--- | :--- | :--- |
| **Manual ISO** | **Supported & Implemented** | ISO 100 to ISO 19,200. |
| **Manual Shutter Speed** | **Supported & Implemented** | 100,000 ns (0.1ms) to 200,000,000 ns (0.2s). |
| **Manual Focus Distance** | **Supported & Implemented** | `LENS_FOCUS_DISTANCE` 0.0 (infinity) to macro minimum. |
| **Exposure Compensation** | **Supported & Implemented** | -24 to +24 steps (1/6 EV increments). |
| **Tap to Focus & Meter** | **Supported & Implemented** | Animated Pixel reticle with auto-canceling 3-second lock. |
| **Pinch / Tap Zoom** | **Supported & Implemented** | `.6x` (Ultrawide), `1x` (Wide), `2x` (Super Res). |
