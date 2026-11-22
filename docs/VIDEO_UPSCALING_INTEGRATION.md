# Video Upscaling Integration

This document records the implementation boundary for adding saved-video upscaling to Nightmare Mobile. Work is confined to the `video-upscaling-integration` branch; the fork's `main` and the upstream repository must remain unchanged.

## Required user experience

- A user opens a video already saved in the Results tab and can choose **Upscale video** from the existing Results/viewer actions.
- The source MP4 remains untouched.
- The completed upscaled MP4 is saved under Nightmare's existing app-private results directory and appears as a separate Results item with its own poster.
- No MediaStore/gallery export is performed automatically.
- Cancellation or failure removes incomplete temporary/output files and leaves the source and existing Results entries intact.
- Audio from the source is preserved in the finished MP4. Audio-bearing source-video testing is a release gate.

## Security and scope boundaries

- Video upscaling is on-device only. The decoder, frame processing, QNN inference, encoder, and result storage must remain local.
- Do not add network requests, upload video or frames, or change existing networking, model-download, AI-assistant, TLS, or authentication behavior.
- Reuse the model files already installed by Nightmare's existing model catalogue. Do not convert, bundle, mirror, or redistribute model files as part of the initial implementation.
- Do not add permissions, exported components, services, or third-party dependencies unless a demonstrated requirement justifies them and their security impact is reviewed.
- Do not weaken existing storage privacy, component protections, thermal safeguards, cancellation, or job coordination.
- GitHub Actions on the fork remain disabled until inherited workflows have been reviewed and any required CI is separately scoped and hardened.

## Confirmed existing architecture

- `ResultsStore` owns app-private result files and metadata. A video result is represented by a poster PNG, JSON metadata, and an MP4 at the same result ID.
- `Result.videoPath` is populated from the MP4 actually present in the Results directory.
- `ResultsScreen` and the fullscreen result viewer now retain the shared picker but route video selections through a separate callback and in-process QNN pipeline. Video choices are restricted to the two built-in QNN contexts; imported `.pth`/`.safetensors` models are not sent to `QnnRunner`.
- `VideoWriter.encode(...)` writes frames to a file and does not itself publish to the gallery. It now has optional per-frame PTS, orientation, and color-metadata inputs; existing callers retain the previous fixed-cadence behavior. `VideoWriter.write(...)` calls `publish(...)`, which exports to MediaStore and must not be used for this feature.
- `QnnRunner` and `NativeQnn` provide an in-process QNN route. `QnnRunner.handleOf(File)` caches a loaded context by absolute path; `describe(File)` exposes the binary's own tensor names, dimensions, and dtypes; `run(File, inputs)` executes named inputs and returns named outputs. Native execution is blocking and must run off the main thread.
- The existing image-upscale route is different: `Ops.upscale` sends raw RGB to the app's local backend `/upscale` endpoint, which creates and frees the QNN model for each request. Calling that endpoint once per video frame would repeatedly load the model and is not the preferred video design.
- The existing `AudioMux.mux(...)` helper is for a content URI and returns false on failure; it is not a safe drop-in for a private Results file when audio preservation is mandatory.
- `ResultsStore.keep(...)` remains best-effort for ordinary generated clips. `keepUpscaledVideo(...)` is now a separate strict operation: it copies the completed MP4 with cancellation checks, syncs and renames poster/MP4 temp files, writes metadata last, and rolls back partial files on failure.
- `DriverFrames` uses `MediaMetadataRetriever` for batched frame reads in motion-transfer features. Its approximate frame-index/time mapping is not yet validated for preserving arbitrary source-video timestamps and duration.

## Model/runtime investigation — current findings

- The built-in catalogue exposes **Anime 4x** (RealESRGAN x4plus anime 6B) and **Realistic 4x** (4x UltraSharp V2 Lite) as existing QNN context binaries. Their existing installation and path-resolution code should remain the source of truth.
- The upstream QNN upscaler implementation documents a single graph with one float input shaped `1x3x192x192` and one float output shaped `1x3x768x768`. Its image tiler converts RGB bytes to normalized `[0,1]` CHW floats, uses 192-pixel tiles with 12-pixel input overlap, and blends 768-pixel output tiles.
- This upstream implementation is useful evidence about the intended model contract, but it does **not** prove that every installed binary works through Nightmare's in-process `NativeQnn` wrapper. The actual installed binary must be described and checked at runtime (graph count, input/output names, shapes, dtypes, and element counts), and a known test tile must execute successfully on-device before the pipeline is implemented around those assumptions.
- The in-process route is the leading design because it can keep the existing downloaded context resident across many frame tiles. This remains a hypothesis until the on-device metadata and execution checks pass.
- Do not use the standalone app's LiteRT `.tflite` runner as if it were a drop-in for Nightmare's QNN context-binary runner. They are different model artifacts and APIs.

## Implementation sequence and current status

1. **QNN compatibility:** the read-only `upscaler_qnn_probe` is committed and awaits a debug build plus one on-device run. It tests both the existing JNI return-array path and the new reusable-output-buffer JNI path used by video tiles. The production frame class independently validates the actual loaded tensor names, shapes, dtypes, and output order before it can save a result.
2. **Frame inference:** `QnnVideoFrameUpscaler` now implements the 192x192 → 768x768 float32 CHW tile path, upstream placement/fade weights, normalized RGB input, 2x/3x/4x output sizing, finite-output checks, and bounded 768-row stripe accumulation. It reuses the same input/output arrays across all tiles through `QnnRunner.runInto` and the new `NativeQnn.executeInto` JNI method; it does not allocate a new 6.75 MiB Java output array per tile or a full-frame float accumulator/weight map.
3. **Memory safety:** `QnnUpscaleMemoryBudget.preflight` runs before the QNN context and codecs are created. It accounts for the native 4x bitmap, requested output bitmap, stripe, tile buffers, source frame, and a decoder/encoder reserve. The same budget is checked again per frame.
4. **Media inspection/decoding:** `VideoUpscaleInspector` scans video and audio timestamps before decoding, checks cancellation and encrypted-sample flags, and inspects rotation, crop, color metadata, bit-depth profiles, and audio presence. It refuses HDR, BT.2020, known non-8-bit formats, multiple audio/video tracks, and audio codecs that Android cannot preserve in MP4 (only AAC/AMR are accepted). `VideoFrameDecoder` uses `MediaExtractor` + `MediaCodec` with an RGBA `ImageReader` surface and emits one timestamped frame at a time.
5. **Encoding/timing:** `VideoWriter.encode` has backward-compatible per-frame PTS, bitrate-override, and watchdog-heartbeat options. The saved-video path disables B-frame reordering, preserves rotation and SDR color metadata, verifies encoder frame count, and uses a bounded high-resolution H.264 bitrate plus a free-storage preflight. It uses decoded presentation timestamps rather than synthesizing a CFR timeline.
6. **Audio:** `StrictVideoAudioMuxer` remuxes the source's compressed audio samples with the re-encoded video samples in timestamp order against a shared timeline origin. It copies the complete audio track, checks strictly increasing per-track timestamps, and fails the whole job if any audio sample cannot be copied. It does not use the non-fatal legacy `AudioMux.mux(...)` helper.
7. **Storage:** `ResultsStore.keepUpscaledVideo` commits a separate result with its own poster and MP4, source-result provenance, selected upscaler, and requested scale. The original source remains untouched and the output is never sent to MediaStore.
8. **UI/job coordination:** Results and the fullscreen viewer expose the video action. The view model uses the existing `run(...)` job wrapper and `RunLock`, keeps QNN work off the main thread, releases its runner in `finally`, and applies local battery-temperature pause checks at tile boundaries using the existing preference thresholds and hysteresis. Decoder, tile processing, and thermal waits feed the existing encoder watchdog heartbeat so legitimate NPU work or thermal pauses are not misreported as encoder stalls.
9. **Verification:** source and unit-test changes are committed, but no Android Gradle build or device test has been run. The decoder's RGBA surface support, actual installed QNN context execution, output visual quality, audio/video sync, encoder capabilities, memory behavior, cancellation, and sustained thermals remain unverified. Do not treat this branch as a working release until those checks pass.

## Licensing and release gate

The initial implementation reuses model files that Nightmare already downloads and uses; it must not create converted model files or redistribute weights. This reduces the scope of model handling but does not itself prove the applicable model licenses permit every use. Preserve existing notices and review the relevant terms before public distribution. Do not publish model binaries or a public release until licensing is clear and audio preservation has been tested on an audio-bearing source.


## Additional source-level constraints confirmed during review

- `NativeQnn.loadLocked` explicitly rejects binaries containing anything other than exactly one graph. `runLocked` also requires every graph input exactly once, matched by its actual tensor name. The runtime diagnostic must therefore verify the graph count and complete input/output contract before any inference; a matching tensor shape alone is not enough.
- The native `g_mu` mutex serializes each load, describe, execute, and free operation across the process. It prevents simultaneous native QNN calls and protects handles, but it does **not** reserve the NPU for an entire multi-frame job: another QNN user can run between tile executions. `QnnRunner` caches handles per runner instance, so the video job must own and release only its own handle. A separate process-wide job coordinator is needed to prevent memory-heavy video generation and saved-video upscaling from overlapping; the existing `VideoSampleNode.inFlight` flag is private to that node and does not coordinate a new Results action.
- `BatteryHeat.watch` pauses through the backend's `/pause` callback, which does not pause direct in-process `NativeQnn` execution. The saved-video view-model therefore uses a separate local cancellable pause at tile boundaries, polling `BatteryHeat.celsius()` and applying `Prefs.heatPause`, `Prefs.heatPauseAt`, and the existing 5 °C hysteresis. This source-level behavior still needs device thermal testing.
- `VideoWriter.encode(count, …, frameAt)` still defaults to its existing fixed cadence for current callers. The new optional timestamp mode accepts per-frame PTS, sets `KEY_MAX_B_FRAMES=0`, validates monotonic timestamps and output frame count, and carries orientation/color metadata. Only the saved-video pipeline uses this mode; it remains unverified on-device.
- `AudioMux.mux(...)` is specifically URI-based, seeks the source audio from a supplied offset, and treats no audio or any mux error as a non-fatal `false`. That behavior is intentionally unsuitable for this feature's strict audio requirement. A separate private-file remux operation must first inspect whether the source has an audio track, copy compatible compressed samples to a temporary MP4, validate the muxed result, and fail/clean up if an audio-bearing source cannot be preserved. The source MP4 must never be the mux destination.
- `ResultsStore.keep(...)` remains best-effort for ordinary kept clips. The new `keepUpscaledVideo(...)` uses the same process-wide storage lock, checks a collision-free ID, writes/syncs temporary poster and MP4 files, supports cancellation during the MP4 copy, commits metadata last, and rolls back partial files on failure.
- The native runner's global mutex is a per-call safety mechanism, not a substitute for job-level cancellation, memory budgeting, or thermal policy. All QNN calls remain on a worker dispatcher, and the context must be released in `finally` after the final tile or any exception/cancellation.

- `FrameInterp.Session` is another existing in-process `QnnRunner` consumer; it holds its own IFRNet context (documented in code as roughly 25 MB) for a clip. The native mutex serializes its individual calls against other QNN work, but does not make a whole clip session exclusive. The new feature must not assume the only competing QNN work is `VideoSampleNode`; review all session lifetimes and use a shared job-level gate for the memory-heavy video-generation path before allowing saved-video upscaling to start.


## Existing image-upscaler behavior and video memory implications

- `UpscalerCatalog.pathFor(context, id)` is the supported installed-file resolver. It calls the same spec/path logic used by the existing image-upscale UI, including the Local Dream shared-folder fallback and the selected `ModelStorage` location. A video action must use this resolver (or the exact spec's `file(context)` after checking `installed(context)`), never assemble paths or invoke `install`/`download`.
- The built-in catalogue contains two 4x QNN context binaries: `upscaler_anime` (RealESRGAN x4plus anime 6B) and `upscaler_realistic` (4x UltraSharp V2 Lite). Device build selection is already defined by `DeviceProbe.caps()` and each spec's `buildFor`; the runtime must still validate the actual installed file, not infer its tensor contract from the selected tier.
- The upstream QNN implementation at `xororz/local-dream` tag `v3.0.0-alpha.4`, `app/src/main/cpp/src/Upscaler.hpp`, confirms the real preprocessing and tile driver: RGB HWC bytes are converted to normalized float32 CHW; input tiles are 192x192; the graph returns 768x768 tiles; source tiles overlap by at least 12 pixels; and the output uses a 48-pixel overlap with a 24-pixel linear fade at each edge followed by weighted normalization. If either source edge is below 192, the upstream endpoint first resizes the source so its shorter edge is 192, then resizes the final 4x result back to exactly 4x the original dimensions.
- The image UI's selectable output scales (2x/3x/4x) are implemented by running the native 4x model and resizing its assembled output to the requested scale; `UpscaleNode.MAX_OUT_EDGE` is 4096. A video feature should preserve that user-facing scale convention and edge cap, but cannot blindly copy the upstream full-frame float accumulators: at high-resolution video, a 4x intermediate accumulator can exceed the app's safe heap budget even when the requested final output is only 2x or 3x. Use bounded tile/stripe accumulation or reject the frame before allocating; never risk an OOM crash or silently lower resolution.
- The upstream implementation's full-frame accumulators are `float[3 * outputWidth * outputHeight]` plus a float weight map. For a 1920x1080 source at native 4x, that is approximately 380 MiB + 127 MiB before input, output bitmap, encoder, and runtime memory. This is not acceptable as a default video-frame strategy on Android. Memory budgeting is a release blocker, not a later optimization.
- `RunLock` is the app-process gate used by the canvas and API. The Results-originated video action enters through the existing `run(...)` wrapper, which acquires/releases that gate and refuses if another job owns it. `VideoSampleNode` additionally has its own in-flight guard; keep reviewing all other in-process QNN session lifetimes because the native mutex serializes individual calls, not whole jobs.


## Implemented media path — source-level, not device-verified

- The inspector reads the source MP4's video sample presentation timestamps, sorts the sample PTS into display order (MP4 samples may be stored in decode order when B-frames are present), rejects duplicate PTS, and computes a shared audio/video timeline origin. The decoder's output PTS is checked against that scan and then passed to the encoder after subtracting the common origin.
- The decoder targets a packed RGBA `ImageReader` surface. This avoids `MediaMetadataRetriever`'s approximate frame seeking and keeps only one decoded bitmap alive at a time. Some device decoders may not support this output surface; that is an explicit runtime failure, not a reason to silently fall back to a lower-fidelity path.
- The source rotation is written as MP4 orientation metadata; the Results poster is rotated to the displayed orientation. Color standard, transfer, and range are passed through to the encoder when present. HDR/BT.2020/known non-8-bit sources are refused.
- The frame upscaler raises a source edge below 192 to the model's minimum input size, then resizes the native 4x result to exactly the selected 2x/3x/4x dimensions. It uses a bounded stripe accumulator and preflight memory gate.
- The output video is encoded to a private intermediate MP4 at a bounded, resolution-aware H.264 bitrate. If the source has AAC or AMR audio, a separate final MP4 is created by interleaving intermediate video samples and original compressed audio samples using their common-origin timestamps. The full audio track is copied; unsupported codecs, DRM/encrypted samples, oversized packets, or timestamp anomalies fail the job and remove partial output.
- The view-model's thermal gate polls the battery sensor and pauses only between QNN tiles. It uses `Prefs.heatPause`, `Prefs.heatPauseAt`, and the existing 5 °C hysteresis; it does not send fake backend `/pause` calls. The encoder watchdog is explicitly heartbeated while the thermal gate waits.
- The output MP4 and poster are copied into the private Results directory using temp files and metadata-last renames. The source Result and source MP4 are never modified. No MediaStore/gallery export is used.

These are implementation claims based on source review only. The device probe and a real clip test are still mandatory. In particular, do not infer that the QNN runtime, RGBA decoder surface, encoder PTS override, or compressed audio mux works on the target phone until it is built and tested there.


## Branch verification boundary

The current integration branch contains the source implementation and focused unit tests for tile geometry and Results storage. **No Gradle build, unit-test run, APK installation, or real video test has been performed.** GitHub Actions remain disabled. The next device-dependent checkpoint is still the previously added `upscaler_qnn_probe`; once a desktop is available, build/install the debug APK and run that probe before trusting either built-in model. After it passes, test a short SDR clip first, then audio-bearing/VFR/rotated clips, cancellation, restart recovery, and sustained heat.

## Model-license evidence refreshed 2026-11-22

- The `amd/realesrgan-x4plus-anime-6b` Hugging Face model card identifies the original Real-ESRGAN anime checkpoint as BSD-3-Clause and attributes it to Xintao Wang / the Real-ESRGAN authors. This is useful supporting evidence for the source weights, but does not itself state the licence of xororz's QNN-converted `.bin` files. The user's direct upstream licensing email remains the stronger confirmation route.
- The exact source checkpoint `Kim2091/UltraSharpV2/4x-UltraSharpV2_Lite.pth` is tagged `cc-by-nc-sa-4.0` on Hugging Face. Therefore the built-in `upscaler_realistic` cannot be treated as BSD-3-Clause. Its corresponding QNN-converted binary is hosted under `xororz/upscaler`, whose visible repository tree contains the model folders and `.gitattributes` but no model card or explicit licence file. Do not assume the converted binary has a separate permissive licence; retain provenance and treat the upstream model's CC BY-NC-SA terms as the conservative baseline until the publisher clarifies the converted artifact's terms.
- The `xororz/upscaler` file listing does not itself declare a licence for either QNN binary. Reusing an already installed binary without bundling or redistributing it keeps our implementation away from copying model files, but it does not erase the model's use conditions. Before any public release, preserve attribution and review the NC/SA obligations against the app's actual distribution and use; do not claim blanket commercial rights for either built-in model.


## Read-only QNN compatibility probe

A headless harness op, `upscaler_qnn_probe`, now checks each installed built-in
upscaler using the existing `UpscalerCatalog.pathFor` resolver and in-process
`QnnRunner`. It acquires the app-wide `RunLock`, runs on `Dispatchers.IO`,
checks the actual loaded graph's single input/output, expected NCHW dimensions
(1×3×192×192 → 1×3×768×768), and float32 tensor types, then executes one
zero-filled synthetic tile and verifies output length and finiteness. It does
not download, rewrite, copy, or delete model files, and releases all QNN handles
in `finally`. A model is skipped if it is not installed; failures are reported
per model so the other built-in can still be checked.

This is a plumbing/compatibility probe, not an image-quality test. A PASS proves
that the installed context loads and executes through this wrapper on the
device; it does not yet validate RGB preprocessing, tile blending, visual
quality, sustained thermals, or video/audio handling. Do not start video-pipeline
implementation until this probe has been built and run on the target device.
