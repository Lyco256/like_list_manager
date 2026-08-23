# `run-safe-ocr-visual-check.ps1`

許可リストの`testDeviceSerial`と接続端末の`ro.serialno`を照合し、直前に成功したOCR8 visual smoke instrumentationがtest package外部filesへ生成したPNGを`build/ocr8-visual/<timestamp>/`へ読み取り専用で取得します。既存の実機統合・安全インストール入口の代替ではなく、画面キャプチャをホストで目視確認するための追加入口です。

接続端末がない場合、統合テストを自動実行せず停止します。デバイス上のファイル削除、アプリ状態変更、package操作は行いません。
