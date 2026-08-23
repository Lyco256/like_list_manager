# `PaddleOcrTextRecognizer.kt`

Production OCR adapter for the bundled PaddleOCR Android SDK.

`OcrQualityMode.FAST` maps to the small detector/recognizer/config bundle and `ACCURATE` maps to the medium bundle. The adapter converts both variants through the same `OcrRecognitionResult` path, preserving text, confidence, source-image four-point polygons, and bitmap dimensions.

`PaddleOcrEnginePool` owns exactly one active ONNX engine. Requests for the same variant reuse it; switching variants releases the old engine before creating the new one. A `Mutex` makes release, initialization, and inference exclusive, while sequential assets in one Repository request reuse the current engine. No tile path or comparison metadata exists in the production adapter.
