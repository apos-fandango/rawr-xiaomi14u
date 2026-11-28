# Rawr X14U: what this fork adds

This is a fork of [Rawr](https://github.com/adityawarmanfw/rawr) by Fidocia Wima Adityawarman, tuned for the **Xiaomi 14 Ultra**. It installs as its own app ("Rawr X14U", `com.rawr.camera.x14u`) beside Rawr. Upstream Rawr is merged in selectively: vivo, Samsung, 200 MP and UI-tooling work is left out. The untouched upstream code is on the `upstream-main` branch.

Most of the code was written with Claude (Anthropic) and tested on the phone by the fork's owner. The video YUV path comes from [utalpt/rawr](https://github.com/utalpt/rawr) (Paulo Gonçalves).

**Download:** [debug APK](https://github.com/apos-fandango/rawr-xiaomi14u/releases/tag/debug), rebuilt from `main` on every change.

## How to take a feature

Each feature below lists its commits, oldest first: the first one adds it, and the others are follow-ups and fixes. Cherry-pick them in that order:

```sh
git remote add x14u https://github.com/apos-fandango/rawr-xiaomi14u
git fetch x14u
git cherry-pick <first> <next> ...
```

- **Searching the history:** `git log --grep=<word> x14u/main`. Commit messages are plain descriptions, and many explain why with log evidence.
- **Expect conflicts:** features build on the X14U capture screen (`X14uCaptureLayout.kt`) and on the lens options. Pieces marked **portable** work on other phones; pieces marked **X14U** depend on this phone's cameras, keys or accessories.

## Video

| Feature | What it does | Main files | Commits |
|---|---|---|---|
| **YUV encoder input** (portable; from utalpt) | Our GPU writes NV12/P010 straight into the encoder's buffers through an `ImageWriter`, so the encoder doesn't convert RGBA itself. **Fixed 4K30 frame drops**, see [the investigation](#4k30-frame-drops-solved). `adb shell setprop debug.rawr.enc_yuv 0` brings back the RGBA swapchain. | `video/VideoOutput.cpp`, `video/shaders/video_yuv_pack.comp`, `VideoRecorder.cpp`, `VideoSession.cpp` | 3b8348a, 46015fb, fdd0d29 |
| **Encoder output built outside the session lock** (portable) | The encoder Surface setup (gralloc probes, ~300 ms) no longer holds the session lock, so the camera doesn't drop frames at record start. | `SessionEngine.cpp`, `VideoSession.cpp` | 42675fb, 203b96b |
| **1080p60** | 60 fps where the RAW stream runs at 60 (quad demosaic for 2x reduced video). | `CameraRequestCadence.h`, `NativeAvRecorder.cpp` | 84c307b, 7f454ce, 6ac701f, eac2dc8, a9cfaa9, 31a1fd8 |
| **Video lens / run-as lens** (X14U) | A lens can run as another (hidden) lens in video, e.g. the 1x's "1xV" mode. | `LensOptions.kt`, `CaptureViewModel.kt` | 82b0f02, 7b3dd57 |
| **Crop, downscale, GPU resample** (portable) | Aspect / pixel crop / downscale with GPU resampling; standard sizes (4K, 1080p, Open Gate). | `VideoProcessingResources.h`, `VideoRecorder.cpp` | 4145309, 7a11864 |
| **Gyro stabilisation** (portable) | Off / Standard / Strong, time-synced per frame. | `Stabilizer.h`, `present.frag`, `VideoRecorder.cpp` | 9bd6bac |
| **Horizon Lock / Follow** (portable) | Level video with a fixed crop, or gimbal-like roll follow. | `HorizonLock.h`, `RawDevelopRecorder.cpp`, `PresentRecorder.cpp` | ed338d9, c793e35, 9d04373, 582e91d, 8d3a846, 613d29b, 4d0cbe8 |
| **Hold to record locked** (portable) | Holding the record button records with exposure, focus and WB locked. | `VideoLockSettings.kt`, `VideoCaptureControls.kt` | 6767f76, bc19e1d |
| **Video export from bursts** (portable) | HEVC / H.264 / GIF with deflicker, and stacking. | `BurstViewerActivity.kt`, `StackJni.cpp` | 9b4194b |
| **Real-time profiles** (portable) | Per video mode (1080p30/60, 4K30/60, Open Gate 30), which processing features run while recording: highlight reconstruction, FCC, defringe, denoise, DCP, lens correction, stabilise. Editable in Settings → Video Encoder; defaults from frame-time tests. | `VideoProfiles.kt`, `VideoImageSettings.kt`, `VideoRecorder.cpp` | 59304de, 3e862f6, 2ff756e, e9b8148 |
| **One-pass fast path** (portable; from utalpt) | RAW → YUV in one GPU pass (~11 ms at 4K against 28–34 ms) for 4K60 and Open Gate 30, used while the mode's real-time profile has every feature off; half-size monitor with it. Logged as `VIDEO_FAST_PATH active=… reason=…`. | `VideoRecorder.cpp`, `VideoFastPath` | a6271d7, 5c6db30, bf93ce3, aa6d2ef, e9b8148 |
| **24 fps on the 1x** (X14U) | At 24 fps a lens records on its own 4:3 stream instead of its video lens (1xV kills the camera at a fixed 24 fps). `debug.rawr.rec24_video_lens 1` brings the video lens back. | `RawPreviewCoordinator.kt`, `LensOptionsCodec.kt` | ab2f3a7 |
| **Test switches** | adb switches for encoder tests (`enc_extra_images`, `enc_wait_ms`, `enc_rate`, `enc_prio`, `video_mf_off`, `half_monitor`, `classic_cache`), plus encoder start and thermal logs. | `VideoOutput.cpp`, `NativeAvRecorder.cpp`, `ThermalLog.kt`, `DebugProps.kt` | dfaca25, b68f310, b757f3c, 36253f7 |

## Photo and capture

| Feature | What it does | Main files | Commits |
|---|---|---|---|
| **Lens options** (X14U) | Aperture (four steps, f/1.6 to f/4), DCG, ISZ 2x and 50 MP as rows on one lens; per-lens overrides, video DCP per lens, native f-numbers. | `LensOptions.kt`, `LensProfiles.kt` | 8246f38, 7572538, 25f704c, 7b3dd57 |
| **Real 50 MP RAW** (X14U) | 1x (f/2 only) and 0.5x full-size RAW, with autofocus from the 12 MP viewfinder. See [50 MP findings](#50-mp-findings). | `CaptureViewModel.kt` | 604483d, 4c0222c, 23902d9, 9aec427, 41ad6d8, 7bdb218, 6ea9468 |
| **3x 50 MP** (X14U) | A companion session (opMode 0x900F: a camera-processed 1920x1440 viewfinder beside the RAW16 8192x6144 still) as a run-as lens setting, so the 3x takes real full-size RAW with working focus. AWB never runs there, so the 12 MP view's white balance is carried in; manual WB starts from it. | `CameraDeviceSession.cpp`, `ShotHandover.h`, `CameraWhiteBalanceControls.cpp` | bc92c5c, c4a942e, 88580d9, 1c8096c, e1e51ea, ff92b36, 685ee13, 5d96340, 4ac18e5 |
| **25 MP JPEG** (portable) | The 50 MP chip's third stage, "25" (only while JPEGs are saved): the JPEG (SDR or Ultra HDR, base and gain map) is scaled to half the area at the end (8192x6144 -> 5792x4344, linear-light Lanczos), the DNG stays 50 MP. Kept per lens; the JPEG description notes it. | `OutputAspect.h`, `JpegCaptureWriter.cpp`, `FullSizeJpegSettings.kt` | df710c6 |
| **Manual WB across restarts** (portable) | Full-size stills keep a fixed viewfinder WB, and manual WB survives the camera restart after a 50 MP shot (the transform seed is kept). | `CameraDeviceSession.cpp`, `NativeCameraController.cpp` | 5d96340, 8699131 |
| **Digital zoom** (portable) | Pinch / zoom ruler across the whole lens chain (0.5–10x), centred crop, tap mapping; the zoom and 35 mm equivalent written into JPEG and DNG; the crop kept in merged shots. | `ZoomChain.kt`, `OutputAspect.h`, `DngExif.cpp`, `JpegExif.cpp`, `CaptureJob.cpp` | 5376ffa, 268a2dc, b0c2146, b718302, bf9c6b2, fcde197 |
| **Long exposures** (portable) | Long shutters run as their own capture, not the next viewfinder frame. | `NativeCameraController.cpp` | 271a72c, 565b087 |
| **Burst** (portable) | Hold to shoot at 10/20/30 fps, a pre-capture ring (PRE 1–3 s), Pixel-style stacks Google Photos groups, a private store with a viewer, develop in Rawr or another editor. | `BurstStore.kt`, `BurstViewerActivity.kt`, `PrecaptureRing.h`, `SingleFrameCoordinator.cpp` | 935d75d, f0bd25c, 12a150b, c4223f0, 3d523df, a180961, dcd16ae, e799e09, 3080a17, 752975d |
| **Timed burst** (portable) | Intervalometer. | `TimedBurstSettings.kt` | a92b20b |
| **Pano** (portable) | Guided frame sets (manual or automatic) stitched on the phone with a slim static OpenCV build; the canvas is sized within a memory budget. | `PanoStitchJni.cpp`, `X14uPano.kt` | 81efe85, 5f22430, 86a70c6, 7f97a64, 604483d, 1fa7d07, 1feb45c, d7766de |
| **Sensor defect concealment** (portable) | Hot or dead pixels hidden in merges (GPU), using the same rule as single frames. Listing them in DNGs (FixBadPixelsList) is off by default since Android galleries reject such DNGs ([DNGs in Android galleries](#dngs-in-android-galleries-solved)); `debug.rawr.dng_badpixels 1` writes it. | `rx_defect_patch.comp`, `HotPixelConceal.cpp`, `DngGainMapOpcode.cpp` | a726ab1 |
| **Faces** (portable) | "Focus on Faces" and "Expose for Faces". | `CameraFocusControls.cpp` | 5a70853 |
| **50 MP shot focus test** | `debug.rawr.shot50_focus 0`: the 50 MP shot's session sends no focus distance (AF auto, no trigger), so the lens may stay where the 12 MP view focused it; 1 (default) sends the 12 MP view's distance (lands elsewhere at full size on the 3x). | `CameraRequestControls.cpp` | (next build) |
| **Rexture grain evening switch** | `debug.rawr.rx_even 0` skips upstream's grain evening (#132), `debug.rawr.rx_moving 0` the moving-texture restore; logged as `REXTURE_SWITCHES`. See [Rexture colour cast](#rexture-greenmagenta-cast-solved-upstream). | `MergeProcessor.cpp`, `RextureRecorder.cpp` | a333e9d, 6ffb1df, da5a0aa |
| **Film recipe in DNG** | Spektra recipe written for StopBath. | `DngExif.cpp`, `StopBathSpektra.kt` | cdfabf7, ecbcfab |
| **DNG thumbnail** (portable) | A 512 px JPEG thumbnail developed from the RAW, embedded as IFD1, so Google Photos and galleries show the shot instead of a grey placeholder. `debug.rawr.dng_thumb 0` leaves it out. | `DngThumbnail.cpp`, `DngCaptureWriter.cpp`, `tinydng_write.c` | 0e19ea4 |
| **Film recipe strip** | A FILM plate next to the preset plate (dim with film off or in video) opens the film recipes as cards like the presets: tap to apply, long-press drag to reorder (factory and saved share one order), + opens the recipe editor (the film cells and their rulers / choices in the exposure row, live; Cancel · New recipe · Save names it), ··· shows film / print / exposure / format with Edit (same editor, Update), Rename, Duplicate, Delete. Picking or changing a recipe no longer marks a P preset changed. Film settings (editor and Look → FILM) go by section: the row holds FILM, DIR, PRINT, FILTERS, DIFFUSION, GRAIN, HALATION, SCANNER, OUTPUT; the slot holds the section's settings as chips (the effect's switch first, its settings dimmed while off; picking one turns it on) over the chosen setting's ruler / choices. | `X14uFilmRecipes.kt`, `X14uCaptureLayout.kt`, `FilmSimModels.kt` | 987c7df, 43c57c0, d595415 |

## Capture screen and controls

| Feature | What it does | Main files | Commits |
|---|---|---|---|
| **X14U capture screen** | Stock proportions; Photo / Pro / Video (plus Burst and Pano); each mode keeps its own Look, exposure and WB. | `X14uCaptureLayout.kt` | 92f8e34, 36fcbd9, 6cb8e8e, beabc62, 8292b2d, 3ef8c3a |
| **Tools panel** (swipe down) | Grids, aspect crop, level, histogram / waveform, overlays, WB presets, location, DCP. | `X14uToolsPanel.kt` | b123eda, 0df1ad6, 2096b83, 41e26c7 |
| **Focus aids** | Focus loupe with a GPU focus meter; MF ruler on a diopter scale; peaking that ignores sensor specks. | `X14uFocusLoupe.kt`, `focus_peaking.comp` | 1bbd356, 0734974, bc97dbb |
| **Level** | Floating horizon line, steadier lock, smooth change to the bubble. | `X14uToolsPanel.kt` | 6f5f8bf, b757f3c |
| **Photography Kit grip over USB** (X14U accessory) | Dial (EV, shutter, ISO, WB, aperture, focus, zoom), zoom lever, record and shutter buttons, read as raw USB HID reports. | `GripUsbReader.kt`, `GripControls.kt` | 713a2fa, 79063f0, 2fa2f3a, 0e4043f, 581f0ab, 3ef8c3a |
| **Presets** | Saved capture presets (P0 baseline, P1…) with a strip and details sheet; Lens off by default when saving, and a group can be left out. | `CapturePresets.kt`, `X14uPresets.kt` | e356097, e7d9bf3, c7869fd, 6cf512b |
| **LUT strength** (portable) | Look → Profile → STRENGTH for an imported LUT (0–100 %, steps of 5, kept per LUT): the tonemap shader mixes the LUT over RAWR NTRL (each with its own grade) below 100 %, per frame without an engine rebuild. Viewfinder, stills, video and the Renderer recipe; greyed with film on. | `tonemap.comp`, `TonemapMath.h`, `LutStrengthSettings.kt` | 8530f8d |
| **Classic / Bronze styles** | A camera-body look (leather, metal, drum rulers); the body is drawn once into a GPU layer. | `X14uClassic.kt` | 2c3ee52, 10bfd96, 7d1703e, 74d7aa8 |
| **Landscape** | Pills, chips and panels turn as a whole. | `X14uCaptureLayout.kt` | c5b872f, 207fe16, a51e4e8 |

## Findings

### 4K30 frame drops (solved)

**Symptom:** 4K30 recordings dropped frames, starting a few seconds to a minute into a take, sooner the warmer the phone, and faster with the heavier Bronze UI. 1080p and 24 fps were fine.

**What it wasn't**, each tested with an adb switch on 60–100 s takes:
- the encoder's operating-rate and realtime-priority hints
- bitrate (45 vs 80 Mbps)
- more Surface images or a longer wait for a free one
- the HDR+ frame ring in video
- the idle frame-rate rule

**Cause:** the encoder's input was RGBA, which the platform converted to YUV for every 4K frame on the GPU, the same GPU that also runs our RAW pipeline and the UI. When the phone warmed up, Xiaomi throttled clocks before Android's thermal status changed, and the encoder fell just under 30 fps. It holds about 16 frames, so the backlog took tens of seconds to show.

**Fix:** feed it YUV written by our own GPU pass (utalpt's `ImageWriter` path). Five takes of 67–93 s, Minimal, Bronze and LOG, with the battery up to 41 °C: **0 encoder drops**, and the encoder at 30.0 fps throughout.

**Lesson:** test on long takes, and log temperature. Short takes and Android's thermal status both hid the problem.

### Open Gate 30 frame drops (solved)

**Symptom:** Open Gate (4096x2304) at 30 fps dropped frames even with every video feature off.

**Cause:** the full path takes about 34 ms a frame there (tonemap 24.5, demosaic 7.2, white balance 2.5; `VIDEO_GPU_STAGES`), over the 33.3 ms budget, with peaks of 88–107 ms.

**Fix:** Open Gate 30 records through utalpt's one-pass fast path (~11 ms), like 4K60. Its real-time profile now defaults to every feature off, because any feature on sends it back to the full path (e9b8148). The fast path's monitor is half size, which is why the viewfinder picture looked small before bf93ce3.

### Rexture green/magenta cast (solved upstream)

**Symptom:** after merging upstream #132, every Rexture shot had a blotchy cast: magenta in bright areas, green or yellow-green elsewhere, with fine yellow/purple mottle. The merged DNG had it too; the saved base frame and HDR+ were fine. Upstream's own debug build showed the same, so it wasn't a merge mistake (our Rexture shaders matched upstream byte for byte).

**What we found:** with `debug.rawr.rx_even 0` (grain evening skipped) the colours matched HDR+; with only the colour bands left untouched it was still mottled. The pass split the image into luma (G) and colour (R−G, B−G) before white balance, so a luma change moved R, G and B by the same raw amount and became a colour shift once the white balance gains were applied (large under warm light).

**Fix:** upstream #162 does the split after white balance (cherry-picked as 6ffb1df). Tested on the same wall: Rexture matches HDR+. The `rx_even` switch stays as a fallback.

### DNGs in Android galleries (solved)

**Symptom:** our DNGs showed as grey placeholders in Google Photos (Lightroom drew its own); another X14U user reported Xiaomi Gallery and file managers crashing on a folder with them (Aves worked).

**Cause:** the DNGs had no embedded preview image. Galleries and Android's media scanner read a small JPEG from the file instead of decoding the RAW.

**Fix:** a 512 px JPEG thumbnail developed from the RAW, written as IFD1 near the start of the file (0e19ea4). Confirmed in Google Photos, Xiaomi Gallery and F-Stop. Older DNGs stay grey.

**Second cause, single frames only:** some DNGs still showed grey and wouldn't open in Google Photos or Xiaomi Gallery (fine in Lightroom and Windows), seemingly at random. The failing ones held our hot-pixel list (FixBadPixelsList in OpcodeList1; 20 points on a 3x 50 MP single frame). Merges never write it, which made it look random. The list follows the DNG spec, but Android's DNG decoding rejects it: the same file with only an empty OpcodeList1 opens (the other X14U user found Xiaomi Gallery's libdng_sdk aborting on it too). The list is now left out by default.

### 50 MP findings

- **1x:** the phone's full-size RAW mode exists only at f/2. Other apertures only come through the phone's own processed route, so the 50 mp chip locks Aperture to f/2.
- **3x and 5x:** the phone's own "50 MP" route there is a binned 12 MP frame upscaled, not a real full-size readout. The 3x now takes a real readout through a companion session (opMode 0x900F) that keeps focus working; the 5x is still out.
- **Detail:** at normal view 50 MP holds about the same real detail as 12 MP (cross-spectrum of tripod pairs), but renders cleaner when magnified; scaled to 25 MP it is about 40 % less grainy than a 12 MP shot enlarged.
- **Quad-Bayer:** every X14U sensor reports a QCFA sensor, but turning remosaic off gives ERROR_CAMERA_DEVICE, so there is no raw quad-Bayer readout.
- **Brightness:** the IMX858 (0.5x, 3x, 5x) at 50 MP is ~0.55 EV darker than at 12 MP with the same settings; the 1x isn't.
- **0.5x:** real 50 MP RAW works, with a measured ISO gain (×1.43, 7bdb218).

## Known issues and open ends

- **Film simulation on 50 / 25 MP shots** develops only part of the image. Suspected: the film's full-frame source and destination buffers (16 B/px, ~805 MB each at 8192x6144) are beyond the GPU's storage-buffer range (Adreno reports 128 MiB; 12 MP at ~200 MB still works). The planned fix reads and writes each film tile straight from and to the images.

- **One camera frame at record start:** with YUV, 5 of 7 takes lost one camera frame in the first quarter second (`INGRESS_QUEUE_PRESSURE rawDropped=1`). It's not the YUV setup time (moved outside the lock in 46015fb, and it still happened). Next step: per-frame GPU and import timing for the first 2 s of a take.
- **24 fps on the 1x video lens:** the 1x's video lens (1xV, the 16:9 4096x2304 stream) kills the camera a second after a fixed 24 fps range (diagnostics 20261125_112228 and _131228: `Device error received, code 1`, then `encoder output format missing csd-0`, since no frame ever reaches the encoder). Worked around in ab2f3a7: at 24 fps a lens records on its own 4:3 stream. The cause in the camera HAL is unknown.
- **Idle viewfinder at 60 fps:** drops about one camera frame a second; not investigated yet.
- **Heat:** the RAW viewfinder runs the GPU about two thirds busy even when idle. Candidates to cut: the HDR+ ring in video (`video_mf_off`), the half-size monitor (`half_monitor`), and UI redraws while recording.
- **Upstream and forks:** from utalpt, the YUV input and the one-pass fast path are in; his clean recording view, Gyroflow log, WB picker (tap-to-neutralise) and the new look sheet are not ported yet. Upstream is merged up to #168 (skipped: #160 OPPO, vivo, Samsung and UI tooling).

## Branches

- **`main`:** the X14U app; each push builds the debug APK.
- **`upstream-main`:** Rawr as published, for diffs.
- **`hold/next-build`:** work in progress.
- **`wip/next-build`:** compile check only.
