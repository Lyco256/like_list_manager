# `ppocr-sdk`

Local Android library containing the official PaddleOCR Android SDK integration required by the app.

It packages only the PP-OCRv6 small and medium detector/recognizer ONNX assets and their matching recognition configs. ONNX Runtime and the 16KB-compatible OpenCV AAR are resolved by the module. The SDK implementation and licenses are tracked with provenance in `ppocr-sdk/UPSTREAM.md` and `ppocr-sdk/LICENSE`.

The app-facing quality-mode mapping and common-result conversion live in `app/src/main/java/com/lyco256/llm/data/PaddleOcrTextRecognizer.kt`; this module does not know about the product's `高速` / `高精度` labels.
