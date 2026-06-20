# AGENTS.md

この文書は、次にこのリポジトリを扱うCodex向けの作業ガイドです。

## プロジェクト概要

`like list manager` は、自分のXアカウントで「いいね」した投稿をAndroid端末へ取得し、タグと概要で分類・検索する個人用アプリです。

現在の主要技術:

- Kotlin / Jetpack Compose
- Room / SQLite
- OAuth 2.0 Authorization Code Flow with PKCE
- AppAuth for Android
- X API v2
- EncryptedSharedPreferences
- Coil

現在の基準コミットは `517409a Add X OAuth integration and source documentation` です。作業開始時は必ず `git status` と最新コミットを確認してください。

## 最初に読む順番

1. `GOALS.md`
   - プロダクトの目的、MVP、達成済み機能、次の目標、非目標を確認します。
   - 実装方針が曖昧な場合は、まずこの文書へ照らします。
2. `SOURCE_FILES.md`
   - 現在のアーキテクチャ、実装済み機能、未実装項目、変更目的別の入口を確認します。
   - 変更対象がまだ分からない場合は、この文書の表から最初に読む個別文書を選びます。
3. `docs/<ソースと同じパス>.md`
   - 対象ソースの役割、主要定義、関連ソース、変更時の確認項目を読みます。
   - 関連ファイル欄に書かれた文書も、編集前に必要な範囲で確認します。
4. 実際のソースファイル
   - 文書だけを根拠にせず、現在のコードとGit差分を正とします。

## Markdown構成

### `SOURCE_FILES.md`

プロジェクト全体の入口です。次の情報を持ちます。

- UI → ViewModel → Repository → DB/API/認証という全体構成
- 現在実装されているUI、X連携、保存・同期機能
- 変更目的別に最初に読む文書
- 個別ソース文書の一覧
- 現在の未実装・制約
- 他の上位文書への案内

個別クラスの細かな説明はここへ重複させず、`docs/` 側へ書きます。

### `docs/`

全ソースファイルに1対1で対応する説明Markdownがあります。ソースと同じディレクトリ構造で、元ファイル名に `.md` を追加しています。

例:

```text
app/src/main/java/com/lyco256/llm/data/ClipRepository.kt
docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md
```

各文書の基本構成:

- 対応ソース
- 役割
- 主要な定義、設定、処理
- 関連ファイルと関連理由
- 変更時の確認事項

ソースを変更したら、対応するMarkdownも同じ作業内で更新します。新しいソースを追加した場合は、同じパスの説明Markdownも必ず追加します。

### `GOALS.md`

プロダクト目標の基準文書です。

- MVPの要件
- 達成状況
- 次の開発目標
- 非目標
- 設計判断の基準

機能範囲や優先順位が変わった場合に更新します。単なる内部リファクタでは更新不要です。

### `REAL_API_VERIFICATION.md`

実Xアカウントを使った確認手順です。

- X Developer Console設定
- Client ID入力とOAuthログイン
- callback確認
- liked posts同期
- token refreshとlogout
- 401/403/429/5xxの確認観点

OAuth、scope、callback、X API操作を変更した場合は更新します。

### `docs/CHANGE_SUMMARY_2026-06-12.md`

OAuth 2.0 + PKCE導入、X API同期変更、UI変更、文書整備、検証結果をまとめたマイルストーン記録です。過去経緯を知るために使い、現在の仕様判断はコード、`GOALS.md`、`SOURCE_FILES.md` を優先します。

### `LikeTagger_requirements.md`

初期の要件・技術仕様メモです。背景、コスト方針、同期、DB、将来案まで詳しく記録されています。現行実装と異なる案も含むため、現在の仕様判断はコード、`GOALS.md`、`SOURCE_FILES.md` を優先し、設計経緯や将来候補の確認に使ってください。

## 変更内容別の読み方

### UI、検索、タグ操作

1. `docs/app/src/main/java/com/lyco256/llm/MainActivity.kt.md`
2. `MainActivity.kt`
3. `ClipRepository.kt.md`
4. 必要に応じて `Entities.kt.md` と `Daos.kt.md`

現在はActivity、ViewModel、Compose UIが1ファイルに集まっています。大きなリファクタは依頼なしに行わず、既存構成に沿って最小差分で変更します。

### 同期、月間制限、画像保存

1. `docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md`
2. `ClipRepository.kt`
3. `XApiClient.kt.md`
4. `Daos.kt.md` / `Entities.kt.md`
5. 使用量表示に影響する場合は `MainActivity.kt.md`

### OAuth、ログイン、scope、callback

1. `docs/app/src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md`
2. `XOAuthManager.kt`
3. `AndroidManifest.xml.md`
4. `ApiSettingsStore.kt.md`
5. `ClipRepository.kt.md`
6. `MainActivity.kt.md`
7. `REAL_API_VERIFICATION.md`

callback URIは次の3か所で完全一致させます。

- X Developer Console
- `app/src/main/AndroidManifest.xml`
- `XOAuthManager.REDIRECT_URI`

Client Secret、Consumer Secret、Bearer Tokenをアプリやリポジトリへ保存しないでください。

### DB、Entity、DAO

1. `Entities.kt.md`
2. `Daos.kt.md`
3. `LikeListDatabase.kt.md`
4. `ClipRepository.kt.md`

Entityの列変更はDB schema変更です。version、migration、既存実機データ、query、Repository変換を一緒に確認してください。現在はversion 1でmigration未実装です。

### 依存関係、SDK、ビルド

1. `docs/app/build.gradle.kts.md`
2. `docs/gradle/libs.versions.toml.md`
3. `docs/build.gradle.kts.md`
4. `docs/gradle.properties.md`

依存追加後はGradle Syncだけで終わらせず、ビルドとlintを実行します。

## 現在の重要な実装状態

- OAuth 2.0 + PKCEは実装済み
- callbackは `likelistmanager://oauth/x/callback`
- scopesは `tweet.read users.read like.read offline.access`
- `/2/users/me` 後に `/2/users/{id}/liked_tweets` を呼ぶ
- access tokenとrefresh tokenは暗号化保存
- Client Secretは使用しない
- 未分類、分類、タグ管理、概要検索は実装済み
- 画像は回線を問わず保存
- 動画/GIF本体は保存せず、Wi-Fi時だけpreview thumbnailを保存
- 月間取得数と15分rate limitを保持・表示
- 自動バックグラウンド同期、backup/import/export、自動テストは未実装

## 作業ルール

- 作業前に `git status --short` と `git diff` を確認します。
- ユーザーの既存変更を巻き戻しません。
- 大きなリファクタは依頼なしに行いません。
- プロジェクト内に目的に合うスクリプトや手順書がある場合は、個別コマンドを組み立てる前にそちらを優先して使います。使わなかった場合は、最終報告で理由を明記します。
- ソース変更と対応する `docs/...md` の更新を同じコミットに含めます。
- 全体構成や実装状況が変わった場合は `SOURCE_FILES.md` も更新します。
- 目標や優先順位が変わった場合は `GOALS.md` も更新します。
- OAuth/APIの確認方法が変わった場合は `REAL_API_VERIFICATION.md` も更新します。
- token、Client ID以外のsecret、実アカウント情報をコミットしません。
- 画像やDBを削除する操作、アプリデータ消去、`git reset --hard` は明示的な許可なしに実行しません。

## 実機データ保護

SC-56Cなど、ユーザーが日常利用している実機には復元できない投稿、タグ、画像、認証情報が蓄積されています。実機内のアプリデータは代替不能な本番データとして扱います。

- ユーザーから操作ごとの明示的な許可を得て、復元可能なバックアップを作成・検証するまでは、アプリのアンインストール、データ消去、再インストールを伴う操作を実行しません。
- `adb uninstall`、`adb shell pm clear`、Android Studioのデータ消去操作は実行しません。
- `connectedDebugAndroidTest`、`connectedAndroidTest`など、対象アプリをアンインストールまたは再インストールする可能性があるGradleタスクを、蓄積データのある実機に対して実行しません。Instrumentation Testはエミュレーターまたはテスト専用端末で実行します。
- 実行時の挙動が不明なテスト、インストール、package ID・署名・`applicationId`変更は、実機データを失う可能性がある操作として扱います。安全性を確認できない場合は実行せず、ユーザーへ報告します。
- 実機へAPKを導入するときは、接続先とpackageを確認したうえで、既存データを維持する`adb install -r`だけを使用します。ただし、`-r`はバックアップの代わりにはなりません。
- DB migrationの確認は、既存実機データを使わず、旧versionのDBを作成する自動テストまたは複製したテスト環境で行います。
- 実機データへ影響する可能性がある操作が必要な場合は、DB本体、WAL/SHM、画像、設定を含むバックアップを先に作成し、ファイルが読み取れることと件数・サイズを確認します。安全なバックアップ手段がない場合は操作を中止します。
- 実機への上書き後は、packageのUIDと初回インストール日時が変わっていないこと、投稿・タグ件数が維持されていることを確認します。
- 2026-06-15に`connectedDebugAndroidTest`が実機上の対象アプリを再インストールし、蓄積データを消失させた事例があります。同じ操作を蓄積データのある端末で繰り返してはいけません。

## 検証

Android Studioやエミュレーターは低スペックPCへの負荷が高いため、通常はコマンドラインの単発実行を優先します。

通常のビルド、単体テスト、lint、必要に応じた実機への安全な上書き再インストールでは、まず `SAFE_DEBUG_ROUTINE.md` と `scripts/run-safe-debug-check.ps1` を確認し、適用可能ならそれらを使います。個別に `gradlew` や `adb` を実行するのは、スクリプトでカバーできない確認が必要な場合か、スクリプト利用が不適切な理由を説明できる場合に限ります。

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

注意:

- `testDebugUnitTest`にはタグ階層と絞り込みのテストがあります。
- DB migrationのInstrumentation Testはエミュレーターまたはテスト専用端末だけで実行します。
- UI、OAuth callback、実X APIはSC-56CなどのUSBデバッグ実機で確認します。
- APKの上書きインストールは `adb install -r` を使うと既存データを維持できます。
- 実機起動後は `FATAL EXCEPTION`、`AndroidRuntime`、ANRをlogcatで確認します。

## 作業完了時

1. 対応ソース文書を更新したか確認
2. 必要なら `SOURCE_FILES.md`、`GOALS.md`、`REAL_API_VERIFICATION.md` を更新
3. `git diff --check`
4. `assembleDebug`, `testDebugUnitTest`, `lintDebug`
5. `git status --short` で意図した差分だけか確認
6. ユーザーが求めた場合だけコミット

コミットや最終報告では、変更内容、検証結果、未確認事項、実機確認の有無を簡潔に伝えてください。
