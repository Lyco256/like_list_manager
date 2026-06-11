# Source Files Overview

このドキュメントは、`like list manager` Androidアプリの主要ソースファイルごとの役割をまとめたものです。

## アプリ入口

### `app/src/main/AndroidManifest.xml`

アプリの権限、アプリケーション、Activity、独自schemeを定義するManifestです。

- `INTERNET`
  - X API通信と画像取得に必要なネットワーク権限です。
- `ACCESS_NETWORK_STATE`
  - Wi-Fi接続中かどうかを判定し、動画/GIFサムネイル取得可否を決めるために使います。
- `<application android:name=".LikeListManagerApp">`
  - アプリ起動時に `LikeListManagerApp` をApplicationクラスとして使います。
- `<activity android:name=".MainActivity">`
  - Compose UIを表示するメイン画面です。
- launcher intent-filter
  - ホーム画面からアプリを起動できるようにします。
- deep link intent-filter
  - `likelistmanager://oauth/x/callback` を受け取るための定義です。
  - 現時点ではOAuth 2.0 PKCEの将来実装用の入口です。

### `app/src/main/res/values/styles.xml`

Android側のベーステーマを定義します。

- `Theme.LikeListManager`
  - `Theme.Material.NoActionBar` を親にし、Compose側で独自のTopAppBarを表示できるようにしています。

### `app/src/main/java/com/lyco256/llm/LikeListManagerApp.kt`

Applicationクラスです。アプリ全体で共有する依存関係を起動時に初期化します。

- `LikeListManagerApp : Application`
  - `container` を保持します。
  - `onCreate()` で `AppContainer` を生成します。

## UI / ViewModel

### `app/src/main/java/com/lyco256/llm/MainActivity.kt`

画面、ViewModel、UI状態をまとめているファイルです。Jetpack Composeで、未分類リスト、分類リスト、タグリスト、API設定、同期/使用量ダイアログを構成します。

- `MainActivity : ComponentActivity`
  - AndroidのActivity本体です。
  - `setContent` でCompose UIを起動します。
  - `MainViewModel.factory(application)` を使ってViewModelを作成します。

- `AppTab`
  - 下部タブの種類を表すenumです。
  - `Unclassified`, `Classified`, `Tags` を定義します。

- `MainUiState`
  - UI表示に必要な状態をまとめるdata classです。
  - クリップ一覧、タグ一覧、同期状態、API設定、検索文字列、選択中タグを持ちます。
  - `unclassified`
    - タグが付いていないクリップだけを抽出します。
  - `classified`
    - タグ付きクリップを抽出し、選択タグと検索文字列で絞り込みます。
    - 検索対象は概要、本文、投稿者名、ユーザー名です。

- `RepositoryUiState`
  - Repositoryから流れてくるDB由来の状態をまとめるprivate data classです。

- `MainViewModel : AndroidViewModel`
  - UIとRepositoryをつなぐViewModelです。
  - `query`, `selectedTagId`, `apiSettings` をStateFlowとして管理します。
  - `uiState`
    - RepositoryのFlowと画面操作状態を合成した状態です。
  - `init`
    - API設定をIOスレッドで読み込み、確認用ダミーデータを準備します。
  - `createTag`, `renameTag`, `deleteTag`
    - タグの追加、名称変更、削除をRepositoryへ委譲します。
  - `addAllFromTagToTag`
    - あるタグが付いた全クリップに別タグを追加します。
  - `setClipTags`
    - クリップごとのタグ再割り当てを行います。
  - `updateSummary`
    - クリップの概要を保存します。
  - `moveClipToTrash`
    - ローカル上の削除フラグを立てます。
  - `saveApiSettings`, `clearApiSettings`
    - API設定の保存/消去後、再読み込みしてUIへ反映します。
  - `syncNow`
    - 同期処理を実行し、結果メッセージをUIへ返します。

- `LikeListManagerUi`
  - アプリ全体のMaterial3テーマを定義します。
  - ダークテーマ、背景色、surface色、primary色などを指定します。

- `MainScreen`
  - Scaffold、TopAppBar、下部NavigationBar、メニュー、各タブ画面を組み立てます。
  - メニューから `同期する`, `同期/使用量`, `X API設定` を開きます。

- `tabIcon`
  - 下部タブ用の簡易アイコン文字を返します。

- `ClipListScreen`
  - 未分類リストなど、クリップ一覧を表示する汎用画面です。
  - 空の場合は `EmptyState` を表示します。

- `ClassifiedScreen`
  - 分類リスト画面です。
  - 検索欄、タグフィルタ、分類済みクリップ一覧を表示します。

- `TweetCard`
  - 1件の投稿カードを表示します。
  - 投稿者名、ユーザー名、本文、画像、概要入力、タグチップ、Xで開く、ローカル削除を扱います。

- `MediaGrid`
  - 画像/サムネイルを1から4枚までグリッド表示します。

- `MediaCell`
  - Coilの `AsyncImage` で画像URLまたはローカル画像を表示します。

- `TagChipRow`
  - クリップに付けるタグのチップ一覧を表示します。
  - チップのON/OFFでタグ割り当てを変更します。

- `TagFilterRow`
  - 分類リスト用のタグ絞り込みチップを表示します。
  - 各タグの件数も表示します。

- `TagListScreen`
  - タグ一覧画面です。
  - 新規タグ追加欄とタグ一覧を表示します。

- `TagRow`
  - タグ1件分の行です。
  - 色、タグ名、件数、名称変更、別タグへの一括追加、削除を表示します。

- `RenameTagDialog`
  - タグ名変更用ダイアログです。

- `AddAllTagsDialog`
  - あるタグの全クリップに、別タグを追加するためのダイアログです。
  - 元タグは消さず、追加だけ行います。

- `UsageDialog`
  - API設定状況、月間取得数、警告/停止ライン、15分制限、最終同期時刻を表示します。
  - API設定の登録済み判定はOAuth 1.0a同期に必要な項目が揃っているかで判断します。

- `ApiSettingsDialog`
  - X API設定入力ダイアログです。
  - `X User ID`, `OAuth 2.0 Client ID`, `OAuth 1.0a API Key`, `API Key Secret`, `Access Token`, `Access Token Secret` を保存できます。
  - secret系は画面上でパスワード表示になります。

- `ConfirmDialog`
  - 削除などの確認に使う汎用ダイアログです。

- `EmptyState`
  - 一覧が空のときの中央メッセージを表示します。

## データ層

### `app/src/main/java/com/lyco256/llm/data/AppContainer.kt`

アプリ内の依存関係をまとめて生成する簡易DIコンテナです。

- `AppContainer`
  - `LikeListDatabase` をRoomで作成します。
  - `ApiSettingsStore` を作成します。
  - `ClipRepository` にContext、DAO、設定ストアを渡して生成します。

### `app/src/main/java/com/lyco256/llm/data/LikeListDatabase.kt`

Room Databaseの定義です。

- `LikeListDatabase : RoomDatabase`
  - DBに含めるEntityを定義します。
  - versionは `1` です。
  - `clipDao()` と `tagDao()` を公開します。

### `app/src/main/java/com/lyco256/llm/data/Entities.kt`

Room Entityと、UI/Repositoryで使う合成モデルを定義します。

- `ClipEntity`
  - Xの投稿1件を表すEntityです。
  - 投稿ID、投稿URL、本文、投稿者情報、作成日時、同期日時、概要、ローカル削除フラグを持ちます。
  - `xPostId` はunique indexです。

- `AssetEntity`
  - 投稿に紐づく画像または動画/GIFサムネイルを表すEntityです。
  - `clipId` で `ClipEntity` に紐づきます。
  - ローカル保存パス、リモートURL、プレビューURL、サイズ、ダウンロード状態を持ちます。

- `TagEntity`
  - タグを表すEntityです。
  - タグ名、色、並び順、作成/更新日時を持ちます。
  - タグ名はunique indexです。

- `ClipTagEntity`
  - クリップとタグの多対多関係を表す中間Entityです。
  - `clipId` と `tagId` の複合主キーです。

- `SyncStateEntity`
  - 同期状態とAPI使用量を保持するEntityです。
  - 月間取得数、月間上限、警告ライン、停止ライン、15分rate limit、最終同期時刻を持ちます。

- `ClipWithDetails`
  - `ClipEntity` に関連assetsとtagsを合成したUI表示用モデルです。

- `TagWithCount`
  - `TagEntity` と、そのタグが付いたクリップ数をまとめるUI表示用モデルです。

### `app/src/main/java/com/lyco256/llm/data/Daos.kt`

Room DAOを定義します。

- `ClipDao`
  - `observeActiveClips`
    - ローカル削除されていないクリップを保存日時順で監視します。
  - `assetsForClipIds`
    - 指定クリップのassetsを取得します。
  - `observeAssets`
    - 全assetsを監視します。
  - `clipTagsForClipIds`
    - 指定クリップのタグ割り当てを取得します。
  - `observeClipTags`
    - 全タグ割り当てを監視します。
  - `insertClip`, `insertAssets`, `insertClipTag`
    - クリップ、assets、タグ割り当てを追加します。
    - 重複時はIGNOREです。
  - `deleteClipTag`
    - クリップから指定タグを外します。
  - `updateClip`
    - 概要や削除フラグなどのクリップ更新に使います。
  - `observeSyncState`, `getSyncState`, `upsertSyncState`
    - 同期状態を監視/取得/保存します。
  - `replaceClipTags`
    - クリップのタグ集合を現在値との差分で置き換えるTransactionです。

- `TagDao`
  - `observeTags`
    - タグ一覧を並び順と名前順で監視します。
  - `observeTagCounts`
    - タグごとの件数を集計します。
  - `insertTag`, `updateTag`, `deleteTag`
    - タグの追加、更新、削除を行います。
  - `clipsForTag`
    - 指定タグが付いた未削除クリップを取得します。
  - `insertClipTag`
    - クリップにタグを追加します。

- `TagCountRow`
  - `observeTagCounts` の集計結果を受け取るdata classです。

### `app/src/main/java/com/lyco256/llm/data/ApiSettingsStore.kt`

X API設定を暗号化SharedPreferencesに保存するファイルです。

- `ApiSettings`
  - API設定値をまとめるdata classです。
  - `authMode`
    - 将来のOAuthモード管理用です。
  - `xUserId`, `clientId`, `apiKey`, `apiKeySecret`, `accessToken`, `accessTokenSecret`
    - X API接続に使う設定値です。
  - `hasAnyCredential`
    - 何かしらの認証情報が入っているかを判定します。
  - `hasCompleteOAuth1Credentials`
    - 現在の同期処理に必要なOAuth 1.0a一式が揃っているかを判定します。

- `ApiSettingsStore`
  - `EncryptedSharedPreferences` と `MasterKey` を使って設定を暗号化保存します。
  - preferencesは遅延生成され、起動時ANRを避けるためRepository側からIOスレッドで読み込まれます。
  - `load`
    - 保存済み設定を読み込みます。
  - `save`
    - 設定を同期保存します。
  - `clear`
    - 設定を消去します。

### `app/src/main/java/com/lyco256/llm/data/ClipRepository.kt`

アプリの主要な業務ロジックを担当するRepositoryです。DB、API設定、X APIクライアント、画像保存、タグ操作をまとめます。

- `ClipRepository`
  - `ClipDao`, `TagDao`, `ApiSettingsStore`, `XApiClient` を使ってアプリ機能を実装します。

- `loadApiSettings`
  - API設定をIOスレッドで読み込みます。

- `syncState`
  - 同期状態をFlowで公開します。

- `tagsWithCount`
  - タグ一覧とタグ件数を合成してFlowで公開します。

- `clipsWithDetails`
  - クリップ、assets、タグ割り当てを合成し、UI表示用の `ClipWithDetails` リストとして公開します。

- `saveApiSettings`, `clearApiSettings`
  - API設定の保存/消去を行います。

- `ensureSeedData`
  - API未設定時でも画面確認できるようにダミー投稿と同期状態を用意します。
  - 既存データと重複しないよう、クリップ追加はIGNOREです。

- `syncNow`
  - 同期ボタンの本体処理です。
  - API設定が未完了ならダミーデータを用意してメッセージを返します。
  - 月間取得数が月をまたいでいた場合はカウントをリセットします。
  - 月間停止ライン/上限を超えている場合はAPIを呼ばず停止します。
  - `GET /2/users/{id}/liked_tweets` をページングしながら取得します。
  - 新規投稿だけDBに保存します。
  - 画像は保存し、動画/GIFはWi-Fi時だけプレビューサムネイルを保存対象にします。
  - 取得数、挿入数、rate limit情報を同期状態へ保存します。

- `createAssetForMedia`
  - X APIのmedia情報から `AssetEntity` を作ります。
  - photoは画像URLを使い、video/animated_gifはpreview imageを使います。
  - ダウンロード成功/失敗/Wi-Fi待ち状態を `downloadState` に入れます。

- `downloadMedia`
  - 画像をアプリ内部ストレージの `files/images` に保存します。
  - 接続/読み込みタイムアウトを設定しています。

- `createTag`, `renameTag`, `deleteTag`
  - タグの追加、名称変更、削除を行います。

- `addAllFromTagToTag`
  - あるタグが付いた全クリップに、別タグを一括追加します。

- `setClipTags`
  - 1クリップのタグ集合を置き換えます。

- `updateSummary`
  - クリップの概要を更新します。

- `moveClipToTrash`
  - X側には触らず、ローカル削除フラグだけ立てます。

- `Context.isWifiConnected`
  - 現在のネットワークがWi-Fiかどうかを判定します。

## X API層

### `app/src/main/java/com/lyco256/llm/data/XApiClient.kt`

X APIのliked_tweets取得とOAuth 1.0a署名を担当します。

- `XPost`
  - X APIから取得した投稿1件を表すdata classです。
  - 投稿ID、本文、作成日時、投稿者情報、media一覧を持ちます。

- `XMedia`
  - X APIのmedia情報を表すdata classです。
  - media key、type、画像URL、preview image URL、幅、高さを持ちます。

- `XApiResult`
  - API取得結果をまとめるdata classです。
  - 投稿一覧、次ページtoken、rate limit情報を持ちます。

- `XApiClient`
  - X API通信を担当するクラスです。

- `fetchLikedPosts`
  - `GET https://api.x.com/2/users/{id}/liked_tweets` を呼びます。
  - 必須認証情報が空なら `require` で失敗させます。
  - `tweet.fields`, `expansions`, `media.fields`, `user.fields` を指定します。
  - OAuth 1.0a Authorizationヘッダーを付けます。
  - HTTP 2xx以外は `IllegalStateException` としてエラー内容を返します。
  - レスポンス本文とrate limitヘッダーを `XApiResult` に変換します。

- `oauth1Header`
  - OAuth 1.0aの署名パラメータ、署名ベース文字列、HMAC-SHA1署名を作り、Authorizationヘッダー文字列を返します。

- `parseLikedPosts`
  - X APIのJSONレスポンスから投稿、ユーザー、mediaを対応付け、`XPost` リストへ変換します。

- `JSONArray?.toMapById`
  - `includes.users` や `includes.media` をid/keyで引けるMapに変換します。

- `Map<String, String>.toQueryString`
  - クエリパラメータをURLエンコードした文字列へ変換します。

- `String.percentEncode`
  - OAuth署名用のpercent encodeを行います。

- `hmacSha1`
  - HMAC-SHA1署名をBase64文字列として返します。

## ビルド設定

### `settings.gradle.kts`

Gradleプロジェクト名、plugin repositories、dependency repositories、`:app` モジュールを定義します。

### `build.gradle.kts`

ルートプロジェクトのGradle plugin aliasを定義します。

### `app/build.gradle.kts`

Androidアプリモジュールのビルド設定です。

- applicationIdは `com.lyco256.llm` です。
- minSdkは29、targetSdk/compileSdkは36です。
- Kotlin JVM toolchainは21です。
- Compose、Room、Coil、Jetpack Securityを依存関係に持ちます。

### `gradle/libs.versions.toml`

Gradle Version Catalogです。

- Android Gradle Plugin、Kotlin、KSP、Compose BOM、Room、Coilなどのバージョンを集中管理します。

### `gradle.properties`

Gradle/Androidビルド用のプロパティを定義します。

### `gradlew` / `gradlew.bat` / `gradle/wrapper/*`

Gradle Wrapper一式です。ローカルにGradleを個別インストールしなくても同じGradleバージョンでビルドできます。
