# Motorola Pixel Camera (Edge 20 Fusion Edition)

Custom computational photography camera application inspired by the Google Pixel Camera experience, engineered specifically for the **Motorola Edge 20 Fusion** (`kyoto` / MediaTek Dimensity 800U).

---

## 📱 Hardware Targeting

* **Device:** Motorola Edge 20 Fusion (`kyoto`)
* **Processor:** MediaTek Dimensity 800U (`MT6853`)
* **Rear Main Camera:** 108 MP Samsung ISOCELL HM2 (`s5khm2`), 9-in-1 Nona-binning to 12 MP (`4000x3000`)
* **Rear Ultrawide:** 8 MP Samsung ISOCELL 4H7 (`s5k4h7`), $f/2.2$
* **Front Camera:** 32 MP OmniVision OV32B40 (`ov32b40`), 4-in-1 Quad-binning to 8 MP
* **Depth Sensor:** 2 MP OmniVision OV02B1B (`ov02b1b`)

---

## ✨ Features & Architecture

### 1. Pixel 9 Material You Interface
* Obsidian dark viewfinder layout with rounded controls.
* Quick-zoom floating selector pills: **`.6x`** (Ultrawide), **`1x`** (108MP HM2 Wide), and **`2x`** (Super-Resolution crop).
* Tap-to-focus with animated yellow Google Pixel reticle and exposure lock.
* Swipeable mode carousel:
  * **Camera (HDR+)**
  * **Night Sight**
  * **Portrait**
  * **Video (4K EIS)**
  * **Pro Manual**

### 2. Computational Photography Modules
* **HDR+ Fusion (`HdrProcessor.kt`):** Sub-pixel frame alignment, de-ghosting, luminance-weighted exposure blending, and Reinhard tone-mapping to eliminate stock watercolor noise smoothing.
* **Night Sight (`NightSightEngine.kt`):** Multi-frame low-light SNR stacking with non-linear shadow expansion.
* **Portrait Bokeh (`PortraitEngine.kt`):** Progressive optical circle-of-confusion depth simulation.
* **Super-Resolution Zoom (`SuperResolutionEngine.kt`):** High-frequency detail recovery from the 108 MP sensor core.
* **Astrophotography Stacking (`AstrophotographyEngine.kt`):** Star-drift alignment compensating for Earth's rotation during multi-second exposures.
* **Pro Manual Controls & Live RGB Histogram (`HistogramView.kt`):** Live multi-channel RGB waveform histogram with manual ISO (100–19200) and EV sliders.
* **RAW DNG Capture (`DngSaver.kt`):** Direct 10-bit Bayer `.dng` capture alongside computational JPEG.
* **108 MP Remosaic Hook (`MotorolaVendorHelper.kt`):** Vendor extension scenario hook (`com.lenovo.moto.remosaic.sensor.scenarioid`).

---

## 🛠️ Building & Installing

### Prerequisites
* JDK 17
* Android SDK (API 34, Build Tools 34.0.0)

### Build Debug APK:
```bash
./gradlew assembleDebug
```

### Install via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License
Open source under the Apache 2.0 License.
