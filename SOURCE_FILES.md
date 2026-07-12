Codexは通常、作業開始時に `CODEX_START.md` からこの文書へ来る。変更対象が不明な場合は「変更目的別の入口」だけを見て、対象docsと実ソースへ進む。個別文書一覧は、対象ファイル名が分からない場合だけ使う。

この文書は、全体構成、現状の実装、変更目的別入口、個別docs一覧だけを担当する。禁止事項、完了報告、検証手順は置かない。

## 2026-07 media grid headers

- `app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt` now owns `buildClassifiedMediaGridItems`, full-width header items, and the like-count overlay.
- `MediaGridThumbnailStore.kt` and `MediaGridThumbnailManager.kt` own fixed 256px JPEG cache generation and latest-viewport serial prioritization; `AppContainer` owns their single instances and the grid-only ImageLoader.
- The same file owns tweet-level long-press selection, shared selection across a tweet's assets, the 2–6 column card-dialog action, specified selection/video overlay sizes, and pending bulk tag editing.
- `MainActivity.kt` carries filtered media-grid tag IDs; `ClipRepository.kt` and `Daos.kt` apply the final tag set in one transaction.
- `app/src/test/java/com/lyco256/llm/TagHierarchyTest.kt` covers bucket generation for day/week/month and like-count units.
- `app/src/androidTest/java/com/lyco256/llm/UiStateRenderingTest.kt` covers the PostTime header, LikeCount header, like overlay, and existing badge/error regressions.
- `UiStateRenderingTest.kt` and `data/RepositoryIntegrationTest.kt` also cover tweet selection, bulk tag draft/apply/discard behavior, and transaction-level tag replacement.

# Source Files Guide

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
- 動画/GIF本体は保存せず、preview thumbnailをWi-Fi時だけ元形式で保存
- 投稿IDのunique制約で重複保存を防止
- 月間取得数、月別API使用量履歴、警告/停止判定値、15分rate limitを記録
- 初回サンプルデータはDBが空の場合だけ投入

## 変更目的別の入口

| 変更したいこと | 最初に読む文書 | 次に確認する文書 |
| --- | --- | --- |
| 画面、操作、検索、タグUI | `docs/app/src/main/java/com/lyco256/llm/MainActivity.kt.md` | `docs/app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt.md`, `ClipRepository.kt.md`, `Entities.kt.md` |
| 同期ロジック、月間制限、画像保存 | `docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md` | `XApiClient.kt.md`, `Daos.kt.md`, `Entities.kt.md` |
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
- `docs/app/src/main/res/values/styles.xml.md`

### Application・UI

- `docs/app/src/main/java/com/lyco256/llm/LikeListManagerApp.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MainActivity.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/SettingsScreen.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/OcrUi.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/TagColorUi.kt.md`
- Classified tab card/grid switching is handled in `MainActivity.kt` and `TagHierarchyUiV2.kt`; the grid path is built from `MediaGridEntry` rows over `uiState.classified`.

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
- `docs/app/src/main/java/com/lyco256/llm/data/OcrTextRecognizer.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/TagColorPalette.kt.md`

### Tests

- `docs/app/src/test/java/com/lyco256/llm/TagHierarchyTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/UiStateRenderingTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/SearchFilterDatabaseIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/LikeListDatabaseMigrationTest.kt.md`
- `MainActivityComposeTest.kt`, `UiStateRenderingTest.kt`, `RepositoryIntegrationTest.kt`, and `LargeDatasetIntegrationTest.kt` cover the classified display toggle, lightweight media-grid flow, 2〜12 column resizing, section headers, selection/Dialog boundaries, and large-data rendering.
- `docs/app/src/androidTest/java/com/lyco256/llm/data/PostStorageManagerRecoveryTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/OcrTextRecognizerTest.kt.md`

### Macrobenchmark

- `docs/macrobenchmark/build.gradle.kts.md`
- `docs/macrobenchmark/src/main/AndroidManifest.xml.md`
- `docs/macrobenchmark/src/main/res/xml/macrobenchmark_network_security_config.xml.md`
- `docs/macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/StartupMacrobenchmark.kt.md`

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
- 階層・複合絞り込み・制約・件数表示の単体テストと、version 1→2・2→3・3→4・4→5のmigration testを実装済み
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
- `docs/app/src/androidTest/java/com/lyco256/llm/MainActivityComposeTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/SearchFilterDatabaseIntegrationTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/SnapshotCompatibilityTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/TagColorPaletteTest.kt.md`
# メディアグリッド高速化の入口

`MediaGridMetadata.kt`が軽量スナップショットの絞り込み・前計算キー生成・標準安定ソート・Asset展開・最大3件LRUキャッシュを担当する。DAO/RepositoryはAsset重複列を除外し、UIはCalculating/Readyと静的スケルトンを扱う。
