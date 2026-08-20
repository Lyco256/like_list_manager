# Source Files Guide

## 現在のアーキテクチャ

```text
MainActivity / Compose UI
  -> MainViewModel
    -> ClipRepository
      -> Room DAO -> SQLite
      -> UndoCoordinator -> single durable undo_slot
      -> DurableClipDeleteUndoStore -> staged image bytes
      -> HeavyLocalWorkTracker -> shared local-work progress
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
- 未分類リスト: 初回DB emissionまでProgressを表示し、グループを展開して配下タグをカード内draftとして選択し、`適用`で一括確定する
- 分類済みリスト: 一致件数と文章形式の条件サブバー、適用中だけ背景highlightするfilter/sortボタン、背景highlightを持たない表示切替、適用/キャンセル付き全画面絞り込みDialog、タグのみトグル付きの全ツイート検索、投稿日・本文・概要・投稿者・ユーザー・タグ／グループの「含む」「必須」「排除」複合絞り込み、タグ再割り当て
- タグリスト: 無制限階層の縦guide付きcompact rowで、グループ／タグ追加、名称変更、移動、長押し並び替え、削除、Tree popupから別タグへの一括追加
- X風の投稿本文、クリック可能な投稿者、保存済み投稿数、いいね数（詳細popup付き）、カード幅・画像比率に応じた高さ上限、画像previewを表示
- メディアグリッドのスクロール中は既存見出しと同じ現在位置ラベルを上端の一時ピルで表示し、停止後3秒保持して上方向へ消す。列数Morph中は旧ラベルを固定し、handoff後に新粒度で再評価する
- 投稿カード内の保存済みPhotoをタップすると、黒背景の全画面画像ビューアで表示し、複数Photoは左右スワイプで切り替え
- 投稿カード上では投稿URLを文字列として表示せず、メディア付き投稿の本文末尾t.coもUI上だけ省略する。本文中URLと「Xで開く」機能は維持
- 未分類、分類済み、タグ管理でスクロールバー、スクロール位置維持、一番上へ移動ボタンを表示
- Xで開く、hard DELETEと画像stagingによる永続Undo対応のローカル削除
- 編集後は画面下部に共通Undo通知を表示し、5秒timeout、横/下Swipe確定、キャンセルによる逆操作を扱う
- 同期と重いローカルDB/ファイル処理のどちらかがactiveな間はTop barに共通Progressを表示
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
- DB version 9でタグ階層、いいね数、同期継続token、月別API使用量履歴、OCR文字列、単一永続Undo slotを保持する。version 7→8で `isDeleted` を廃止し、8→9で `undo_slot` を追加するほか、1→2から6→7までの非破壊移行も維持
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
| メディアグリッド現在位置ピル、表示区間ラベル | `docs/app/src/main/java/com/lyco256/llm/MediaGridScrollPosition.kt.md` | `TagHierarchyUiV2.kt.md`, `MediaGridMorph.kt.md`, `MediaGridScrollPositionTest.kt.md` |
| メディアグリッド高速スクロールバー、thumb drag | `docs/app/src/main/java/com/lyco256/llm/MediaGridScrollbar.kt.md` | `TagHierarchyUiV2.kt.md`, `MediaGridScrollPosition.kt.md`, `MediaGridScrollbarTest.kt.md` |
| 共通Undo通知、5秒timeout、Swipe dismiss | `docs/app/src/main/java/com/lyco256/llm/UndoNotificationUi.kt.md` | `MainActivity.kt.md`, `data/UndoCoordinator.kt.md` |
| Undo payloadの種類、schema version、厳密decode | `docs/app/src/main/java/com/lyco256/llm/data/UndoPayloadCodec.kt.md` | `UndoCoordinator.kt.md`, `Entities.kt.md` |
| 同期ロジック、月間制限、画像保存 | `docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md` | `XApiClient.kt.md`, `Daos.kt.md`, `Entities.kt.md` |
| 重いローカルDB・ファイル処理のactive追跡 | `docs/app/src/main/java/com/lyco256/llm/data/HeavyLocalWorkTracker.kt.md` | `ClipRepository.kt.md` |
| 投稿削除の永続Undo、画像staging・復元・cleanup | `docs/app/src/main/java/com/lyco256/llm/data/DurableClipDeleteUndoStore.kt.md` | `ClipRepository.kt.md`, `UndoCoordinator.kt.md`, `Daos.kt.md` |
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
- `docs/app/src/main/java/com/lyco256/llm/UndoNotificationUi.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/SettingsScreen.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/OcrUi.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/TagColorUi.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridPlaceholderRendering.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridSessionCoordinator.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridMorphCanvas.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridMorphHandoff.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridMorphLazyGridHandoffTestHost.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridScrollPosition.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridScrollbar.kt.md`
- Classified tab card/grid switching is handled in `MainActivity.kt` and `TagHierarchyUiV2.kt`; the grid path is built from `ClassifiedMediaGridState` over the lightweight repository source, while the card path continues to use `uiState.classified`.
- `MediaGridScrollPosition.kt` derives the current-position label from the existing viewport ordinal and `mediaGridMorphBucketSpec`; its generation-scoped pill state handles scroll start, 3-second idle retention, upward exit, stale-timer cancellation, frame changes, and Morph handoff reevaluation without touching image or repository work.

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
- `docs/app/src/main/java/com/lyco256/llm/data/UndoCoordinator.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/UndoPayloadCodec.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/DurableClipDeleteUndoStore.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/HeavyLocalWorkTracker.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPersistentPreviewStore.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPreviewWork.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPreviewWorker.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/OcrTextRecognizer.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/TagColorPalette.kt.md`

### Tests

- `docs/app/src/test/java/com/lyco256/llm/AuthorSavedCountTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/ClipTagDraftStateTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/InitialClipListStateTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/SingleCardMediaPresentationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/AuthorSavedCountUiTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/CrossFeatureRegressionUiTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/TweetLikeDisplayUiTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/UndoNotificationUiTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/TagHierarchyTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/TagManagementCompactRowContractTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/TagTreeGuideTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/TagTreeGuideContractTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/TagManagementCompactRowUiTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridMorphTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridMorphHandoffTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridRenderingContractTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridPreparedRenderTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridPlaceholderRenderingTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/MediaGridMorphCanvasComposeTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/MediaGridMorphLazyGridHandoffComposeTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/MediaGridFramePublicationComposeTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/UiStateRenderingTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/SearchFilterDatabaseIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/LikeListDatabaseMigrationTest.kt.md`
- `MainActivityComposeTest.kt`, `UiStateRenderingTest.kt`, `RepositoryIntegrationTest.kt`, and `LargeDatasetIntegrationTest.kt` cover the classified display toggle, lightweight media-grid flow, 2〜12 column resizing, section headers, selection/Dialog boundaries, and large-data rendering.
- `docs/app/src/androidTest/java/com/lyco256/llm/data/PostStorageManagerRecoveryTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/OcrTextRecognizerTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/MediaGridPersistentPreviewStoreTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/HeavyLocalWorkTrackerTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/UndoPayloadCodecTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/UndoDaoIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/UndoCoordinatorIntegrationTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridSessionCoordinatorTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridScrollPositionTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridScrollbarTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/MediaGridPositionPillUiTest.kt.md`

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
- DBはversion 9で、version 1→2から8→9までのmigrationを実装済み
- 階層・複合絞り込み・制約・件数表示の単体テストと、version 1→2から8→9までのmigration testを実装済み
- 実際のXログインとliked posts同期はユーザーのClient IDとXアカウントで実機確認が必要

## 関連文書

- `TEST_REQUIREMENTS_COVERAGE.md`: 実機レベル統合テスト要件の項目別証跡、未確認事項、完了判定基準

- `GOALS.md`: プロダクトの目的、MVP、将来目標
- `SAFE_DEBUG_ROUTINE.md`: 毎回使い回せる安全なビルド/テスト/再インストール手順
- `REAL_API_VERIFICATION.md`: 実Xアカウントでの確認手順

# メディアグリッド高速化の入口

`MediaGridMetadata.kt`が軽量スナップショットの絞り込み・正規化済みcache key・実効sortだけの準備・標準安定ソート・Asset展開・最大3件LRUキャッシュを担当する。Repositoryのsourceは現存Clip/Asset/ClipTagの3 Flowだけで、タグIDの`LongArray`と前計算済み投稿者キーを保持する。保存順ではsortを省略し、cache hitでは`Calculating`を表示しない。
- `docs/app/src/test/java/com/lyco256/llm/data/TagColorPaletteTest.kt.md`
