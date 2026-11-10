# FaceFusion Mobile

Offline face swapping on your phone. Pick a source face and a target photo or video, and the swap runs entirely on your device. No server, no account, nothing uploaded.

This is a mobile port of [FaceFusion](https://github.com/facefusion/facefusion) by Henry Ruhs. The pipeline, the models, and the option names, defaults and ranges are FaceFusion's. The native Android app ([AbrahamPaulJ/facefusion-mobile](https://github.com/AbrahamPaulJ/facefusion-mobile)) runs hyperswap on the Qualcomm Hexagon NPU; this web build runs the same workflow in any mobile browser — face detection + landmarks (face-api.js) with a landmark-warp swap engine, fully on-device.

## What it does

- Swaps faces in photos and video, fully offline (PWA — installable, works offline after first load).
- **Swap screen**: multiple source faces (row under the source pane), saved faces (🔖, kept across sessions), side-by-side before/after preview before you commit.
- **Processors** (optional, run in order): `face_swapper` (hyperswap 1a/1b/1c blend presets), `face_editor` (14 LivePortrait-style offset sliders), `lip_syncer` (mouth motion from a voice file), `face_enhancer` (sharpen + contrast).
- **Assign per person**: give each person their own source face, or **Keep face** to leave someone untouched.
- **Pick the lips**: tap 👄, then tap faces to choose who gets lip sync (green frames, count badge).
- **Face animator**: a photo's face moves like the face in a driver video → video output.
- **Talking photo**: lip sync on a photo with a voice makes a video the voice's length.
- **Output settings**: trim, frame rate, resolution cap (1080p/720p/480p), **Fast video** (tracks the face between frames), cancel any run.
- **Live**: front/back camera swapped in real time, tap a face to assign a source, fullscreen, record (optional microphone audio).
- **Batch**: queue several clips, swap them all in one run with one source face.
- **Settings**: light/dark/system theme, English / Русский / 简体中文 / 繁體中文, device panel (GPU, cores, RAM), graphics chip, low-memory mode, model download source (CDN / hf-mirror), bug report, run log.
- Saves to your gallery (download) or shares directly.

## Install

Open `index.html` in a mobile browser and use **Add to Home Screen** (PWA). Models load from CDN on first run and are cached for offline use.

## Privacy

No accounts, no analytics, no telemetry. Your photos and videos are never uploaded. Bug reports are assembled only when you tap **Share bug report** and contain no media.

## Content policy

**Do not use this on real people without their consent.** That is the main way software of this kind causes harm, and it is prohibited by the upstream licence.

## Licence and attribution

Built on [FaceFusion](https://github.com/facefusion/facefusion), licensed **OpenRAIL-AS**, which carries use restrictions. Read the licence before using, modifying or redistributing this. The native app's models are converted from FaceFusion's and are not uniformly permissive (`yoloface_8n` GPL-3.0, `arcface_w600k_r50`/`inswapper_128` Non-Commercial, `hyperswap_1a_256` ResearchRAIL).

## Support

Free with no accounts and nothing to pay for. If it has been useful: [buymeacoffee.com/abrahampaulj](https://buymeacoffee.com/abrahampaulj).
