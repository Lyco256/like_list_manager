# `OcrSessionTest.kt`

`OcrSessionController`のJVM回帰テストです。

- 保存済みOCRの開始分岐と空OCRの自動検出1回を確認します。
- 構造化結果の成功反映、再検出失敗時のdraft／構造化結果保持を確認します。
- 古い要求、dismiss済みセッション、保存中の二重操作を無効化します。
- 保存成功／失敗callback、保存後だけのclose通知、遅い保存結果の無視を確認します。
