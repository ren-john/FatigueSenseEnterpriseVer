# MediaPipe face and landmark detection

Face detection and landmarks use Google's **MediaPipe Face Landmarker**, with
`com.google.mediapipe:tasks-vision:1.0.0` and the bundled `face_landmarker.task`.
There is no custom-model profile switch. Face Setup shows the active model.

MediaPipe runs on the complete camera frame. Its bundled BlazeFace detector
finds faces, then the face mesh model locates eye/lip points and produces facial
transformation matrices for head pose. ML Kit and its face-detection dependency
have been removed. Up to three faces are evaluated; the largest mesh bounding box
selects the monitored face, with landmarks and pose taken from that same result.
MediaPipe runs synchronously on CPU in IMAGE mode. Frames stay on the device;
no runtime model download is required. A private bitmap copy prevents inference
cleanup from recycling the preview frame.

`MediaPipeLandmarker.kt` runs inference; `LandmarkModel.kt` selects 32 eye/lip
points from the face mesh and keeps the existing six-point EAR and inner-lip MAR
formulas. Displayed points 0–5 are the right eye, 6–11 the left eye, 12–23 the outer
lip, and 24–31 the inner lip. Normalized full-frame coordinates are converted to
original image pixels before ratios are calculated, preserving aspect ratio on rectangular frames. A virtual
square reference around the face keeps the established geometry validity limits.

Head pose is derived from MediaPipe's column-major canonical-to-camera transform.
Turned/clipped faces can continue monitoring qualified points, while baseline
collection pauses. A missing/invalid pose also pauses calibration. Empty MediaPipe
results are no-face observations for absence tracking; native errors instead reset
alert streaks and publish an inference-error result without counting as absence.

## Calibration

Baseline EAR, adaptive mode, and manual EAR/MAR settings use separate
`*_mediapipe_v1` preference keys. Defaults are 0.22 EAR, 0.60 MAR, and adaptive
mode enabled. Custom-model settings remain stored but are not reused. Complete
Face Setup calibration and validate eye closure/yawn thresholds on the target
camera before relying on alerts. Existing logs and the alert frame count are shared.

## Model provenance

- [Official Android integration guide](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android)
- [Official model, float16 version 1](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task)
- SHA-256: `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`
- [MediaPipe mesh topology](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/python/solutions/face_mesh_connections.py)

The retired custom `.tflite` assets are kept in `docs/legacy-models/` for reference
and are not bundled in the APK. Their interpreter and GPU dependencies have been
removed. Returning to a custom model requires an explicit implementation change.

## Validation

With JDK 17 or 21 and Android SDK 36:

```bash
bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

Unit tests cover point mapping, full-frame coordinate conversion, EAR/MAR geometry,
head-pose matrix conversion, invalid pose/calibration gates, invalid meshes, eye
selection, and calibration-key isolation. With a connected
test device, run `bash ./gradlew :app:connectedDebugAndroidTest` to check that the
official model detects the retained real face fixture, produces boxes/pose and
valid eye/mouth measurements, preserves input bitmaps, and forwards blank frames
to absence tracking.
Real ESP streaming, head turns, blinking/yawning, latency, and thermal behavior
still need phone/camera validation.
