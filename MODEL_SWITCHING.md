# Switch FatigueSense models

Use either prompt in Codex:

- Switch FatigueSense to the old 12-point model using MODEL_SWITCHING.md, then build and test it.
- Switch FatigueSense to the new 32-point model using MODEL_SWITCHING.md, then build and test it.

## Single switch

In `app/src/main/java/com/example/fatiguesense/ml/ModelProfile.kt`, change only `ACTIVE`:

```kotlin
val ACTIVE = Profile.LEGACY_12PT
// or:
val ACTIVE = Profile.COORDINATES_32PT
```

Rebuild and reinstall the APK, then restart the app. This is a build-time switch, not a live toggle. Both assets remain bundled. Face Setup shows the active model.

| Profile | Old 12-point | New 32-point |
|---|---|---|
| Asset | landmark_model_float32(ver3).tflite | coordinates32_android_fp32_float32.tflite |
| Input | 128x128 NHWC RGB /255 | 192x192 NCHW RGB /255 |
| Output | [1,24] | [1,32,2] |
| Crop | Original square crop, 10% downward offset, clipped and bitmap-resized | 1.25x centered affine crop with black padding |
| EAR | Each eye: top-bottom distance / corner distance; average both eyes | Six-point EAR with geometry-based eye selection |
| MAR | Mouth top-bottom distance / corner distance | Three inner-lip distances / (3 x inner width) |
| Turned/clipped faces | Skip inference (15-degree / 2% margin gate) | Continue inference, pause baseline collection |

The legacy implementation is in `LegacyLandmarkModel.kt`, recovered from commit c310954; the newer implementation remains in `LandmarkModel.kt`. The legacy gate resets alert streaks when rejecting a frame. Shared alert logic and connection/shutdown repairs remain current. Both profiles use four CPU threads; this does not restore the old GPU delegate.

Each profile retains its own baseline preference. Manual EAR/MAR cutoffs and adaptive mode now have separate profile keys and initially use defaults (0.22 EAR, 0.60 MAR, adaptive enabled). Recalibrate for a fresh comparison and record the chosen cutoffs. Alert frame count remains shared. Existing event logs are shared and do not identify the model, so record the model and test time separately.

## Validation after switching

Run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline` (JAVA_HOME must point to Android Studio's jbr). With a connected Android device, run `gradlew.bat :app:connectedDebugAndroidTest` to verify both bundled tensor contracts and the existing 32-point reference output. Install the rebuilt debug APK and check Face Setup's ACTIVE MODEL before testing. Device inference, thresholds, and thermal behavior require an actual phone test.

Do not replace entire analyzer/manager files with Git versions: that would discard unrelated fixes. The profile selects the model, preprocessing, formulas, calibration keys, and UI map together.
