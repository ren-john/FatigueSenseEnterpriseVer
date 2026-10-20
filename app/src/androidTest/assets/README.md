Landmark model parity fixture
============================

landmark32_input.bin: one real validation crop from the coordinate32 notebook,
RGB float32 / 255, little endian, contiguous [1,3,192,192].

landmark32_expected.bin: ONNX Runtime CPU prediction for that crop,
little-endian float32 [1,32,2], normalized crop coordinates.

The instrumented test compares the bundled TFLite model against this ONNX
reference at absolute tolerance 1e-4. These files are test assets only.

Model SHA-256:
680f3bf04f1f6c4ebf2445a6868294cd31e02d0d0073336c5d91ba5f640854ef
