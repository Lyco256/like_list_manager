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

## PP-OCRv6 model assets

Only extracted inference assets are packaged. The downloaded `.tar` archives are not retained in the repository or APK.

| Purpose | Official archive | Archive SHA-256 | Packaged file | Packaged file SHA-256 |
| --- | --- | --- | --- | --- |
| small detector | `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_det_onnx_infer.tar` | `d218f6fbf0f1c23d2161bd6ac7f5eaa6104fa89955c09290497e31008e2618e4` | `src/main/assets/models/small/det/inference.onnx` | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` |
| small recognizer | `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_rec_onnx_infer.tar` | `d267ab077a44a0eedb1ea8f8c542d263f211de8e9d7a029bf9fcfff7e5a88fb1` | `src/main/assets/models/small/rec/inference.onnx` | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` |
| small recognition config | same recognizer archive | same as above | `src/main/assets/models/small/rec/inference.yml` | `ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1` |
| medium detector | `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_medium_det_onnx_infer.tar` | `c5adb0b15de1b1838934eba1dd72e7529e7d80132216c6ee6d26eba6fa054fcf` | `src/main/assets/models/medium/det/inference.onnx` | `eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1` |
| medium recognizer | `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_medium_rec_onnx_infer.tar` | `d8cc46c7163c83a151aef8fce5856b965860df90a875029216d605b0f607eaec` | `src/main/assets/models/medium/rec/inference.onnx` | `9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba2` |
| medium recognition config | same recognizer archive | same as above | `src/main/assets/models/medium/rec/inference.yml` | `991b700facf5b50a7de193468207d5f4255b538dde0d312ae3b7c7a9b6873129` |
| recognizer config | same recognizer archive | same as above | `src/main/assets/models/rec/inference.yml` | `ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1` |
