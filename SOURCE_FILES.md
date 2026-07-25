Codexは通常、作業開始時に `CODEX_START.md` からこの文書へ来る。変更対象が不明な場合は「変更目的別の入口」だけを見て、対象docsと実ソースへ進む。個別文書一覧は、対象ファイル名が分からない場合だけ使う。

この文書は、全体構成、現状の実装、変更目的別入口、個別docs一覧だけを担当する。禁止事項、完了報告、検証手順は置かない。

## 2026-07-22 第11実装: retired image pipeline removal

## 2026-07-24 ordinal background queues

## 2026-07-25 frame-paced image publication

- `MediaGridSteadyLoadController.kt` separates worker `states` from Compose-facing `publishedCells`. Only non-image state changes are reconciled immediately after startup; new visible Ready candidates set `framePublicationDemand` and wait for the Compose runner.
- `TagHierarchyUiV2.kt` owns one `MediaGridFramePublicationRunner` per effective controller. It waits on demand, uses `withFrameNanos`, and calls `publishOneReadyImageForFrame()` once per frame. The runner performs no image IO.
- `MediaGridControllerStateSnapshot` is the test boundary for comparing internal Ready state and published Ready state independently.

- `MediaGridSteadyLoadController.kt`はframeごとの`MediaGridOrdinalIndex`と、metadata／Bitmapを分離したordinal BitSet pendingを使用する。background選択は最新snapshotの`centerMediaOrdinal`からnearest ordinalを取得し、urgent queueはFIFO `ArrayDeque`で保持する。
- task objectはbackground pending全件には保持せず、ordinalをworkerへ渡す時だけ構築する。watermark判定前のBitmap pending保持、memory cache hitのrequest省略、anchor変更時の未開始task保持、frame/invalidationのgeneration・token無効化を行う。

- 現行のメディアグリッド画像経路は`MediaGridDirectPreview.kt`、`MediaGridPersistentPreviewStore.kt`、`MediaGridPreviewWork.kt`、`MediaGridPreviewWorker.kt`、`TagHierarchyUiV2.kt`だけで構成する。
- `AppContainer.kt`は共有ImageLoader、`MediaGridImagePreparer`、`WorkManagerMediaGridPreviewEnqueuer`を構築し、画面は`MediaGridPreviewPreloader`を所有する。
- 廃止済みgenerator/store/state/coordinatorのsource、互換wrapper、fake、fixture、専用testは存在しない。端末に残る廃止済みcacheは参照もcleanupもせず、Androidの通常管理に任せる。
- benchmark snapshotは`filesDir/media_grid_previews/v1`の永続JPEGを読み取り専用で取得し、benchmark targetの同じ相対位置へコピーする。local path由来のcache key変換やJPEG生成は行わない。

## 2026-07-19 第6実装: direct preview pipeline

Current media-grid rendering is owned by `MediaGridSessionCoordinator` under `MainViewModel`. It keeps the session's frame, controller, prepared metadata/load states, anchor, and long-lived `LazyGridState` behavior across tabs, settings, card mode, column changes, and source revisions. New sessions show Progress only during the first warm-up; column/source updates keep the old frame visible and atomically publish the replacement. `MediaGridImagePreparer` prepares file metadata, candidates, source identities, and cache keys off composition. Retired cache data and application data remain untouched. Current acceptance evidence is tracked in `TEST_REQUIREMENTS_COVERAGE.md`.

## 2026-07-20 第8実装: persistent JPEG preview generation

- `MediaGridPersistentPreviewStore.kt` generates only the new local asset's `filesDir/media_grid_previews/v1/<assetId>.jpg`; it uses bounds/sample decode, center crop, JPEG quality 80, and same-directory atomic replacement.
- `MediaGridPreviewWork.kt` and `MediaGridPreviewWorker.kt` enqueue fixed-size batches as non-expedited unique WorkManager work with storage-not-low constraint and serial execution. The worker rechecks the asset and current `localPath` before publishing.
- `ClipRepository.kt` enqueues only successful inserted assets with a local path after source image persistence; sync does not await JPEG generation. Clip deletion removes preview files best effort after DB deletion, while storage migration leaves previews in `filesDir`.
- DB schema, original images, existing caches, candidate order, Coil configuration, preload range, grid UI, and Macrobenchmark are unchanged.

## 2026-07 media grid direct preview pipeline

- `TagHierarchyUiV2.kt` owns the classified grid, headers, sorting, selection, pinch column changes, and cell interactions.
- `ClassifiedMediaGridCell` receives a prepared image model and starts `AsyncImage` only from that model. It keeps `ContentScale.Crop`, static cell-local placeholder rendering, and one-step fallback on `onError`.
- `MediaGridDirectPreview.kt` owns the single `MediaGridImagePreparer`. It performs candidate availability, file stat, source identity, and cache-key calculation off composition, reusing same-key/asset/size results and preparing visible cells plus at most one adjacent row from the prebuilt index column.
- `AppContainer.kt` owns the one shared media-grid `ImageLoader`: crossfade is disabled, disk cache is `cacheDir/media_grid_coil_cache` at 128 MiB, memory cache is `min(totalMem / 8, 64 MiB)`, and decoder parallelism is limited to four; the normal controller still limits active requests to two.
- The retired generator, store, scheduling state, cache restore, and viewport coordinator have no source, wrapper, test, or runtime reference. Existing retired cache files are left to Android's normal cache management.
- `MediaGridMorph.kt` remains only for the existing pinch calculation/tests; no morph overlay is part of the product path.

# Source Files Guide

## 2026-07-24 cache-hit starvation fix

- `MediaGridSteadyLoadController.kt` owns the unified cache-hit transition, asset-local work reconciliation, bounded metadata retry/failure state, conflated UI publication, and deterministic controller snapshots.
- `MediaGridSteadyLoadControllerIntegrationTest.kt` is the isolated TEST_HARNESS entry for cache-hit regression, mixed cache/miss, 1,000 assets, metadata failure, and fixed-seed state-machine coverage.

## 2026-07-24 第16実装: decoupled load pipeline

- `MediaGridSteadyLoadController.kt`は、metadata準備、Bitmap load、Compose publicationを別Channel consumerで処理する。通常loadに50ms tick、固定Delay、sleep、周期pollingはない。
- metadata/Bitmapは総数4、background最大2、urgent予約最大2。viewportは最新anchorと未開始taskの優先順位だけを更新し、開始済みlocal taskを画面外移動でcancelしない。
- backgroundはframe全体metadataとlocal候補のBitmap preloadを継続し、Coil memory cache 75%で停止、65%未満signalで再開する。visible/前後1行はurgent、backgroundではnetwork候補を開始しない。
- UI publication consumerはevent到着までsuspendし、到着済みeventを1 batchでMainへ公開する。offscreen completionはCompose stateへ追加しない。
- 第16実装のunit契約は`MediaGridSteadyLoadControllerTest.kt`、既存の高速スクロール・画面復帰・列数変更・Progress・RGB565/JPEG回帰は`MainActivityComposeTest.kt`、`UiStateRenderingTest.kt`、`MediaGridRgb565IntegrationTest.kt`で確認する。

## 2026-07-24 viewport hot path改善

- `updateViewport()`は最新anchor、epoch、conflated signalだけを更新し、anchor consumerがlock外でepoch単位の`MediaGridActiveWindowSnapshot`を一度だけ生成する。
- snapshotはvisible順序、active順序、membershipを保持し、urgent判定・visible task登録・UI publicationは同じsnapshotを使う。active window再生成、queue全体のurgent昇格、viewport側のlock取得を行わない。
- `AssetQueueRecord`がmetadata/Bitmapのqueue状態、token、generation、source identity、candidate indexをasset ID単位で保持し、古いentryはtoken不一致でskipする。background queueの選択方式、候補順、worker上限、UI batch、Progressとsession保持は維持する。
- RGB_565候補は2byte/pixel、その他候補は4byte/pixelをLongで見積もる。要件のUnit証跡は`MediaGridSteadyLoadControllerTest.kt`、既存の高速viewport・画面外完了・cache再表示・fallback回帰はCompose/隔離Integration Testで確認する。

## 2026-07 keyed frame and direct preview viewport path

## 2026-07-22 steady-load controller

- `MediaGridSteadyLoadController.kt` owns startup warm-up, current-frame metadata, active-window load state, cancellation, completion batching, and the single 50ms loop.
- `MediaGridSessionCoordinator.kt` owns the session LRU, frame/controller lifetime, pause/resume, atomic frame replacement, and background refresh boundary. The controller is disposed only by ViewModel clear or session eviction.
- `TagHierarchyUiV2.kt` only overwrites the latest conflated viewport anchor and renders controller-published cell states. Pending/Loading cells do not start image work.
- `MediaGridDirectPreview.kt` remains the stateless candidate/identity/cache-key builder and does not retain metadata across render keys.
- The shared Coil capacity and decoder concurrency, persistent JPEG generation, DB, original images, UI interactions, and Macrobenchmark remain unchanged.

- The grid viewport observer reads the frame's stable item indices, visible asset IDs, and cell size; pixel-only movement does not rebuild preparation targets.
- Dragging and flinging do not stop visible `AsyncImage` requests. Compose disposal cancels requests for cells that leave the composition.
- `collectLatest` cancels old preparation on direction turns and keeps at most `columnCount` cells in the adjacent row.

## 2026-07-13 card/grid duplicate-work removal

- `MainUiState`の未分類・投稿者候補・分類済み一覧は遅延評価し、グリッド経路はカード一覧・スクロールキー・item keyを評価しない。
- Repositoryは明示的なrevision付き`MediaGridSourceSnapshot`を発行し、投稿日・local day・投稿者正規化・タグID配列・対象Asset件数をsource更新時に前計算する。
- グリッド結果cacheはsource revisionとタグ構造revisionを使い、全source/階層の`hashCode()`を使わない。`tagIdsByClip`は`LongArray`で保持し、一括タグDialog境界だけ集合化する。

## Current media-grid column change path

- The product path keeps the normal `LazyVerticalGrid` visible throughout a two-pointer gesture and changes the saved column count only once on release.
- `mediaGridColumnCountAfterPinchRelease` resolves the final accumulated distance ratio to no change or one adjacent column step; threshold, reversal, cancellation, and 2..12 bounds are pure-testable.
- Pinch-start anchor selection prefers the media cell under the pinch center, then the nearest visible media cell. Stable item keys and relative center offsets are restored after the normal grid rebuild.
- The product path does not create or call `MediaGridMorphSession`, `MediaGridMorphOverlay`, morph settle effects, or grid handoff effects. `MediaGridMorph.kt` remains for the existing pure calculation/tests; the former overlay source is removed.

## 2026-07 single-step media-grid resize（履歴: 現行経路では不使用）

- Media-grid pinch resizing changes exactly one column at direction recognition, locks until all pointers are released, and animates only composed media cells.
- Stable asset keys and center offsets preserve the central asset across header regrouping. Resize updates the latest viewport without rebuilding the ordered source snapshot or thumbnails.

## 2026-07-14 final media-grid morph adjustment（履歴: 現行経路では不使用）

- `MediaGridMorph.kt` keeps Header geometry, title-layer alpha, and the session/handoff state calculations independent from Compose and source work.
- `MediaGridMorphOverlay.kt` draws only bounded Media Slot/Header ranges; the normal grid remains visible until the plan, stable Asset visuals, Rects, and placeholder brush are ready. Progress/correction are read through graphics layers.
- `TagHierarchyUiV2.kt` keeps the normal grid as the only grid, prepares the final Header/Media sequence off the UI thread, updates only motion during tracking, and owns the single Morph handoff correction path.

この文書は、like list managerの全体構成、現状の実装、変更時に最初に読む個別文書への索引です。

個別ソースの説明は `docs/` 配下に、ソースと同じディレクトリ構造で配置しています。ファイル名は元ソース名に `.md` を追加した形式です。

例:

```text
app/src/main/java/com/lyco256/llm/data/ClipRepository.kt
docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md
```

## 現在のアーキテクチャ

```text
MainActivity / Compose UI
  -> MainViewModel
    -> ClipRepository
      -> Room DAO -> SQLite
      -> XOAuthManager -> AppAuth / X OAuth 2.0
      -> XApiClient -> X API v2
      -> ApiSettingsStore -> EncryptedSharedPreferences
      -> PostStorageManager -> internal storage / SD card app-specific storage
        -> Room DB + images
```

依存関係は `LikeListManagerApp` が所有する `AppContainer` で組み立てます。

## 現状の実装

### UI

- ダークテーマ
- 未分類リスト: グループを展開し、配下のタグを保留選択して分類済みにする
- 分類済みリスト: 一致件数と文章形式の条件サブバー、適用/キャンセル付き全画面絞り込みDialog、タグのみトグル付きの全ツイート検索、投稿日・本文・概要・投稿者・ユーザー・タグ／グループの「含む」「必須」「排除」複合絞り込み、タグ再割り当て
- タグリスト: 無制限階層のグループ／タグ追加、名称変更、移動、長押し並び替え、削除、別タグへの一括追加
- X風の投稿本文、投稿者、画像表示
- 投稿カード内の保存済みPhotoをタップすると、黒背景の全画面画像ビューアで表示し、複数Photoは左右スワイプで切り替え
- 投稿カード上では投稿URLを文字列として表示せず、メディア付き投稿の本文末尾t.coもUI上だけ省略する。本文中URLと「Xで開く」機能は維持
- 未分類、分類済み、タグ管理でスクロールバー、スクロール位置維持、一番上へ移動ボタンを表示
- Xで開く、ローカル削除
- 右上設定アイコンから全画面の設定画面を開き、同期、いいね数更新、API使用量、X API設定、投稿データ保存先をまとめて表示

### X連携

- OAuth 2.0 Authorization Code Flow with PKCE
- AppAuthによるstate、code verifier/challenge管理
- AndroidアプリへClient Secretを保存しないpublic client方式
- scopes: `tweet.read users.read like.read offline.access`
- callback: `likelistmanager://oauth/x/callback`
- `/2/users/me` でユーザーID取得
- `/2/users/{id}/liked_tweets` のpagination取得
- refresh tokenによるaccess token自動更新
- logout時のtoken revoke
- 401、403、429、5xxのユーザー向けエラー表示

### 保存と同期

- Roomで投稿、画像情報、タググループ、タグ、投稿タグ関連、同期状態を保存
- DB version 7でタグ階層、いいね数、同期継続token、月別API使用量履歴、OCR文字列を保持し、version 1→2、2→3、3→4、4→5、5→6、6→7を非破壊移行
- Client IDとOAuth tokenは暗号化SharedPreferencesへ保存
- Room DBと画像は内部ストレージまたはSDカードのアプリ専用領域へまとめて保存
- 保存先変更時はコピー、容量・件数・DB整合性検証、切り替え、旧データ削除を行う
- 選択中のSDカードがない場合は空DBへ切り替えず、閲覧・編集・同期を停止
- 保存先設定と移動復旧状態は内部SharedPreferencesへ保存
- PhotoはWebP lossy quality 85で保存
- 動画/GIF本体は保存せず、previewImageUrlからthumbnailを取得してWebPで保存する。新規同期ではWi-Fi待ち状態を作らない
- 投稿IDのunique制約で重複保存を防止
- 月間取得数、月別API使用量履歴、警告/停止判定値、15分rate limitを記録
- 初回サンプルデータはDBが空の場合だけ投入

## 変更目的別の入口

| 変更したいこと | 最初に読む文書 | 次に確認する文書 |
| --- | --- | --- |
| 画面、操作、検索、タグUI | `docs/app/src/main/java/com/lyco256/llm/MainActivity.kt.md` | `docs/app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt.md`, `ClipRepository.kt.md`, `Entities.kt.md` |
| 同期ロジック、月間制限、画像保存 | `docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md` | `XApiClient.kt.md`, `Daos.kt.md`, `Entities.kt.md` |
| 新規local assetの永続JPEG preview生成 | `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPersistentPreviewStore.kt.md` | `MediaGridPreviewWork.kt.md`, `MediaGridPreviewWorker.kt.md`, `ClipRepository.kt.md` |
| 投稿DB・画像の保存先、SDカード移動 | `docs/app/src/main/java/com/lyco256/llm/data/PostStorageManager.kt.md` | `AppContainer.kt.md`, `ClipRepository.kt.md`, `MainActivity.kt.md` |
| X APIのendpointやresponse | `docs/app/src/main/java/com/lyco256/llm/data/XApiClient.kt.md` | `ClipRepository.kt.md`, `Entities.kt.md` |
| Xログイン、scope、callback | `docs/app/src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md` | `AndroidManifest.xml.md`, `ApiSettingsStore.kt.md`, `MainActivity.kt.md` |
| DB列、table、relation | `docs/app/src/main/java/com/lyco256/llm/data/Entities.kt.md` | `LikeListDatabase.kt.md`, `Daos.kt.md`, `ClipRepository.kt.md` |
| queryやtransaction | `docs/app/src/main/java/com/lyco256/llm/data/Daos.kt.md` | `Entities.kt.md`, `ClipRepository.kt.md` |
| 依存ライブラリ、SDK | `docs/app/build.gradle.kts.md` | `docs/gradle/libs.versions.toml.md` |
| アプリ起動、権限、deep link | `docs/app/src/main/AndroidManifest.xml.md` | `LikeListManagerApp.kt.md`, `XOAuthManager.kt.md` |

## 個別文書一覧

### ルート・Gradle

- `docs/.gitignore.md`
- `docs/build.gradle.kts.md`
- `SAFE_DEBUG_ROUTINE.md`: 安全なビルド、テスト、lint、実機上書き再インストール手順
- `docs/settings.gradle.kts.md`
- `docs/gradle.properties.md`
- `docs/gradlew.md`
- `docs/gradlew.bat.md`
- `docs/gradle/libs.versions.toml.md`
- `docs/gradle/wrapper/gradle-wrapper.properties.md`
- `docs/gradle/wrapper/gradle-wrapper.jar.md`

### Android設定

- `docs/app/build.gradle.kts.md`
- `docs/app/src/main/AndroidManifest.xml.md`
- `docs/app/src/main/res/drawable/ic_x_logo.xml.md`
- `docs/app/src/main/res/values/styles.xml.md`

### Application・UI

- `docs/app/src/main/java/com/lyco256/llm/LikeListManagerApp.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MainActivity.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/SettingsScreen.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/OcrUi.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/TagColorUi.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridPlaceholderRendering.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridSessionCoordinator.kt.md`
- Classified tab card/grid switching is handled in `MainActivity.kt` and `TagHierarchyUiV2.kt`; the grid path is built from `ClassifiedMediaGridState` over the lightweight repository source, while the card path continues to use `uiState.classified`.

### Data・API

- `docs/app/src/main/java/com/lyco256/llm/data/AppContainer.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/Entities.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/Daos.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/LikeListDatabase.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/PostStorageManager.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/ApiSettingsStore.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/XApiClient.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPersistentPreviewStore.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPreviewWork.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPreviewWorker.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/OcrTextRecognizer.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/TagColorPalette.kt.md`

### Tests

- `docs/app/src/test/java/com/lyco256/llm/TagHierarchyTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridMorphTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridPlaceholderRenderingTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/UiStateRenderingTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/SearchFilterDatabaseIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/LikeListDatabaseMigrationTest.kt.md`
- `MainActivityComposeTest.kt`, `UiStateRenderingTest.kt`, `RepositoryIntegrationTest.kt`, and `LargeDatasetIntegrationTest.kt` cover the classified display toggle, lightweight media-grid flow, 2〜12 column resizing, section headers, selection/Dialog boundaries, and large-data rendering.
- `docs/app/src/androidTest/java/com/lyco256/llm/data/PostStorageManagerRecoveryTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/OcrTextRecognizerTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/MediaGridPersistentPreviewStoreTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridSessionCoordinatorTest.kt.md`

### Macrobenchmark

- `docs/macrobenchmark/build.gradle.kts.md`
- `docs/macrobenchmark/src/main/AndroidManifest.xml.md`
- `docs/macrobenchmark/src/main/res/xml/macrobenchmark_network_security_config.xml.md`
- `docs/macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/StartupMacrobenchmark.kt.md`
- `docs/macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/PersistentBenchmarkRunner.kt.md`
- `docs/macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/MediaGridPerformanceMacrobenchmark.kt.md`
- `docs/app/src/benchmark/java/com/lyco256/llm/BenchmarkSnapshotSetupActivity.kt.md`

### Scripts

- `docs/scripts/SafeScriptCommon.ps1.md`
- `docs/scripts/run-safe-debug-check.ps1.md`
- `docs/scripts/run-safe-debug-check.cmd.md`
- `docs/scripts/run-safe-integration-check.ps1.md`
- `docs/scripts/run-safe-integration-check.cmd.md`
- `docs/scripts/run-safe-macrobenchmark-check.ps1.md`
- `docs/scripts/run-safe-macrobenchmark-check.cmd.md`
- `docs/scripts/run-safe-snapshot-check.ps1.md`
- `docs/scripts/run-safe-snapshot-check.cmd.md`

## 現在の未実装・制約

- 自動バックグラウンド同期は未実装
- backup/import/exportは未実装
- 任意フォルダへの保存とアンインストール後の投稿データ保持は未実装
- タグ色変更は12色パレットで実装済み
- 動画/GIF本体は保存しない
- DBはversion 7で、version 1→2・2→3・3→4・4→5・5→6・6→7のmigrationを実装済み
- 階層・複合絞り込み・制約・件数表示の単体テストと、version 1→2・2→3・3→4・4→5・5→6・6→7のmigration testを実装済み
- 実際のXログインとliked posts同期はユーザーのClient IDとXアカウントで実機確認が必要

## 関連文書

- `TEST_REQUIREMENTS_COVERAGE.md`: 実機レベル統合テスト要件の項目別証跡、未確認事項、完了判定基準

- `GOALS.md`: プロダクトの目的、MVP、将来目標
- `SAFE_DEBUG_ROUTINE.md`: 毎回使い回せる安全なビルド/テスト/再インストール手順
- `REAL_API_VERIFICATION.md`: 実Xアカウントでの確認手順
- `docs/CHANGE_SUMMARY_2026-06-12.md`: 今回のOAuth実装と文書整備の要約
- `LikeTagger_requirements.md`: 初期の要件・技術仕様メモ。設計経緯や将来候補の確認に使い、現在の仕様判断は `GOALS.md` と実装を優先します。

## 2026-06-20 いいね数・件数表示

- 新規同期投稿へ `public_metrics.like_count` と取得日時を保存し、既存投稿は明示再取得だけで更新します。
- 設定画面から未取得／期限到来した暫定値を月間残り枠内で一括再取得できます。
- 投稿カードのいいね数・暫定警告、投稿者件数順、タグ投稿数、グループ直下要素数を表示します。
- Room schema versionは7で、version 1→2、2→3、3→4、4→5、5→6、6→7 migrationを登録しています。

## 2026-06-22 高リスク統合テスト基盤

## 2026-07-12 メディアグリッド要件確認

- 一括タグ編集は選択ツイート単位で `NONE` / `ALL` / `MIXED` を集約し、`KEEP` をDB更新へ渡さない。
- `MIXED` は全件削除予定と全件追加予定を交互に切り替え、適用確認後も複数選択状態を維持する。
- いいね数見出しは2〜4列/5〜8列/9〜12列で200/500/1000単位、週見出しは月曜〜日曜の期間表示。

## 廃止済み画像経路の履歴

実装6より前の生成・状態管理・viewportスケジューリング・独自cache復元は削除済みです。詳細はGit履歴だけに残し、この現行構造ガイドには旧APIや旧動作を再掲しません。

## 2026-07 media-grid continuous morph foundation（履歴: 現行経路では不使用）

- `MediaGridMorph.kt` is the pure state/plan boundary for `Idle`, `Tracking`, both settle phases, and `AwaitingGridHandoff`.
- A session creates one from/to plan for exactly one adjacent column count, bounded to the viewport plus two rows on each side. Slots keep both Rects, Asset keys, item indexes, and presence flags; the wider side defines the slot count.
- Header bands retain start/end title, Y, height, and presence, including zero-height add/remove transitions. The nearest Media Asset to the pinch center is kept as the Y anchor.
- The Morph transaction is separate from `MediaGridMorphOverlayMotion.progress`; progress is reversible and bounded to `0f..1f`, while the real `columnCount` remains unchanged during tracking and the callback is dispatched once only after target settle.
- `TagHierarchyUiV2.kt` keeps `pointerInput(Unit)` stable and reads latest values with `rememberUpdatedState`. Progress updates do not rebuild items or request thumbnails.
- Idle-only preparation slices the current LazyGrid window plus two rows on each side and builds both adjacent candidates; target handoff resolves the anchor by stable Asset key and performs one `scrollToItem` plus the necessary `scrollBy`. Normal anchor restoration is disabled for every non-Idle phase.
- Pure coverage is in `app/src/test/java/com/lyco256/llm/MediaGridMorphTest.kt`; the source-level contract is documented in `docs/app/src/main/java/com/lyco256/llm/MediaGridMorph.kt.md`.

## 2026-07 seamless media-grid morph overlay（履歴: 現行経路では不使用）

- `MediaGridMorphOverlay.kt` draws a viewport-bounded overlay over the single normal `LazyVerticalGrid` during all non-Idle morph phases; no second grid or `AnimatedContent` is constructed.
- `MediaGridMorphRenderModel` is created once per session plan, resolves Assets by stable key, and retains slot Assets, metadata, selection, header bands, and placeholder data through handoff. Progress updates only change interpolated Rects, layer alpha, and GPU transforms.
- Retained image state was read without starting image work. This overlay is not part of the current product path.
- Target handoff keeps progress 1 visible through callback and new-grid layout, performs one anchor correction, then completes without a second post-handoff correction.
- The old resize scale animation and normal `animateItem()` placement are removed; the retained morph documentation is historical and the current normal grid uses cell-local placeholder rendering from `MediaGridPlaceholderRendering.kt`.

- `integrationTest` build typeは `com.lyco256.llm.test` と本番とは異なるDB、画像、Preferencesを使います。
- テスト用Application containerは本番OAuth/X APIを無効化し、Repositoryテストだけが記録可能なFakeを注入します。
- AndroidJUnitRunner、Compose UI Test、Room統合、MockWebServerで環境分離、同期、データ保持、画像、HTTP異常系、主要画面を検証します。
- `SnapshotCompatibilityTest` は明示指定されたDB・画像をホスト側の一時コピーで検証し、コピー元hash不変を確認します。
- `scripts/run-safe-integration-check.cmd` はGit管理外の許可serialだけを受け入れ、同じ実機上でメインと `.test` のpackage・UID分離、メインmetadata前後不変を検証します。
- `scripts/run-safe-macrobenchmark-check.cmd` は同じ許可serial上で `.test.benchmark` 対象APKとMacrobenchmarkホストだけを扱い、メインmetadata前後不変を検証します。
- 2026-06-23以降、SC-56Cで本番と隔離テストを共存させ、AndroidJUnitRunnerによる実機統合テストを継続実行しています。wireless時は`run-safe-integration-check.cmd -DebugMethod wireless`がhardware serialからmDNS endpointを解決します。

追加したテスト文書:

- `docs/app/src/androidTest/java/com/lyco256/llm/TestEnvironmentIsolationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/RepositoryIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/XApiClientMockWebServerTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/SettingsStoreIsolationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/LargeDatasetIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/PostStorageManagerRecoveryTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/MediaGridPersistentPreviewIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/MainActivityComposeTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/SearchFilterDatabaseIntegrationTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/SnapshotCompatibilityTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/TagColorPaletteTest.kt.md`
# メディアグリッド高速化の入口

`MediaGridMetadata.kt`が軽量スナップショットの絞り込み・正規化済みcache key・実効sortだけの準備・標準安定ソート・Asset展開・最大3件LRUキャッシュを担当する。Repositoryのsourceはactive Clip/Asset/ClipTagの3 Flowだけで、タグIDの`LongArray`と前計算済み投稿者キーを保持する。保存順ではsortを省略し、cache hitでは`Calculating`を表示しない。
- `docs/app/src/test/java/com/lyco256/llm/data/TagColorPaletteTest.kt.md`

## 2026-07-14 実装22

- `app/src/benchmark/java/com/lyco256/llm/data/MediaGridBenchmark.kt` owns benchmark-only settings, frame timing, and result export. It is not part of debug, release, or integrationTest source sets.
- `app/src/benchmark/java/com/lyco256/llm/BenchmarkSnapshotImporter.kt` imports only the benchmark target handoff, rewrites absolute local paths, and never copies Preferences or credentials.
- `app/src/benchmark/java/com/lyco256/llm/BenchmarkMainActivity.kt` selects the classified media-grid startup state only for the benchmark variant; production `MainActivity` has no benchmark state or result handling.
- The production image path is `MediaGridDirectPreview.kt` plus `MediaGridPersistentPreviewStore.kt`, `MediaGridPreviewWork.kt`, and `MediaGridPreviewWorker.kt`. `TagHierarchyUiV2.kt` consumes prepared images without benchmark metrics, counters, or Trace sections.
- `MediaGridPerformanceMacrobenchmark.kt` runs identical five-iteration scroll and real two-pointer pinch scenarios.
- `run-safe-macrobenchmark-check.cmd` is the only snapshot entry and generates `build/reports/media-grid-benchmark/latest-summary.md` after cleanup and production invariance checks.

## 2026-07-19 第3実装 placeholder描画

- グリッド共有Brushを廃止し、`MediaGridPlaceholderRendering.kt`の`MediaGridCellVisualState`でセルごとに`Placeholder` / `Image` / `Error`を判定する。
- prepared candidateが未到着、または現在candidateの`onSuccess`前だけ、セル内の左上から右下までを覆う静的グラデーションを`drawWithCache`で描画する。Brushと色Listはセルサイズまたはテーマ色の変更時だけ作り直す。
- 現在モデルの`onSuccess`後はPlaceholderレイヤーを完全に外し、モデル変更または`onError`ではPlaceholderへ戻す。Failed、画像元なし、download失敗は単色背景と既存エラーアイコンのみ。
- 現行viewport、preload、候補順、依存関係、列数変更、Macrobenchmarkは変更しない。
## 2026-07-23 第14実装: RGB_565 fixed-slot pack

- `MediaGridRgb565PackStore.kt`が128asset固定slot、二重bank＋generation、crash-safe publish、最大4packのread-only mapping LRUを所有する。
- `MediaGridRgb565Coil.kt`がraw専用data/Keyer/Fetcherを提供し、Decoderを経由せず256×256 `RGB_565` Bitmapを返す。
- `MediaGridRgb565RepairWork.kt`がJPEG優先・local WebP fallbackのrepairを最大2並列、同一pack直列で実行する。
- 新規画像は`ClipRepository`でsource Bitmapからpayloadを作り、WebP保存とAsset insert後にraw publish完了を確認する。既存JPEG enqueueは維持する。
- 表示候補はraw→JPEG→local→preview URL→remote URL→display。phase13の初回4、通常2、session/scroll/atomic frame swapを維持する。
