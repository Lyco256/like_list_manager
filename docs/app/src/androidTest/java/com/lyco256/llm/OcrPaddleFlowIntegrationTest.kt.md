# `OcrPaddleFlowIntegrationTest.kt`

Isolated-device UI flow for OCR7. It creates a temporary high-contrast test image, runs the production `PaddleOcrTextGateway` through automatic `高速` detection and `高精度` redetection, edits a detected polygon region, dismisses the session, and verifies that reopening starts a fresh non-persistent session in `高速`. It does not use the production database, images, or credentials.
