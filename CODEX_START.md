`like list manager` は、自分のXアカウントで「いいね」した投稿をAndroid端末へ取り込み、タグと概要で分類・検索する個人用Androidアプリ。主な技術は Kotlin / Jetpack Compose / Room / OAuth 2.0 PKCE / AppAuth / X API v2 / EncryptedSharedPreferences / Coil。

最初に確認するもの:

文書の使い分け:

* 現在の実装を把握する: `SOURCE_FILES.md`、対象ソース、対応する `docs/`、必要に応じて `GOALS.md` と `TEST_REQUIREMENTS_COVERAGE.md`
* 実装順序、ブランチ、当時の要件・意図、廃止経路、過去の検証記録を確認する: `ARCHIVE.md`（通常の作業では読まない）

```powershell
git status --short --branch
git log -1 --oneline
git diff --stat
git diff --name-only
```

最初から `git diff` 全文を読まない。対象ファイルが決まった後に必要な範囲だけ読む。

```powershell
git diff -- <path>
```

変更対象が不明なら `SOURCE_FILES.md` の「変更目的別の入口」だけを見る。目的や優先順位が必要なら `GOALS.md` を見る。

作業別の読み方:

* UI、検索、タグ操作: `SOURCE_FILES.md` のUI入口 → 対象UI docs → 実ソース
* DB、Entity、DAO、migration: data系docs → 実ソース → migration test
* 同期、X API、画像保存、月間制限: `ClipRepository` / `XApiClient` 周辺docs → 実ソース
* OAuth、login、scope、callback、token保存: `XOAuthManager` / `AndroidManifest` / `ApiSettingsStore` 周辺docs → `REAL_API_VERIFICATION.md`
* 保存先、SDカード、DB・画像移動: `PostStorageManager` / `AppContainer` / `ClipRepository` 周辺docs
* テスト追加・修正: 対象機能docs → 既存テスト → 必要な場合だけ `TEST_REQUIREMENTS_COVERAGE.md`
* build、test、lint、実機上書き: `SAFE_DEBUG_ROUTINE.md`
* 実機統合テスト、安全境界、実機データ保護: `SAFE_DEBUG_ROUTINE.md` と `TEST_REQUIREMENTS_COVERAGE.md`
* 古い設計経緯や将来案: 必要な場合だけ `ARCHIVE.md`

ソースを触る時だけ、対応する `docs/<source path>.md` と実ソースを読む。関連docsは、実際に影響する場合だけ読む。
