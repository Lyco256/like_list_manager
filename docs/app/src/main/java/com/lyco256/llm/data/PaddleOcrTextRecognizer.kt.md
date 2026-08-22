# `PaddleOcrTextRecognizer.kt`

公式PaddleOCR Android SDKをアプリ共通の`OcrTextGateway`へ接続します。

## 役割

- `Dispatchers.Default`上で初回だけSDK engineを生成し、Mutexで直列化して再利用します。
- APK内の`models/det/inference.onnx`と`models/rec/inference.onnx`／`inference.yml`を使い、実行時downloadを行いません。
- SDKが返すreading orderを保ち、画像寸法、text、confidence、bitmap座標の4点polygonを共通結果へ変換します。
- 空文字は除外し、不正polygonは位置なしregionとして文字を残します。
- `close()`で公式SDKの`release()`を呼び、共有engineを破棄します。

OpenCV Android 4.5.3の元prebuilt arm64 ELFは4KB alignmentのため、公式4.5.3 sourceをNDK r28で再buildした16KB-aligned arm64 libraryを使用します。由来とhashは`ppocr-sdk/libs/README.md`に固定します。隔離実機の`PaddleOcrRuntimeSmokeTest`がOpenCV loadからmodel推論・releaseまでをfakeなしで確認します。

公式sourceとモデルの由来・hashは`ppocr-sdk/UPSTREAM.md`を参照してください。
