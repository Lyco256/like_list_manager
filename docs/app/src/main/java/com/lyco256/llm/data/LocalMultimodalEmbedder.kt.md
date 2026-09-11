# `LocalMultimodalEmbedder.kt`

## 役割

Japanese CLIPのtext／image embeddingを端末内だけで実行するruntimeです。`ImageEmbedder`としてimage同期へ注入されますが、正本Room、`DerivedSearchStorage`、semantic同期、検索UI、OCRを直接参照しません。text APIは既存どおり独立しており、呼び出し側が必要なmodalityだけを遅延初期化できます。

## 固定資産と前処理

- repository: `AUXOUT-TEAM/clip-japanese-base-v2-onnx`
- revision: `c924148be2e25b6e4d98e66d8dc1768adb72d079`
- assets: `onnx/text_model_q4f16.onnx`、`onnx/vision_model_q4f16.onnx`、`tokenizer.json`
- asset bundle: 144,627,423 bytes（137.927 MiB）
- output: 256 float、finite・正のL2 normを検証してL2 normalize

Gradleは固定revisionのURLから取得した3ファイルをSHA-256検証し、revision別cacheからgenerated assetへコピーします。実行時は`LocalRuntimeAssetInstaller`を通じて`noBackupFilesDir/multimodal_embedding/japanese_clip/<revision>/`へ各ファイルをstreaming copyし、metadata・byte size・SHA-256を確認して再利用します。中断時の`.partial`は専用ディレクトリ内だけを削除します。

textはDJL tokenizerへ`addSpecialTokens=false`で渡し、最大76 raw tokenをCLS id `4`の前に置いて最大77 tokenの`input_ids`、`attention_mask`、`position_ids`を作ります。imageは入力Bitmapを変更せず、alphaを黒へ合成し、224×224の黒キャンバスへ長辺224のaspect-fitで中央配置して、CHWのOpenAI CLIP mean/stdで正規化します。

## lifecycle / safety

`LocalMultimodalEmbedder`生成時は資産・tokenizer・ONNX sessionを作りません。最初のtext入力でtokenizer＋text q4f16 CPU session、最初のimage入力でvision q4f16 CPU sessionだけを作り、以後同じsessionを再利用します。推論は`Dispatchers.IO`上で専用lockにより直列化し、要求を無制限にキューイングしません。blank／recycled／不正画像はruntime初期化前に拒否し、closeは冪等、close後は拒否します。共有`OrtEnvironment`はcloseしません。

## テスト

- `LocalMultimodalEmbedderTest`: 256次元出力のfinite／L2検証、token input、metadata／SHA再利用、単一ファイル復旧、partial cleanup、copy失敗をfake assetで確認します。
- `LocalMultimodalEmbedderIntegrationTest`: 隔離された`com.lyco256.llm.test`で実際のq4f16 ONNX Runtime CPU text／vision推論、lazy/reuse、同時呼び出し、bad input、close、asset復旧、画像前処理、正本DB・画像・Undo・派生DBの不変性を確認します。類似度、検索品質、実データ画像の目視評価は行いません。

`ImageEmbeddingSynchronizer`は同じprocessのこのruntime instanceを`ImageEmbedder`として共有し、vision sessionをassetごとに作り直しません。
