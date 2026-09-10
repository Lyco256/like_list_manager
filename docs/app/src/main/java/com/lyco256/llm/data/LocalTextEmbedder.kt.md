# `LocalTextEmbedder.kt`

## 役割

EmbeddingGemma 300M Q4を端末内だけで実行する独立したtext embedding runtimeです。正本Room DB、`DerivedSearchStorage`、`LexicalIndexSynchronizer`、既存検索、UI、OCRへは接続しません。

## 固定資産

- repository: `onnx-community/embeddinggemma-300m-ONNX`
- revision: `75a84c732f1884df76bec365346230e32f582c82`
- files: `model_q4.onnx`、`model_q4.onnx_data`、`tokenizer.json`
- output: 768 float、返却時にL2 normalize
- maximum token length: 2048

Gradleが固定URLから取得したgenerated assetのmetadataとSHA-256を検証します。実行時は`noBackupFilesDir/text_embedding/embeddinggemma/<revision>/`へ必要なファイルだけをstreaming copyし、同じディレクトリのONNX graphとexternal dataを実ファイルパスでロードします。

## lifecycle / safety

`LocalTextEmbedder`生成時はモデル、tokenizer、ONNX sessionを作りません。最初の非blank入力で一度だけ初期化し、内部lockでsession/tokenizerの利用とcloseを直列化します。推論処理は`Dispatchers.IO`へ移し、入力はDJLのlocal `InputStream` tokenizerへ渡します。DJLのoffline/telemetry opt-out system propertyはtokenizer生成前に設定します。

配置中断時の`.partial`だけをモデル専用ディレクトリ内で削除し、各ファイルをSHA-256・byte size確認後に原子的に置換します。破損時は該当ファイルだけを復旧します。モデル出力は768要素、finite、正のL2 normを満たさない場合に明示的な推論エラーとします。

## prompt

- query: `task: search result | query: {content}`
- document: `title: none | text: {content}`

## テスト

- `LocalTextEmbedderTest`: prompt、blank reject、出力構造、asset metadata検証、再利用、部分復旧、copy失敗を確認します。
- `LocalTextEmbedderIntegrationTest`: generated asset、tokenizer、ONNX external data、768次元出力、長文truncation、繰り返し／並行実行、close、正本データ非変更を実モデルで確認します。semantic精度や順位は判定しません。
