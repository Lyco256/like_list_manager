# Change Summary 2026-06-12

## OAuth 2.0 + PKCE

- AppAuthを追加
- `likelistmanager://oauth/x/callback` をAppAuth receiverへ接続
- Client IDからXログインを開始する設定UIを追加
- Authorization Codeをtokenへ交換
- `/2/users/me` でログインユーザーIDと表示情報を取得
- access token、refresh token、有効期限、scope、ユーザー情報を暗号化保存
- 期限切れ前のaccess token自動更新
- logout時のtoken revoke
- Client Secretを使用しないpublic client方式
- 旧OAuth 1.0a入力欄と通信署名処理を削除
- 旧バージョンで保存されたOAuth 1.0a秘密情報をClient ID保存時に削除

## X API同期

- Bearer user access tokenでliked postsを取得
- pagination、投稿者、画像情報、rate-limit headerを処理
- 401、403、429、5xxをユーザー向けエラーへ変換
- 未ログイン時はダミー同期せずログインを案内
- 初期サンプル投入が既存の同期状態を上書きしないよう修正

## UI

- X API設定をClient ID、ログイン、ログアウト中心へ変更
- 設定画面と使用量画面へログインユーザー名を表示
- 認証結果をActivity Result APIで受け取る構成へ変更

## Documentation

- `docs/` に全ソースファイル対応の説明Markdownを追加
- 各説明へ関連ファイルと変更時の確認項目を追加
- `SOURCE_FILES.md` を全体構成、現状実装、変更目的別索引へ再構成
- `GOALS.md` にMVP、達成状況、次の目標、非目標を整理
- `REAL_API_VERIFICATION.md` をOAuth 2.0実機確認手順へ更新

## Verification

- `assembleDebug`: 成功
- `lintDebug`: 成功
- `testDebugUnitTest`: 成功、ただしテストコード未作成のため `NO-SOURCE`
- SC-56Cへの上書きインストールと起動: 成功
- 起動直後のFATAL EXCEPTION/ANR: 検出なし
