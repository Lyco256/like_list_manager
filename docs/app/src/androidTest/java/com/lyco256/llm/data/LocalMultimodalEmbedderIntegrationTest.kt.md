# `LocalMultimodalEmbedderIntegrationTest.kt`

integration buildの隔離パッケージ上で、実際のJapanese CLIP q4f16 text／vision ONNX Runtime CPU sessionを実行します。text-only／image-onlyの遅延初期化、session再利用、並行要求の直列化、close後拒否、bad input、専用資産の破損復旧、Bitmapの黒背景センターパッド、256次元finite・L2 normalized出力を確認します。テスト中はRoom、画像保存、Undo、派生検索DBへ書き込まず、前後snapshotで不変性も確認します。類似度、検索品質、実データの視覚評価は対象外です。
