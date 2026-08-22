# `OcrTextRecognizer.kt`

Engine-independent OCR result contract and quality-mode boundary.

## Responsibilities

- Defines `OcrQualityMode.FAST` (`高速`) and `OcrQualityMode.ACCURATE` (`高精度`).
- Defines the common `OcrPoint`, four-point `OcrPolygon`, `OcrTextRegion`, and `OcrRecognitionResult` types.
- Exposes `OcrTextGateway` and `FakeOcrTextGateway` without depending on a concrete OCR engine.

`OcrTextGateway.recognize()` receives only the product quality mode. Mapping that mode to PP-OCRv6 model assets is isolated in `PaddleOcrTextRecognizer.kt`.

The common result keeps image width/height, formatted full text, ordered regions, optional confidence, and source-image four-point polygons. Polygon normalization rejects non-finite coordinates without dropping the recognized text. Structured results remain in the active OCR session and are not persisted here.
