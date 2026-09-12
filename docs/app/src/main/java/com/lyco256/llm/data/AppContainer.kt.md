# `AppContainer.kt`

## 第14実装

共有`MediaGridRgb565PackStore`、最大4件のraw Fetcher、repair enqueuerを構築します。専用Keyer/Fetcherを既存ImageLoaderへ登録し、JPEG/local/URL向けDecoder設定、disk/memory容量、phase13 controller上限は変更しません。

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/AppContainer.kt`

## 2026-07 direct preview ImageLoader

`AppContainer` owns the single shared media-grid `ImageLoader` and stateless `MediaGridImagePreparer`. The ViewModel-scoped session controller uses them without changing cache configuration. The loader keeps crossfade disabled, enables `cacheDir/media_grid_coil_cache` with a 128 MiB disk limit, caps memory at `min(totalMem / 8, 64 MiB)`, and uses an IO decoder dispatcher limited to four concurrent decodes; normal controller requests remain limited to two. It constructs no retired image-pipeline dependency.

## 2026-07 persistent JPEG preview

`AppContainer`は`WorkManagerMediaGridPreviewEnqueuer`を1つ構築してRepositoryへ注入します。workerは`filesDir/media_grid_previews/v1`の永続JPEGだけを生成し、表示側は`MediaGridImagePreparer`と`MediaGridPreviewPreloader`を使います。

## 役割

`localSearchEngine` を1つ公開する。`localTextEmbedder` は同じ具体的 `LocalTextEmbedder` をSemantic同期とquery検索へ共有し、Japanese CLIPとSudachiも既存instanceを渡す。engine生成だけでは辞書・model・ANNをloadせず、Application起動時の検索やANN構築も追加しない。

投稿保存先マネージャー、暗号化設定ストア、OAuthマネージャー、Repositoryを組み立てる簡易DIコンテナです。

## 生成順

`PostStorageManager` → `ApiSettingsStore` / `XOAuthManager` → `ClipRepository` の順で生成します。Room Databaseは保存先マネージャーが現在の保存先に対して開閉します。

メディアグリッド用の共有ImageLoader、prepared-image作成器、WorkManager enqueuerをここで1インスタンスずつ生成します。さらに、正本DBのFlow、独立派生検索DB、Sudachi解析器、`LexicalIndexSynchronizer`、1つの遅延初期化`LocalTextEmbedder`、`SemanticIndexSynchronizer`、1つの遅延初期化`LocalMultimodalEmbedder`、`ImageEmbeddingSynchronizer`を生成します。3つのsynchronizerは同じ`PostStorageManager.database`を監視し、semantic側は`DocumentEmbedder`、image側は`ImageEmbedder`として共有runtimeを使います。preloaderは画面ライフサイクル単位でUI側が所有します。benchmark settings、metrics、counterなどの計測依存は生成せず、本番引数にも含めません。benchmark専用Activity・importer・frame計測は `app/src/benchmark` 側に隔離されています。

## 関連ファイル

- `../LikeListManagerApp.kt.md`: AppContainerの所有者です。
- `LexicalIndexSynchronizer.kt.md`: Room clip監視とfingerprint差分reconcileを担当します。
- `LexicalDocumentBuilder.kt.md`: 5種類のsource documentとfingerprintを生成します。
- `SemanticIndexSynchronizer.kt.md`: text/summary/OCRのchunk化、embedding、派生DB同期を担当します。
- `LocalTextEmbedder.kt.md`: 共有する遅延初期化EmbeddingGemma runtimeを担当します。
- `ImageEmbeddingSynchronizer.kt.md`: 保存済みlocal image assetのdecode、fingerprint差分、Japanese CLIP image embedding同期を担当します。
- `LocalImageEmbeddingBitmapDecoder.kt.md`: BitmapFactoryによるbounded image decodeを担当します。
- `LocalMultimodalEmbedder.kt.md`: 共有する遅延初期化Japanese CLIP runtimeを担当します。
- `LikeListDatabase.kt.md`: Room Databaseを定義します。
- `PostStorageManager.kt.md`: Room DBと画像の保存先、移動、復旧を管理します。
- `ApiSettingsStore.kt.md`: Client IDとOAuthセッションを保存します。
- `XOAuthManager.kt.md`: AppAuth認証を担当します。
- `ClipRepository.kt.md`: 全依存を受け取る業務ロジック層です。

## 変更時の確認

Repositoryのconstructor変更や新しい共有サービス追加時は、このファイルとApplication初期化を同時に確認します。

## テスト分離

`BuildConfig.TEST_HARNESS` がtrueの専用variantでは、テスト専用DB/画像/Preferences名、`InMemorySettingsStore`、`DisabledOAuthGateway`、`DisabledXApiGateway`を注入します。本番variantは従来どおり暗号化設定、AppAuth、X API実装を使います。

テストvariantではsample mediaも無効化し、UI起動時のCoil外部画像通信を防ぎます。

## 2026-07 OCR update

- Injects the OCR gateway into `ClipRepository`.
- Test harness mode uses a deterministic fake OCR gateway so integration tests stay stable.

## 2026-08 structured OCR boundary

The test harness fake now returns `OcrRecognitionResult`, matching the production gateway boundary while keeping the existing deterministic full-text behavior. Tests can inject arbitrary structured regions without changing Repository or UI persistence behavior.

## 2026-08 OCR7 PaddleOCR

The production container injects `PaddleOcrTextGateway`, which runs the bundled PP-OCRv6 small/medium ONNX assets offline. The test variant injects the existing fake gateway. `closePaddleOcr()` releases the single active engine when the application receives critical memory pressure; no model choice is persisted.
