# Night Sight & Image Clarity — Worklog, Findings and Next Ideas

Last updated: 2026-11-10. Device: Motorola Edge 20 Fusion (`kyoto`, MT6853, Android 13).
Everything below was measured on the real phone (logcat, EXIF, test photos) unless marked as an idea.

---

## 1. Done (committed & pushed)

| Commit | Change | Evidence |
|---|---|---|
| `137a602` | **Night Sight frame alignment.** New `computational/BurstAligner.kt`: coarse-to-fine global translation per frame (luma pyramid, SAD search), sharpest frame as reference, noise-adaptive 4×4-block weights that fall back to the reference where frames disagree (moving subjects, rotation). Streams one frame at a time (heap). JUnit tests in `app/src/test/.../BurstAlignerTest.kt`. | Old Night Sight photo showed 3–4 ghost copies of text, up to ~50 px apart. Frames are ~2.5 s apart and were averaged unaligned. |
| `86669b6` | **Handheld Night Sight takes 2 captures instead of 4** (`NIGHT_HANDHELD_CAPTURES`). Astro (phone still ≥2.5 s) unchanged at 8. | Each capture already runs HAL MFNR (see §2), ~2.5 s each. 4 captures = ~10 s of holding still. |
| `a51d551` | **Night tone curve as 256-entry LUT** (was 3× `Math.pow` per pixel). Adds stage timing logs. `NightToneTest` checks LUT == original formula for all 256 values. | Tone step 14.9 s → 0.27 s. |

Night Sight timing now (debug build, 12 MP, 2 frames): capture+decode ~2.5 s/frame, sharpness 0.4 s, merge 5.2 s, tone 0.3 s, save 0.9 s. Tap → saved ≈ 12 s (was ≈ 35 s).

Read the timings live:
```bash
adb logcat -v time -s NightSightEngine:I CameraManager:I
```

---

## 2. Hardware / HAL facts (from `dumpsys media.camera` + logcat)

- Main camera (id 0): **no OIS** (`availableOpticalStabilization [0]`), exposure 0.1 ms–200 ms, ISO 100–19200.
- **JPEG max is 4000×3000.** No 108 MP stream is exposed, so the "108MP Ultra" toggle still saves 12 MP.
- Capabilities: RAW, MANUAL_SENSOR, BURST_CAPTURE, PRIVATE/YUV_REPROCESSING.
- App heap: `largeHeap=true` → 512 MB (`dalvik.vm.heapsize`). A 12 MP `IntArray` is 48 MB.
- **HAL MFNR:** with `sceneMode=NIGHT`, every still capture triggers `MTK_FEATURE_MFNR`: 6 frames, AIS anti-shake, BSS best-shot selection, `mfll_iso_th:200`. About 1.4 s inside the HAL.
- AE target FPS range is `5,30` in both our app and the stock Moto camera, so exposure policy is identical.
- Useful MediaTek vendor tags (from `dumpsys media.camera` → "Vendor tags"):
  - `com.mediatek.mfnrfeature.mfbmode` (int32; available 0 off / 1 MFNR / 2 AIS / 255 auto)
  - `com.mediatek.mfnrfeature.aismode` (int32; 0/1)
  - `com.mediatek.control.capture.remosaicenable` (int32). This is a real MTK remosaic key, different from the Lenovo `scenarioid` key the app uses now.
  - `com.mediatek.control.capture.zsl.mode` (byte), `com.mediatek.hdrfeature.hdrMode` (int32)
- Logcat overflows within seconds during a capture (thousands of HAL lines). Stream filtered tags to a file instead of `logcat -d`.

---

## 3. Open problem: Photo mode gets NO hardware multi-frame noise reduction

**Finding:** Photo-mode captures in our app are single-frame:
`FeatureSettingPolicy: trigger feature:NO_FEATURE_NORMAL ... COMBINATION_SINGLE`.
In the same scene the stock Moto camera gets `MTK_FEATURE_MFNR` with 6 frames, taken from a **zero-shutter-lag history buffer** (`ZslProc ... MultiframeSelector`). Result: our low-light Photo-mode shots are noisier and softer than stock.

The only visible request difference is `control.enableZsl:1` (stock) vs `0` (ours). The HAL log line is truncated, so vendor keys aren't visible.

**Tried, did not work (all reverted, not in git):**
1. `CaptureRequest.CONTROL_ENABLE_ZSL = true` on the ImageCapture request (Camera2Interop) → still single frame.
2. Same, plus ZSL on the Preview repeating request → still single frame.
3. `com.mediatek.mfnrfeature.mfbmode = 255` on ImageCapture → still single frame. `MFNRPlugin negotiate` runs but returns before `MfllCore queryStrategy`.

Snippet used for (3), for reference:
```kotlin
val mfbKey = CaptureRequest.Key<Int>("com.mediatek.mfnrfeature.mfbmode", Int::class.javaPrimitiveType ?: Int::class.java)
camera2Extender.setCaptureRequestOption(mfbKey, 255)
```

**Ideas to try next (in this order):**
1. Compare the **stream configuration** (not the requests). While the stock camera is open, run `adb shell dumpsys media.camera` and compare the configured streams and session parameters with ours. CameraX binds Preview + ImageCapture + ImageAnalysis (YUV). The stock app may also use a PRIVATE/ZSL stream or session keys.
2. Try ZSL + `mfbmode=255` **together** (only tested separately so far).
3. Check if the Lenovo remosaic key (`com.lenovo.moto.remosaic.sensor.scenarioid=1`, set when "108MP" is on, which it is on the phone) blocks MFNR in Photo mode. Turn 108MP off and capture in dim light. Night mode still got MFNR with it set, so this is lower probability.
4. Session-level keys: CameraX `Camera2Interop` can't set session parameters directly in 1.3. You may need a small Camera2 capture path for the still shot.
5. Fallback if HAL MFNR can't be enabled: reuse `BurstAligner` and take N fast frames in Photo mode (e.g. ImageCapture `CAPTURE_MODE_MINIMIZE_LATENCY` or YUV ImageAnalysis frames), then align and merge in software.

---

## 4. Other bugs found in the first analysis (not fixed yet)

- **HDR+ (Photo mode, when 108MP is off) still merges frames unaligned**: `HdrProcessor.fuseBurstFrames`. Same ghosting bug Night Sight had. Fix: use `BurstAligner.alignAndMerge` before tone mapping. The frames are all the same exposure, so the "well-exposedness" weights add nothing.
- **Long Exposure (12 frames) and Astro (8 frames)** keep every 12 MP bitmap plus pixel arrays in memory, which is a likely OOM. They also merge unaligned (Astro has its own star alignment). Stream frames as Night Sight does now.
- **"108MP Ultra" saves 12 MP** (HAL max 4000×3000). Either label it honestly or try `com.mediatek.control.capture.remosaicenable` plus a full-size RAW/YUV stream.
- **RAW toggle does nothing**: `isRawEnabled` is never used in capture. `DngSaver` writes raw bytes without a DNG header. Use `DngCreator` with a `RAW_SENSOR` ImageReader. The camera supports RAW.
- `SuperResolutionEngine` and `MotorolaVendorHelper.apply108MpRemosaic` are never called.
- The software pipeline re-encodes to JPEG and drops exposure/ISO EXIF. Copy the EXIF from the HAL JPEG.
- `MainActivity.kt` is 2,400 lines, and the 13 empty module folders at the repo root are leftover scaffolding.
- Merge speed: 5 s on the phone vs 0.2–0.5 s on a desktop JVM for the same 12 MP. Partly the debug build. Ideas: precompute quantized block weights, reuse buffers, test a release build, or move it to the GPU (Vulkan/GLES compute) or NEON via the NDK.

## 5. Image clarity ideas (not yet tested)

- Capture-moment shake: pressing the on-screen shutter shakes the phone. A 2 s timer or the volume-key shutter (already implemented) lets you check how much that matters.
- Compare the same scene, handheld, stock vs app, in daylight and indoors. Measure Laplacian variance on a centre crop (the script approach in §6) before changing sharpening.
- `HdrProcessor` applies a 3×3 chroma box blur and noise-cored sharpening. Tune these only after the HAL MFNR question is settled.

---

## 6. Setup on another machine

1. JDK 17, Android SDK (platform 34, build-tools 34). Create `local.properties` with `sdk.dir=/path/to/android-sdk` (it's git-ignored).
2. Tests: `./gradlew :app:testDebugUnitTest`
3. Build + install: `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Quick sharpness check of pulled photos (Python + Pillow):
   ```python
   from PIL import Image, ImageFilter, ImageStat
   g = Image.open("photo.jpg").convert("L"); w, h = g.size
   c = g.crop((w//2-500, h//2-500, w//2+500, h//2+500))
   lap = c.filter(ImageFilter.Kernel((3,3), [0,1,0,1,-4,1,0,1,0], 1, 128))
   print("sharpness", ImageStat.Stat(lap).var[0])
   ```
5. Not in git (on purpose, the repo is public): the raw `device_props.txt` and `dumpsys_media_camera.txt` (they contain the phone serial number), the GCam APKs (776 MB, third-party), and the `analysis/` photos. To get fresh dumps: `adb shell getprop > device_props.txt` and `adb shell dumpsys media.camera > dumpsys_media_camera.txt`.
