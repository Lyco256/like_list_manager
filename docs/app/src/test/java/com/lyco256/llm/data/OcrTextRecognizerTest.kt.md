# `OcrTextRecognizerTest.kt`

`OcrTextRecognizer.kt` の OCR 文字列整形ロジックを JVM で検証する単体テストです。

## 役割

- 画像の縦横に応じて block / line の並び順が変わることを確認します。
- bounding box がない行や block でも、ML Kit 由来の fallback 文字列が壊れないことを確認します。
- OCR 結果の連結ルールが将来の変更で崩れないようにします。

## 2026-08 structured OCR boundary

- 構造化領域の読み順、行区切り、block間空行が既存全文と一致することを確認します。
- corner pointsの4点保持、bounding boxからの4点polygon fallback、位置なし行、confidence、元画像寸法を確認します。
- 構造化blockがない場合も全文fallbackを維持することを確認します。
