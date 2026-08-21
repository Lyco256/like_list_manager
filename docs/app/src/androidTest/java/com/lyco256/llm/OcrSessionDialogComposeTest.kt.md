# `OcrSessionDialogComposeTest.kt`

OCRセッションUIの回帰テストです。

OCR3では小型AlertDialogではなく全画面ビューア、asset IDページ、Fit表示、1倍時の左右スワイプ、asset IDに一致する構造化polygon表示を検証します。

- 保存callback完了前はダイアログを閉じず、保存失敗時はdraftとエラーを表示したまま再試行できることを確認します。
- 保存中は編集、再検出、キャンセルを無効化し、成功callback後だけ閉じることを確認します。
- 手動編集後のキャンセルと再オープンで、保存済み`ocrText`から再開することを確認します。
- classifiedカードとmedia grid投稿ダイアログの両方が共通の`OcrSessionDialog`を使うことを確認します。
