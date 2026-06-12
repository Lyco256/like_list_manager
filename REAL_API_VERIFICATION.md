# Real API Verification

実際のXアカウントでOAuth 2.0 + PKCE連携を確認する手順です。Client Secretやアクセストークンをソースコードへ書く必要はありません。

## X Developer Console

- App type: Native App
- App permission: Read
- Callback URI: `likelistmanager://oauth/x/callback`
- Scopes: `tweet.read users.read like.read offline.access`

Androidアプリに入力するのはOAuth 2.0 Client IDだけです。Client Secret、Consumer Key、Consumer Secret、Bearer Tokenは入力しません。

## Login

1. アプリの `...` から `X API設定` を開きます。
2. `OAuth 2.0 Client ID` を入力します。
3. `保存してXにログイン` を押します。
4. ブラウザでXへログインし、アプリへのアクセスを許可します。
5. アプリへ戻り、`@username でXにログインしました` と表示されることを確認します。
6. `X API設定` または `同期/使用量` にログイン中のユーザー名が表示されることを確認します。

## Sync

1. `...` から `同期する` を押します。
2. 同期結果に取得件数と新規保存件数が表示されることを確認します。
3. 取得した「いいね」投稿が未分類リストに表示されることを確認します。
4. 本文、投稿者名、画像が表示されることを確認します。
5. 投稿へタグと概要を設定し、分類リストのタグ絞り込みと検索で見つかることを確認します。
6. `同期/使用量` で月間取得数、15分制限、最終同期時刻が更新されることを確認します。

## Token And Logout

1. 時間を置いた後も再ログインなしで同期できることを確認します。期限切れに近いアクセストークンは更新トークンで自動更新されます。
2. `X API設定` の `Xからログアウト` を押します。
3. ログイン表示が消え、同期時にログインを求められることを確認します。

## Failure Signals

- `401`: 認証期限切れとして再ログインを案内します。
- `403`: Developer Consoleの権限またはスコープ不足を案内します。
- `429`: 15分制限の回復待ちを案内します。
- `5xx`: X APIの一時障害として再試行を案内します。
- callback後にアプリへ戻らない場合は、Developer ConsoleとManifestのURIが完全一致しているか確認します。
- Android `logcat` に `FATAL EXCEPTION`、`AndroidRuntime`、アプリのANRがないことを確認します。

## Local Verification Commands

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
.\gradlew.bat testDebugUnitTest
```
