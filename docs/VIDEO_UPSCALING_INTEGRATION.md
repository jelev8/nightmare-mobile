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
