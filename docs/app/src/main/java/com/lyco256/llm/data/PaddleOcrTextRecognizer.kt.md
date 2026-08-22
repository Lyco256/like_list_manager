# `PaddleOcrTextRecognizer.kt`

公式PaddleOCR Android SDKをアプリ共通の`OcrTextGateway`へ接続します。

## 役割

- `Dispatchers.Default`上でsmall/mediumのSDK engineをvariant単位に遅延生成し、`PaddleOcrEnginePool`で再利用します。同一variantの連続再検出は同じengineを使い、variant切替時は旧engineを解放して実機メモリを保護します。Mutexで同一gateway内の推論を直列化し、medium + tileもmedium engineを共有します。
- APK内の`models/small/...`と`models/medium/...`のdetector／recognizer／recognition configを使い、実行時downloadを行いません。
- SDKが返すreading orderを保ち、画像寸法、text、confidence、bitmap座標の4点polygonを共通結果へ変換します。
- 空文字は除外し、不正polygonは位置なしregionとして文字を残します。
- medium + tileはmedium全体OCRを残したうえで、最大1536px・256px overlapのタイルを逐次処理し、長辺2048pxまで高品質拡大します。タイル候補は元画像座標へ戻し、内部境界32px以内のedge-clipped判定と、IoU／面積比／中心距離の定数化した条件で入力順に依存しない重複統合を行います。タイル失敗は全体OCR成功時に部分失敗metadataとして返します。
- `close()`で公式SDKの`release()`を呼び、共有engineを破棄します。

OpenCV Android 4.5.3の元prebuilt arm64 ELFは4KB alignmentのため、公式4.5.3 sourceをNDK r28で再buildした16KB-aligned arm64 libraryを使用します。由来とhashは`ppocr-sdk/libs/README.md`に固定します。隔離実機の`PaddleOcrRuntimeSmokeTest`がOpenCV loadからmodel推論・releaseまでをfakeなしで確認します。

公式sourceとモデルの由来・hashは`ppocr-sdk/UPSTREAM.md`を参照してください。
