MediaPipe inference fixture
==========================

landmark32_input.bin is a retained real validation face crop from the former
coordinate32 notebook: RGB float32 /255, little endian, planar [1,3,192,192].
The instrumentation test reconstructs the bitmap, runs the bundled official
MediaPipe model, checks its selected 32 eye/lip points, and rejects a blank image.

landmark32_expected.bin is the archived custom-model prediction and is no longer
used by the MediaPipe test. It is retained only as historical reference.
