# `run-safe-ocr-visual-check.cmd`

`run-safe-integration-check.cmd` が成功した直後に、許可済みtest packageの外部filesへ生成されたOCR8 visual smoke PNGだけをホストへ取得する薄いラッパーです。ADBのhardware serialを照合し、同一実機の重複wireless endpointを整理してから読み取り専用の`adb pull`を行います。本番packageのDB・画像・Preferences、test packageの状態初期化、uninstallは行いません。

取得先は `build/ocr8-visual/<timestamp>/` です。3枚以上のPNGを確認できた場合だけ`Success`を出力します。
