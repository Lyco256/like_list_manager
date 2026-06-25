# `ApiSettingsStore.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/ApiSettingsStore.kt`

## 役割

OAuth 2.0 Client IDとOAuthセッションを `EncryptedSharedPreferences` へ暗号化保存します。Client Secretは扱いません。

## 主要定義

- `ApiSettings`: Client IDのみを保持
- `OAuthSession`: access token、refresh token、有効期限、scope、Xユーザー情報
- `isExpired`: 有効期限60秒前から更新対象と判定
- `save`: Client ID保存と旧OAuth 1.0aキーの削除
- `loadSession` / `saveSession` / `clearSession`: ログイン状態の永続化

## 関連ファイル

- `XOAuthManager.kt.md`: 保存するtokenを取得・更新します。
- `ClipRepository.kt.md`: IOスレッドから設定とsessionを読み書きします。
- `../MainActivity.kt.md`: Client ID入力とログイン状態を表示します。
- `AppContainer.kt.md`: Storeを1つ生成します。

## 変更時の確認

保存キー変更時は既存データ移行とログアウト動作を確認します。tokenやClient Secretをログ、通常Preferences、ソースへ出さないでください。

## テスト分離

- `SettingsStore` がRepository向けの保存契約です。
- 本番は名前を指定できる `ApiSettingsStore`、統合テスト用アプリは永続化しない `InMemorySettingsStore` を使います。
- テスト用アプリは暗号化済み本番PreferencesやOAuth tokenを読みません。
