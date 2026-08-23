# `OcrSessionDialogComposeTest.kt`

OCRセッションUIの回帰テストです。

OCR3では小型AlertDialogではなく全画面ビューア、asset IDページ、Fit表示、1倍時の左右スワイプ、asset IDに一致する構造化polygon表示を検証します。

- 保存callback完了前はダイアログを閉じず、保存失敗時はdraftとエラーを表示したまま再試行できることを確認します。
- 保存中は編集、再検出、キャンセルを無効化し、成功callback後だけ閉じることを確認します。
- 手動編集後のキャンセルと再オープンで、保存済み`ocrText`から再開することを確認します。
- classifiedカードとmedia grid投稿ダイアログの両方が共通の`OcrSessionDialog`を使うことを確認します。
- 再検出結果から現在assetが消えた場合に、別の有効なpreview assetへ移動することを確認します。
- 検出中でも直前のstructured polygonと現在画像を維持したまま進捗表示することを確認します。
- OCR全文のregion文字列タップが同じ`selectedRegionKey`経路でpolygonを選択し、既存のregion編集TextFieldへ接続することを確認します。
- OCR全文の別asset文字列タップが対象ページへ移動して対応polygonを選択し、同じregion編集TextFieldへ接続することを確認します。
