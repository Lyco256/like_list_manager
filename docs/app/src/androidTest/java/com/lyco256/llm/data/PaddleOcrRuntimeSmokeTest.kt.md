# `PaddleOcrRuntimeSmokeTest.kt`

Isolated-device smoke test for the real bundled PaddleOCR gateway. It runs the same bitmap through `高速` (small), `高精度` (medium), and `高速` again, proving offline model initialization, variant release/reload, common result dimensions, and polygon safety without using production data.
