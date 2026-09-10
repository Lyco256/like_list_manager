# `SudachiLexicalTextAnalyzer.kt`

Sudachi Full 20260723を完全ローカルで使う、既存検索経路から独立した検索用テキスト解析コンポーネントです。

- Sudachi本体は`com.worksap.nlp:sudachi:0.8.0`に固定し、公式のFull辞書ZIPはGradle準備タスクが固定URL・SHA-256・容量で検証して生成assetへ渡します。辞書本体と生成assetはGit管理しません。
- Androidでは生成assetのZIPから`system_full.dic`だけを`noBackupFilesDir/sudachi/20260723`へ一時ファイル経由で展開します。展開中のhash／size検証、file descriptor同期、同一ディレクトリ内置換を行い、配置済み辞書が一致すれば再展開しません。
- コンポーネント生成時は辞書を初期化せず、blank入力も空結果を返します。初回の非blank解析で`Config`と`DictionaryFactory.create(Config)`からDictionary／Tokenizerを一度だけ作り、解析呼び出しは内部Mutexで直列化します。
- `normalizedText`、`readingText`、`romanizedText`、`compactText`を不変結果として返します。ローマ字化はAndroid標準ICUの`Katakana-Latin`、`Katakana-Latin/BGN`、`Latin-ASCII`だけを使います。
- `LexicalTextAnalyzer`を実装し、`LexicalIndexSynchronizer`から検索対象fieldごとの解析に使われます。`close`はIO dispatcher上で冪等にDictionaryを閉じ、close後の解析は`IllegalStateException`を返します。検索クエリ、Repository保存、既存検索UI、OCR認識へは接続しません。

## 検証

- JVMテストは小さな合成ZIPでhash／sizeの一致・不一致、原子的な一時展開、再利用、残存一時ファイルの掃除、破損配置の復旧、並行初回配置、blank入力、closeを確認します。
- Android integration testだけが同梱された本物のFull辞書を使い、日本語・混在文字列・改行・URL・絵文字・結合文字・長文・繰り返し解析・並行解析・close後解析を確認します。検索精度、順位、特定語の変換結果、実データ目視は評価しません。
