
# `OcrTextRecognizer.kt`

OCR text gateway and formatting helpers.

## Responsibilities

- Wraps ML Kit Japanese text recognition behind `OcrTextGateway`.
- Provides a fake gateway for tests.
- Formats ML Kit text into a stable line/block order for storage and assertions.
