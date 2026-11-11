# SwapStudio AI

**Hyper-realistic photo and video face swap that runs entirely on the phone.**

No server, no upload queue, no account. The weights are downloaded once, then every frame is
decoded, swapped, colour-matched and encoded on the device — on the Hexagon NPU / Adreno GPU when
the chipset has them, on the CPU otherwise.

<p align="center">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF">
  <img alt="Compose" src="https://img.shields.io/badge/Compose-BOM%202024.12-4285F4">
  <img alt="ONNX Runtime" src="https://img.shields.io/badge/ONNX%20Runtime-1.22-005CED">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-29%20(Android%2010)-3DDC84">
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-35-3DDC84">
</p>

---

## Contents

* [What it does](#what-it-does)
* [Screens](#screens)
* [The pipeline, step by step](#the-pipeline-step-by-step)
* [Hyper-realistic details that actually matter](#hyper-realistic-details-that-actually-matter)
* [Performance](#performance)
* [Video export quality](#video-export-quality)
* [Privacy, safety and labelling](#privacy-safety-and-labelling)
* [Build](#build)
* [Model packs](#model-packs)
* [Repository layout](#repository-layout)
* [Tests and checks](#tests-and-checks)
* [Troubleshooting](#troubleshooting)
* [Licence notes](#licence-notes)

---

## What it does

| | Photo swap | Video swap |
|---|---|---|
| Input | Two images (identity + target) | Any clip the gallery can open + one identity photo |
| Output | Full-size JPEG or PNG, optional 4K (Real-ESRGAN) upscale | 1080p / 1440p / 4K H.264 or HEVC MP4, original audio stream-copied |
| Multiple faces | Tap the face you want replaced | Every tracked face gets its own identity and colour state |
| Time | 1–4 s on a flagship | roughly real time to 2× real time, depending on SoC |

Everything is offline after the first launch. There is no analytics, no crash reporter, no network
call except the model download itself.

## Screens

```
Home ──┬── Photo swap ──► Result
       ├── Video swap ──► Result
       ├── Models
       └── Settings
```

* **Home** — device card (SoC, cores, RAM, live swap rate), model readiness, one tap into either
  workflow.
* **Photo swap** — two pick tiles, face analysis with per-face badges (identity score, angle, size),
  quality controls (hyper-realistic toggle, GFPGAN strength, mesh overlay, upscale, grain match, PNG),
  performance chips, consent checkbox.
* **Video swap** — clip tile with duration/resolution/HEVC badges, identity slot, export card
  (height, HEVC, copy audio, bitrate slider with an estimated file size), micro-expression controls,
  live progress (stage, %, frame count, fps, ETA) with cancel.
* **Models** — per-model download/pause/resume/delete, byte-level progress with speed and ETA,
  storage bar, "Download all" / "Pause all".
* **Settings** — hardware provider, quality and video defaults, consent state, diagnostics, reset.
* **Result** — ExoPlayer preview or before/after slider, quality card (resolution, frames, bitrate,
  whether the file really met the 4K/20 Mbps bar), save-to-gallery with progress, share, open.

## The pipeline, step by step

```
 image / clip
      │
      ▼
┌─────────────────┐   MediaPipe Face Landmarker (478 pts, 52 blendshapes, 4×4 matrix)
│  detect + mesh  │   SCRFD det_10g for the initial boxes and 5-point alignment
└────────┬────────┘
         ▼
┌─────────────────┐   ArcFace r50 embedding of the identity crop (one 112×112 forward pass,
│  identity       │   cached for the whole clip)
└────────┬────────┘
         ▼
┌─────────────────┐   inswapper_128_fp16, every frame, 128×128 aligned crop
│  swap          │   with the source latent matrix projected into the model's embedding space
└────────┬────────┘
         ▼
┌─────────────────┐   BiSeNet face parser → skin / hair / glasses / occlusion masks
│  composite     │   feathered mask, eye and mouth hole preservation
└────────┬────────┘
         ▼
┌─────────────────┐   Lab-space statistics transfer per region (forehead, cheeks, chin/nose),
│  colour match  │   CLAHE + grey-world, optional Poisson blend for hard edges
└────────┬────────┘
         ▼
┌─────────────────┐   GFPGAN v1.4 at 512² with a fidelity weight driven by the "hyper realistic"
│  enhance       │   slider, then grain matched from the original face patch
└────────┬────────┘
         ▼
┌─────────────────┐   blendshape EMA (0.85/0.15, 5-frame window) + optical-flow jitter damping
│  temporal      │   so eyes blink and mouths move without per-frame flicker
└────────┬────────┘
         ▼
    MediaCodec H.264/HEVC ──► MediaMuxer ──► MediaStore (DCIM/SwapStudio)
                                              audio copied, never re-encoded
```

Key files:

| File | Role |
|---|---|
| `core/swap/PhotoSwapPipeline.kt` | Image path: detect → swap → blend → enhance → export bitmap |
| `core/swap/VideoProcessor.kt` | Decode → swap → encode loop, hardware encoder, audio passthrough |
| `core/swap/FaceSwapper.kt` | ORT session for inswapper + ArcFace, embedding cache, latent projection |
| `core/swap/FaceBlender.kt` | Occlusion-aware composite, eye refinement, grain synthesis |
| `core/swap/TemporalStabilizer.kt` | Optical-flow based jitter damping between frames |
| `core/processing/ColorMatcher.kt` | Lab statistics transfer, regional histogram match, Poisson blend |
| `core/detection/FaceDetector.kt` | SCRFD decode + NMS + 5-point alignment |
| `core/mesh/FaceMeshAnalyzer.kt` | MediaPipe Tasks wrapper, blendshapes, transformation matrix |
| `core/export/ExportManager.kt` | MediaStore insert, "AI Generated" metadata, share/open intents |

## Hyper-realistic details that actually matter

A swap looks fake for a handful of specific reasons. Each one has a dedicated step:

| Artefact | Fix in this app |
|---|---|
| Skin tone mismatch (the classic "pale face" look) | Lab-space mean/std transfer **per region** instead of one global correction, plus CLAHE normalisation |
| Plastic skin | GFPGAN is run at a **fidelity weight > 0** so pores and wrinkles survive; grain is copied from the original patch (`FaceBlender.matchGrain`) |
| Dead eyes | Iris landmarks are kept from the target; eye regions are masked out of the blend and unsharp-masked separately (`faceBlender.eyeRefine`) |
| Hair / glasses eaten by the mask | BiSeNet classes for hair, glasses and skin are unioned into an occlusion mask that stays on top of the swap |
| Flicker between frames | 5-frame blendshape EMA + Farneback optical flow between consecutive faces |
| Teeth and mouth interior | Mouth interior landmark polygon masked to the target |
| Wrong identity | `w600k_r50` embedding drives inswapper directly; the identity score in the result card is the cosine similarity of source vs. swapped embedding |

## Performance

* `PerformanceMode.BATTERY_SAVER / BALANCED / MAX` map to ORT execution providers and thread counts
  (QNN HTP vs. QNN GPU vs. NNAPI vs. XNNPACK CPU).
* ONNX Runtime session reuse: one session per model, created lazily, `OrtSession.SessionOptions`
  tuned per mode (`ORT_ENABLE_ALL` graph optimisation, arena allocator off for the big models).
* The decoder writes straight into the swapper's input tensor layout where possible; no intermediate
  `Bitmap` for the aligned crop.
* Video frames are recycled (`Bitmap.recycle` in `finally`) — a 4K clip would otherwise OOM in a
  minute.
* `largeHeap` is on and the APK is `arm64-v8a` only for the QNN flavour.

## Video export quality

The spec asks for "4K, high bitrate, hyper realistic" and the app tries hard to actually deliver it,
but the ceiling is the **device encoder**, not the app:

* Resolution and bitrate are requested from `MediaFormat` (`KEY_BIT_RATE`, `KEY_BITRATE_MODE =
  VBR`, `KEY_I_FRAME_INTERVAL = 1`), then the **written file is measured** with
  `MediaMetadataRetriever`. If the encoder silently capped the output, the result screen says so
  instead of claiming 4K.
* The colour format is **negotiated with the encoder** (`MediaCodecList` → `CodecCapabilities`)
  instead of assumed: `COLOR_FormatYUV420Flexible`, NV12, Qualcomm's semi-planar alias, NV21 and
  planar I420 are all supported, and `YuvUtils` produces whichever layout came back. Picking a
  format the codec does not advertise is the classic "export finishes but the video is green"
  failure, so the conversion is unit tested (`YuvUtilsTest`).
* The output timeline starts at 0 regardless of the source `presentationTimeUs` (a trimmed clip or a
  file with a non-zero start plays correctly), and portrait capture is rotated in the pixels rather
  than relying on encoder-side rotation metadata.
* HEVC is offered as a toggle: roughly half the bitrate for the same quality, and every recent
  Snapdragon does it in hardware. It stays off by default so the file plays everywhere.
* Audio is stream-copied through `MediaExtractor` → `MediaMuxer` when "copy audio" is on, using the
  *original* track format (so `csd-0`/`csd-1` survive) and honouring the trim window. Bit-exact, no
  quality loss, no ffmpeg dependency.
* Defaults: 2160p, 24 Mbps, H.264, copy audio. The bitrate slider goes 12–48 Mbps and the card
  shows the estimated output size before you start.

## Privacy, safety and labelling

* **Nothing leaves the device.** No network code except the model downloader (`ModelDownloader`,
  OkHttp with resume/Range support). No analytics SDKs in the dependency list.
* **Consent gate** — the checkbox is required before a swap can start, and the state persists.
* **Content analyser** — `open_nsfw` runs on every source and target image (and sampled video
  frames) before any processing; explicit media is refused.
* **Visible label** — `BitmapUtils.drawWatermark` burns "AI Generated" into every export, and
  `ExportManager` also writes `DESCRIPTION`/`TITLE` metadata into MediaStore.
* Model weights are never committed and never bundled in the APK.

## Build

### Requirements

* Android Studio Ladybug or newer, **JDK 17**
* Android SDK **35** (`compileSdk = 35`, `minSdk = 29`)
* A device with Android 10+; a Snapdragon 8-series for the QNN flavour

### From the command line

```bash
git clone https://github.com/SwaRohil/FaceFusionMobile.git
cd FaceFusionMobile

# 1. cheap sanity checks (no Gradle needed, ~1 s)
tools/verify.sh

# 2. unit tests
./gradlew :app:testDebugUnitTest

# 3. debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk (applicationId com.swapstudio.ai.debug)

# run the release pipeline (R8 + resource shrinking)
./gradlew :app:assembleRelease

Release builds must be signed with your own upload key; create `keystore.properties`
and wire it into `signingConfigs` (or use *Build → Generate Signed Bundle* in Android Studio).

### Build switches (`gradle.properties`)

| Key | Values | Why |
|---|---|---|
| `swapstudio.ortRuntime` | `qnn` (default) \| `nnapi` | `qnn` ships `onnxruntime-android-qnn` (Hexagon NPU + Adreno GPU, arm64 only). `nnapi` ships the universal AAR (CPU + NNAPI + XNNPACK, also runs on x86_64 emulators). The two AARs both contain `libonnxruntime.so`, so only one may be linked. |
| `swapstudio.ffmpegKit` | empty (default) \| path to an AAR \| Maven coordinates | Optional FFmpegKit bridge for exotic containers. Empty means the built-in `MediaExtractor`/`MediaMuxer` path is used, which is a lossless audio copy anyway. FFmpegKit was retired upstream, so it is never a default dependency. |

```bash
# universal build that also runs on an emulator
./gradlew :app:assembleDebug -Pswapstudio.ortRuntime=nnapi
```

## Model packs

`app/src/main/assets/models.json` is the single source of truth. It is parsed at runtime by
`ModelCatalog`, shown in the Models screen, and used for the storage pre-flight check.

| id | File | Size | Used for |
|---|---|---|---|
| `face_landmarker` | `face_landmarker.task` | 4 MB | 478-point mesh, 52 blendshapes, head pose |
| `face_detector` | `buffalo_l.zip` → `det_10g.onnx`, `w600k_r50.onnx` | 276 MB | Detection, 5-point alignment, identity embedding |
| `face_swap` | `inswapper_128_fp16.onnx` | 265 MB | The swap itself |
| `face_parser` | `bisenet_resnet_34.onnx` | 89 MB | Skin / hair / glasses / occlusion masks |
| `face_enhancer` | `gfpgan_1.4.onnx` | 332 MB | 512² face restoration with fidelity control |
| `frame_enhancer` | `real_esrgan_x2_fp16.onnx` | 35 MB | 2× upscale for the 4K path (optional) |
| `content_analyser` | `open_nsfw.onnx` | 23 MB | Explicit-content gate (optional but recommended) |

* Downloads are **resumable** (HTTP `Range`), verified by SHA-256 when the manifest publishes one,
  by byte count + CRC32 otherwise. The Models screen spells out which check applies.
* Archives (`.zip`) are unpacked into `filesDir/models/`; individual `.onnx`/`.task` files are stored
  as-is.
* Required total: ~966 MB, all of it on-device after the first download.

### Adding a model

1. Add an entry to `models.json` (`id`, `name`, `file_name`, `url`, `size_mb`, `sha256`/`crc32`,
   `version`, `required`, `description`).
2. Run `tools/verify.sh` — it validates the JSON schema, unique ids, https URLs and digest formats.
3. Reference it through `ModelStore` (`store.resolve("file.onnx")`); never hard-code a path.

## Repository layout

```
FaceFusionMobile/
├── app/src/main/java/com/swapstudio/ai/
│   ├── core/
│   │   ├── background/   SwapForegroundService, ModelDownloadService
│   │   ├── detection/    SCRFD detector + NMS
│   │   ├── export/       MediaStore writer, share/open intents, VideoInfo
│   │   ├── geometry/     ArcFace templates, 5-point alignment, region masks
│   │   ├── inference/    OrtEngine (provider selection, session cache)
│   │   ├── mesh/         MediaPipe Face Landmarker wrapper
│   │   ├── models/       catalog, downloader, store, manager
│   │   ├── processing/   ColorMatcher (Lab transfer, histogram match, Poisson)
│   │   ├── safety/       consent + NSFW gate
│   │   ├── swap/         photo pipeline, video processor, swapper, blender, temporal, enhancer
│   │   └── util/         bitmap/mat conversion, hashing
│   ├── data/             DataStore-backed SettingsRepository
│   └── ui/               theme, shared components, screens (one ViewModel each), nav host
├── app/src/main/assets/models.json
├── app/src/test/java/…   JVM unit tests (no device needed)
├── tools/verify.sh       static repo self-check
└── index.html            the original browser prototype, kept for reference
```

## Tests and checks

```bash
tools/verify.sh                 # required files, strings, manifest, models.json,
                                # balanced braces/comments, leftovers
tools/verify.sh --with-build    # the same, then ./gradlew test + assembleDebug
./gradlew :app:testDebugUnitTest
```

JVM unit tests cover the pure logic that does not need a GPU or a device:

* `HashUtilsTest` — SHA-256 / CRC32 digests, digest verification, streaming of large files
* `BlendshapeEmaTest` — filter response, frozen-face detection, blink amplitude, reset
* `YuvUtilsTest` — I420/NV12/NV21 layouts, BT.601 studio-swing levels, 2×2 chroma averaging, round trip
* `FaceGeometryTest` — five-point ordering, roll/yaw/pitch on synthetic meshes, 80 px minimum,
  ArcFace template values

Instrumented tests (`app/src/androidTest`) are for the parts that need real codecs and ORT sessions.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Models missing: 0/7 installed` | Expected on first launch. Open **Models → Download all**, or use a device/emulator with network access. |
| Swap starts then fails with an ORT error | The QNN flavour needs a Snapdragon 8-series and 64-bit ARM. Rebuild with `-Pswapstudio.ortRuntime=nnapi`. |
| Result card says the file did not meet 4K/20 Mbps | The encoder capped it. Raise the bitrate, switch HEVC ↔ H.264, or accept the warning — the app reports what was actually written. |
| "Face too small" warning | Faces under 80 px do not have enough detail for a convincing swap. Crop closer. |
| Video render is very slow | Battery saver forces the CPU provider. Use **Max** or **Balanced** while charging. |
| Duplicate notification channels after upgrade | Uninstall/reinstall once; channel settings are owned by the system. |
| Render cancelled / failed and no file appears | Intentional: a partial MP4 is deleted and the codecs are torn down, so the next render starts clean. The reason is shown on the video screen. |

## Licence notes

* The app code in this repository is the project's own work.
* `inswapper_128`, GFPGAN, Real-ESRGAN and BiSeNet weights carry their own upstream licences
  (**non-commercial** for several of them) — check them before any commercial use.
* ONNX Runtime, MediaPipe, OpenCV, Media3, OkHttp and AndroidX are Apache-2.0.
* This tool is meant for consensual, clearly labelled face swaps. Do not use it to impersonate or
  deceive.
