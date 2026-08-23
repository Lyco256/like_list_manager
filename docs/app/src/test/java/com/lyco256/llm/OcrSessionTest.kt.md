# `OcrSessionTest.kt`

`OcrSessionController`のJVM回帰テストです。

- 保存済みOCRの開始分岐と空OCRの自動検出1回を確認します。
- 自動検出失敗、再検出成功／失敗、検出中の重複要求を確認します。
- 古い成功／失敗要求、clip不一致、dismiss済みセッション、同じclipの再オープンを無効化します。
- 自動OCR後・手動編集後・再検出後のキャンセルでは保存callbackが呼ばれないことを確認します。
- 保存成功／失敗callback、構造化結果保持、保存中の二重操作・dismiss、古い保存結果の無視を確認します。
