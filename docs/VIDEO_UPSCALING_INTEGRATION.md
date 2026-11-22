# Video Upscaling Integration

This document records the implementation boundary for adding video upscaling to Nightmare Mobile. Work is being done on the `video-upscaling-integration` branch; the fork's `main` and the upstream repository must remain unchanged.

## Required user experience

- A user opens a video already saved in the Results tab and can choose **Upscale video** from the existing Results/viewer actions.
- The source MP4 remains untouched.
- The completed upscaled MP4 is saved under Nightmare's existing app-private results directory and appears as a separate Results item with its own poster.
- No MediaStore/gallery export is performed automatically.
- Cancellation or failure removes incomplete temporary/output files and leaves the source and existing Results entries intact.
- Audio from the source is preserved in the finished MP4. Audio-bearing source-video testing is a release gate.

## Confirmed existing architecture

- `ResultsStore` owns app-private result files and metadata. A video result is represented by a poster PNG, JSON metadata, and an MP4 at the same result ID.
- `Result.videoPath` is populated from the MP4 actually present in the Results directory.
- `ResultsScreen` and `ResultViewer` already share the existing image-upscale picker/callback, but the current upscale refusal explicitly rejects video results.
- `VideoWriter.encode(...)` writes frames to a temporary MP4 and does not itself publish to the gallery. `VideoWriter.write(...)` calls `publish(...)`, which exports to MediaStore and must not be used for this feature.
- Nightmare's `QnnRunner` executes named tensors against QNN context binaries. Its model input/output tensor contract must be verified before connecting frame tiles to it.

## Integration constraints

- Do not use the standalone app's LiteRT `.tflite` runner as if it were a drop-in for Nightmare's QNN context-binary runner. They are different model artifacts and APIs.
- Do not bundle a second Qualcomm runtime or copy Nightmare's app UI into the standalone project.
- Reuse Nightmare's existing Results storage, result viewer, model catalogue, QNN runtime, job/progress/cancellation conventions, and device safeguards.
- Never call the gallery publishing method for generated video output.

## Implementation sequence

1. Verify the installed QNN upscaler's tensor names, shapes, data types, RGB normalization/range, tile dimensions, and output scale using the existing QNN description/runtime APIs.
2. Build a cancellable video frame pipeline that decodes a Results MP4, runs tiles through the verified QNN contract, and encodes the upscaled frames to a private temporary MP4.
3. Copy/remux the source audio track into the completed MP4 without re-encoding where the source format permits; fail clearly rather than silently dropping audio.
4. Add an atomic ResultsStore operation that creates a new result ID, poster, MP4, and metadata, preserving the source result and recording its provenance. Roll back partial files on failure.
5. Add a video-specific action in the existing Results row/viewer. Keep the image-upscale path unchanged and route clips to the video job.
6. Test silent and audio-bearing clips, cancellation, failure cleanup, repeated runs, app restart, private-storage placement, and original preservation before any public release.

## Licensing and release gate

The existing Nightmare QNN model binary's redistribution terms and the Real-ESRGAN weights/conversions require separate review. Public availability or Nightmare's existing use is not, by itself, redistribution permission. Do not publish model binaries or a public release until the applicable licensing is clear and audio preservation has been tested on an audio-bearing source.