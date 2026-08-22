# `PaddleOcrRuntimeSmokeTest.kt`

隔離実機上でproductionの`PaddleOcrTextGateway`を直接生成し、APK asset内のPP-OCRv6 small modelで白紙Bitmapを1回推論します。

OpenCV native libraryのload、ONNX modelの初期化、detector推論、結果変換、releaseをfakeなしで通し、入力画像寸法が共通結果へ戻ることを確認します。精度採点や比較用datasetは作りません。
