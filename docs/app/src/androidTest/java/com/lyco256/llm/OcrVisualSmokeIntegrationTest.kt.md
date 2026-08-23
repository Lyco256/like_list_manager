# `OcrVisualSmokeIntegrationTest.kt`

実機Compose上で決定的なOCR8表示状態を描画するvisual smokeです。横書き2×2の独立group、縦書き2列の文章group、複数assetの全文タップによるページ移動・polygon選択・region編集を確認し、各状態をtest package外部filesへPNGとして保存します。PNGは`run-safe-ocr-visual-check.cmd`でホストへ読み取り専用取得し、目視確認に使います。
