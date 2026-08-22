
# `OcrTextRecognizer.kt`

OCR text gateway and formatting helpers.

## Responsibilities

- Wraps ML Kit Japanese text recognition behind `OcrTextGateway`.
- Provides a fake gateway for tests.
- Formats ML Kit text into a stable line/block order for storage and assertions.

## 2026-08 structured OCR boundary

`OcrTextGateway.recognize()` returns the engine-independent `OcrRecognitionResult`. It keeps the source image width/height, the formatted full text, and ordered line-level `OcrTextRegion` values. Each region carries its text, an optional four-point source-coordinate `OcrPolygon`, optional confidence, and the separator that precedes it in the formatted text.

`MlKitOcrTextGateway` converts ML Kit types at this boundary. Four corner points are preferred; when unavailable, a bounding box is converted to a four-point polygon. Lines without position data remain as text-only regions, and unavailable confidence remains `null`. Blank lines are omitted as regions without changing the existing full-text formatting.

`FakeOcrTextGateway` accepts a `(Bitmap) -> OcrRecognitionResult` provider, allowing tests to inject arbitrary dimensions, line order, separators, polygons, and confidence values. `ClipRepository.detectOcrText()` carries this image-level result into the ordered post-level `OcrPostRecognitionResult`; no structured result is persisted.

## 2026-08 OCR engine comparison

`OcrEngine` keeps ML Kit for the legacy non-comparison route but exposes only PP-OCRv6 small, PP-OCRv6 medium, and PP-OCRv6 medium + tile in the comparison selector. `OcrDetectionMetadata` records the engine, elapsed recognition time, tile count, and tile failure count for the last successful request. `OcrGatewayRecognition` carries these non-persistent comparison details alongside the common result. Polygon normalization rejects non-finite coordinates as invalid while retaining the recognized text through the engine adapter.
