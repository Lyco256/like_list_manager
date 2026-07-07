# `OcrTextRecognizerTest.kt`

`OcrTextRecognizer.kt` の OCR 文字列整形ロジックを JVM で検証する単体テストです。

## 役割

- 画像の縦横に応じて block / line の並び順が変わることを確認します。
- bounding box がない行や block でも、ML Kit 由来の fallback 文字列が壊れないことを確認します。
- OCR 結果の連結ルールが将来の変更で崩れないようにします。
