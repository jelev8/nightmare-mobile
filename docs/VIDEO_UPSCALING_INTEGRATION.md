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
- `ResultsScreen` and the fullscreen result viewer already share the existing image-upscale picker/callback, but the current upscale refusal explicitly rejects video results.
- `VideoWriter.encode(...)` writes frames to a file and does not itself publish to the gallery. `VideoWriter.write(...)` calls `publish(...)`, which exports to MediaStore and must not be used for this feature.
- `QnnRunner` and `NativeQnn` provide an in-process QNN route. `QnnRunner.handleOf(File)` caches a loaded context by absolute path; `describe(File)` exposes the binary's own tensor names, dimensions, and dtypes; `run(File, inputs)` executes named inputs and returns named outputs. Native execution is blocking and must run off the main thread.
- The existing image-upscale route is different: `Ops.upscale` sends raw RGB to the app's local backend `/upscale` endpoint, which creates and frees the QNN model for each request. Calling that endpoint once per video frame would repeatedly load the model and is not the preferred video design.
- The existing `AudioMux.mux(...)` helper is for a content URI and returns false on failure; it is not a safe drop-in for a private Results file when audio preservation is mandatory.
- `ResultsStore.keep(...)` is designed for user-kept generated content and intentionally treats a failed source-video copy as non-fatal. A video-upscale result needs its own atomic commit operation; do not rely on that best-effort copy behavior.
- `DriverFrames` uses `MediaMetadataRetriever` for batched frame reads in motion-transfer features. Its approximate frame-index/time mapping is not yet validated for preserving arbitrary source-video timestamps and duration.

## Model/runtime investigation — current findings

- The built-in catalogue exposes **Anime 4x** (RealESRGAN x4plus anime 6B) and **Realistic 4x** (4x UltraSharp V2 Lite) as existing QNN context binaries. Their existing installation and path-resolution code should remain the source of truth.
- The upstream QNN upscaler implementation documents a single graph with one float input shaped `1x3x192x192` and one float output shaped `1x3x768x768`. Its image tiler converts RGB bytes to normalized `[0,1]` CHW floats, uses 192-pixel tiles with 12-pixel input overlap, and blends 768-pixel output tiles.
- This upstream implementation is useful evidence about the intended model contract, but it does **not** prove that every installed binary works through Nightmare's in-process `NativeQnn` wrapper. The actual installed binary must be described and checked at runtime (graph count, input/output names, shapes, dtypes, and element counts), and a known test tile must execute successfully on-device before the pipeline is implemented around those assumptions.
- The in-process route is the leading design because it can keep the existing downloaded context resident across many frame tiles. This remains a hypothesis until the on-device metadata and execution checks pass.
- Do not use the standalone app's LiteRT `.tflite` runner as if it were a drop-in for Nightmare's QNN context-binary runner. They are different model artifacts and APIs.

## Implementation sequence

1. Add or use a diagnostic path that resolves an already-installed built-in model through `UpscalerCatalog.pathFor`, loads it with `QnnRunner`, and reports its actual tensor metadata. Do not download or convert anything for this check.
2. Verify that the metadata matches the expected single-graph, one-input/one-output tile contract and execute a controlled tile. Reject incompatible binaries with a clear error; do not guess tensor names, shapes, dtypes, or normalization.
3. Design frame decoding and encoding around source timestamps, rotation, frame rate, variable-frame-rate behavior, and dimensions. Process one frame at a time and enforce a memory/output-size policy before allocating large buffers.
4. Coordinate the in-process QNN job with Nightmare's other NPU work; guarantee context release in every success, failure, and cancellation path. Reuse existing thermal warnings and safeguards where applicable without changing network behavior.
5. Build a cancellable frame pipeline that runs verified tiles through the resident QNN context and encodes to a private temporary MP4.
6. Preserve source audio. Inspect the source tracks and codecs, copy/remux compatible compressed audio without re-encoding where possible, and fail the job clearly if audio cannot be preserved. Never silently publish a silent output from an audio-bearing source.
7. Add an atomic ResultsStore operation that creates a unique new result ID, poster, MP4, and metadata. Verify every write and rename; roll back partial files on failure. Preserve the source result and record the source result ID, selected upscaler, scale, and processing details.
8. Add a video-specific action in the existing Results row/viewer. Keep the image-upscale path unchanged and route clips to the video job.
9. Test silent and audio-bearing clips, variable frame rates if supported, rotation, cancellation, failure cleanup, repeated runs, app restart, private-storage placement, source preservation, and thermal behavior before considering release.

## Licensing and release gate

The initial implementation reuses model files that Nightmare already downloads and uses; it must not create converted model files or redistribute weights. This reduces the scope of model handling but does not itself prove the applicable model licenses permit every use. Preserve existing notices and review the relevant terms before public distribution. Do not publish model binaries or a public release until licensing is clear and audio preservation has been tested on an audio-bearing source.


## Additional source-level constraints confirmed during review

- `NativeQnn.loadLocked` explicitly rejects binaries containing anything other than exactly one graph. `runLocked` also requires every graph input exactly once, matched by its actual tensor name. The runtime diagnostic must therefore verify the graph count and complete input/output contract before any inference; a matching tensor shape alone is not enough.
- The native `g_mu` mutex serializes each load, describe, execute, and free operation across the process. It prevents simultaneous native QNN calls and protects handles, but it does **not** reserve the NPU for an entire multi-frame job: another QNN user can run between tile executions. `QnnRunner` caches handles per runner instance, so the video job must own and release only its own handle. A separate process-wide job coordinator is needed to prevent memory-heavy video generation and saved-video upscaling from overlapping; the existing `VideoSampleNode.inFlight` flag is private to that node and does not coordinate a new Results action.
- `BatteryHeat.warnIfWarm` is reusable for the pre-run warning. `BatteryHeat.watch` pauses through the backend's `/pause` callback, however, and that does not pause direct in-process `NativeQnn` execution. Do not pass a fake backend pause callback. If the existing preference is to be honored, the new job needs a local, cancellable pause at safe frame/tile boundaries, with the same temperature thresholds and hysteresis; otherwise it must clearly retain the warning-only behavior already used by some video paths. This needs review before implementation.
- `VideoWriter.encode(count, …, frameAt)` already supports fetching one frame at a time, but it stamps output frames at a fixed `1/fps` cadence. It is not a timestamp-preserving VFR writer and does not by itself preserve source rotation metadata. A saved-video pipeline must either explicitly normalize source timestamps/rotation into a documented CFR output or refuse unsupported timing/rotation cases; it must not claim exact source timing while using the fixed-cadence overload.
- `AudioMux.mux(...)` is specifically URI-based, seeks the source audio from a supplied offset, and treats no audio or any mux error as a non-fatal `false`. That behavior is intentionally unsuitable for this feature's strict audio requirement. A separate private-file remux operation must first inspect whether the source has an audio track, copy compatible compressed samples to a temporary MP4, validate the muxed result, and fail/clean up if an audio-bearing source cannot be preserved. The source MP4 must never be the mux destination.
- `ResultsStore.keep(...)` requires a workflow and poster bitmap and tolerates a failed video copy. The new result-save method should instead be narrowly scoped to an already completed private MP4 plus poster/metadata, use the same process-wide storage lock, claim a collision-free ID, write temporary files, publish metadata last, and roll back all partial files on failure.
- The native runner's global mutex is a per-call safety mechanism, not a substitute for job-level cancellation, memory budgeting, or thermal policy. All QNN calls remain on a worker dispatcher, and the context must be released in `finally` after the final tile or any exception/cancellation.

- `FrameInterp.Session` is another existing in-process `QnnRunner` consumer; it holds its own IFRNet context (documented in code as roughly 25 MB) for a clip. The native mutex serializes its individual calls against other QNN work, but does not make a whole clip session exclusive. The new feature must not assume the only competing QNN work is `VideoSampleNode`; review all session lifetimes and use a shared job-level gate for the memory-heavy video-generation path before allowing saved-video upscaling to start.


## Existing image-upscaler behavior and video memory implications

- `UpscalerCatalog.pathFor(context, id)` is the supported installed-file resolver. It calls the same spec/path logic used by the existing image-upscale UI, including the Local Dream shared-folder fallback and the selected `ModelStorage` location. A video action must use this resolver (or the exact spec's `file(context)` after checking `installed(context)`), never assemble paths or invoke `install`/`download`.
- The built-in catalogue contains two 4x QNN context binaries: `upscaler_anime` (RealESRGAN x4plus anime 6B) and `upscaler_realistic` (4x UltraSharp V2 Lite). Device build selection is already defined by `DeviceProbe.caps()` and each spec's `buildFor`; the runtime must still validate the actual installed file, not infer its tensor contract from the selected tier.
- The upstream QNN implementation at `xororz/local-dream` tag `v3.0.0-alpha.4`, `app/src/main/cpp/src/Upscaler.hpp`, confirms the real preprocessing and tile driver: RGB HWC bytes are converted to normalized float32 CHW; input tiles are 192x192; the graph returns 768x768 tiles; source tiles overlap by at least 12 pixels; and the output uses a 48-pixel overlap with a 24-pixel linear fade at each edge followed by weighted normalization. If either source edge is below 192, the upstream endpoint first resizes the source so its shorter edge is 192, then resizes the final 4x result back to exactly 4x the original dimensions.
- The image UI's selectable output scales (2x/3x/4x) are implemented by running the native 4x model and resizing its assembled output to the requested scale; `UpscaleNode.MAX_OUT_EDGE` is 4096. A video feature should preserve that user-facing scale convention and edge cap, but cannot blindly copy the upstream full-frame float accumulators: at high-resolution video, a 4x intermediate accumulator can exceed the app's safe heap budget even when the requested final output is only 2x or 3x. Use bounded tile/stripe accumulation or reject the frame before allocating; never risk an OOM crash or silently lower resolution.
- The upstream implementation's full-frame accumulators are `float[3 * outputWidth * outputHeight]` plus a float weight map. For a 1920x1080 source at native 4x, that is approximately 380 MiB + 127 MiB before input, output bitmap, encoder, and runtime memory. This is not acceptable as a default video-frame strategy on Android. Memory budgeting is a release blocker, not a later optimization.
- `RunLock` is the app-process gate used by the canvas and API so they do not overlap work against the shared backend. A Results-originated video upscale must acquire the same gate (and refuse clearly if another run/API job owns it); `VideoSampleNode` additionally has a separate process-wide in-flight guard for its own headless/canvas callers. The C++ backend's own generation mutex is in a different process and does not coordinate with `NativeQnn` directly, so do not treat either native mutex as the whole-app job gate.
