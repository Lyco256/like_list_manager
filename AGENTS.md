# AGENTS.md

この文書は、Codexがこのリポジトリで作業するときの入口です。
作業開始時は `git status --short`、`git diff`、最新コミットを確認し、以降は作業種別に応じた文書だけを読みます。

## プロジェクト概要

`like list manager` は、自分のXアカウントで「いいね」した投稿をAndroid端末へ取り込み、タグと概要で分類・検索する個人用アプリです。

主な技術:

- Kotlin / Jetpack Compose
- Room / SQLite
- OAuth 2.0 Authorization Code Flow with PKCE
- AppAuth for Android
- X API v2
- EncryptedSharedPreferences
- Coil

## 必要な文書の選び方

| 状況 | まず読む文書 |
| --- | --- |
| 変更対象がまだ不明 | `SOURCE_FILES.md` |
| 目的や優先順位を確認したい | `GOALS.md` |
| 既存ソースの役割や参照先を知りたい | 対応する `docs/<source path>.md`。対応文書が不明なら `SOURCE_FILES.md` |
| 実ソースを触る | 対応する `docs/...md` と実ソース |

## 作業種別ごとの参照文書

| 作業種別 | 読む文書 |
| --- | --- |
| 新機能追加 | `SOURCE_FILES.md`、影響する `docs/...md`、必要なら `GOALS.md` |
| バグ修正 | 関連する `docs/...md`、実ソース、必要なテスト文書 |
| UI、検索、タグ操作 | `SOURCE_FILES.md`、`docs/app/src/main/java/com/lyco256/llm/MainActivity.kt.md`、`docs/app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt.md` |
| DB、Entity、DAO、migration | `docs/app/src/main/java/com/lyco256/llm/data/Entities.kt.md`、`docs/app/src/main/java/com/lyco256/llm/data/Daos.kt.md`、`docs/app/src/main/java/com/lyco256/llm/data/LikeListDatabase.kt.md`、`docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md` |
| 同期、X API、画像保存、月間制限 | `docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md`、`docs/app/src/main/java/com/lyco256/llm/data/XApiClient.kt.md`、`docs/app/src/main/java/com/lyco256/llm/data/Daos.kt.md`、`docs/app/src/main/java/com/lyco256/llm/data/Entities.kt.md` |
| OAuth、login、scope、callback、token保存 | `docs/app/src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md`、`docs/app/src/main/AndroidManifest.xml.md`、`docs/app/src/main/java/com/lyco256/llm/data/ApiSettingsStore.kt.md`、`REAL_API_VERIFICATION.md` |
| 保存先、SDカード、DB・画像移動 | `docs/app/src/main/java/com/lyco256/llm/data/PostStorageManager.kt.md`、`docs/app/src/main/java/com/lyco256/llm/data/AppContainer.kt.md`、`docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md`、`docs/app/src/main/java/com/lyco256/llm/MainActivity.kt.md` |
| テスト追加・修正 | 対象機能のdocs、`SOURCE_FILES.md` のTests一覧、既存テストdocs、必要に応じて `TEST_REQUIREMENTS_COVERAGE.md` |
| build、test、lint、実機上書き | `SAFE_DEBUG_ROUTINE.md` |
| 実機統合テスト、安全境界、実機データ保護 | `SAFE_DEBUG_ROUTINE.md`、`TEST_REQUIREMENTS_COVERAGE.md` |
| 目的、優先順位、MVP、非目標 | `GOALS.md` |
| 古い設計経緯や将来案 | `LikeTagger_requirements.md` |

## 読みこぼし防止

- 複数の作業種別に当てはまる場合は、該当する文書をすべて読む
- 変更対象が不明な場合は `SOURCE_FILES.md` を読む
- ソースを編集する場合は、対応する `docs/<source path>.md` と実ソースを読む
- docsと実ソースが矛盾する場合は、実ソースとGit差分を正とする

## 絶対禁止事項

- ユーザーの既存変更を巻き戻さない
- 依頼なしに大きなリファクタを行わない
- token、Client ID以外のsecret、実アカウント情報をコミットしない
- 画像、DB、アプリデータを削除する操作は明示許可なしに実行しない
- `git reset --hard` は明示許可なしに実行しない

## 実機データ保護

- SC-56Cなど、ユーザーが日常利用している実機のアプリデータは本番データとして扱う
- 実機には復元できない投稿、タグ、画像、認証情報が蓄積されている可能性がある
- 実機データへ影響する操作が必要な場合は、復元可能なバックアップを確認してから進める
- 安全なバックアップ手段がない場合は操作を中止する
- `adb uninstall` と `adb shell pm clear` は、蓄積データのある実機では実行しない
- `connectedDebugAndroidTest` や `connectedAndroidTest` など、対象アプリを再インストールする可能性があるGradleタスクは、蓄積データのある実機では実行しない
- package ID変更、署名変更、`applicationId`変更、挙動が不明なインストールやテストは、実機データを失う可能性がある操作として扱う
- 実機へAPKを入れる場合は、接続先とpackageを確認したうえで `adb install -r` だけを使う
- `adb install -r` はバックアップの代わりにはならない
- DB migrationの確認は、既存実機データを直接使わず、自動テストまたは複製したテスト環境で行う
- 2026-06-15に `connectedDebugAndroidTest` が実機上の対象アプリを再インストールし、蓄積データを消失させた事例がある。同じ操作を繰り返してはいけない

## 検証

- 通常の build、unit test、lint、必要に応じた安全な実機上書きは `SAFE_DEBUG_ROUTINE.md` を読む
- 実機統合テストや安全境界を扱う場合は `SAFE_DEBUG_ROUTINE.md` と `TEST_REQUIREMENTS_COVERAGE.md` を読む
- 検証コマンドの詳細は AGENTS.md に長く書かず、検証文書側へ寄せる
- 実機確認が必要な変更は、必要に応じて `REAL_API_VERIFICATION.md` も読む

## 文書更新ルール

- ソースを変更したら、対応する `docs/...md` も同じ作業内で更新する
- 新しいソースを追加したら、同じパス構造の説明Markdownも追加する
- 全体構成や実装状況が変わったら `SOURCE_FILES.md` を更新する
- 目的、優先順位、MVP、非目標が変わったら `GOALS.md` を更新する
- OAuth、scope、callback、X API確認手順が変わったら `REAL_API_VERIFICATION.md` を更新する
- build、test、lint、実機検証の安全手順が変わったら `SAFE_DEBUG_ROUTINE.md` を更新する

## 完了報告

- 変更内容を簡潔に伝える
- 実行した検証を簡潔に伝える
- 未確認事項を簡潔に伝える
- 実機確認の有無を簡潔に伝える
- ユーザーが求めた場合だけコミットする
