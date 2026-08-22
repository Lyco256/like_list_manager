# PaddleOCR Android SDK provenance

This local Android library is copied from the official PaddlePaddle/PaddleOCR repository:

- Repository: https://github.com/PaddlePaddle/PaddleOCR
- Commit: `77ddbafccdee5020368c16c6960e6ff51e40b971`
- Source path: `deploy/ppocr-android/ppocr-sdk`
- Upstream commit title: `[Feat] Add PP-OCRv6 Android Demo (#18121)`
- License: Apache License 2.0; the upstream root license is retained as `LICENSE`.

The SDK implementation source and upstream test fixtures are kept from that revision. The local Gradle build file contains project-integration changes for this repository's plugin/version catalog, SDK levels, and the 16 KB-compatible OpenCV AAR described below. App-facing adapters and feature integration live outside this module.

## Android 16 KB page compatibility

The official demo dependency remains OpenCV Android `4.5.3`. Its Maven prebuilt arm64 `libopencv_java4.so` and `libc++_shared.so` have 4 KB ELF LOAD alignment and fail to load on the target 16 KB page-size device. Manifest compatibility mode did not make that prebuilt usable.

The local AAR therefore preserves OpenCV version 4.5.3 and the original Java API/other ABI entries, but replaces the arm64 native pair with a build from the official OpenCV 4.5.3 source tag and Android NDK r28. Build inputs, linker flags, licenses, and SHA-256 values are fixed in `libs/README.md`. An isolated-device runtime smoke test loads this exact pair and runs the bundled detector model before release.

Android guidance: https://developer.android.com/guide/practices/page-sizes

## PP-OCRv6 small model assets

Only extracted inference assets are packaged. The downloaded `.tar` archives are not retained in the repository or APK.

| Purpose | Official archive | Archive SHA-256 | Packaged file | Packaged file SHA-256 |
| --- | --- | --- | --- | --- |
| detector | `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_det_onnx_infer.tar` | `d218f6fbf0f1c23d2161bd6ac7f5eaa6104fa89955c09290497e31008e2618e4` | `src/main/assets/models/det/inference.onnx` | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` |
| recognizer | `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_rec_onnx_infer.tar` | `d267ab077a44a0eedb1ea8f8c542d263f211de8e9d7a029bf9fcfff7e5a88fb1` | `src/main/assets/models/rec/inference.onnx` | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` |
| recognizer config | same recognizer archive | same as above | `src/main/assets/models/rec/inference.yml` | `ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1` |
