# LikeTagger 要件・技術仕様メモ

作成日: 2026-05-31  
対象: Android向け個人用アプリ  
目的: 自分のXアカウントで「いいね」した投稿を取得し、画像つきで端末内に保存し、あとからタグ付け・検索できるようにする。

---

## 1. 背景

Xで見つけた投稿をあとから分類・検索したい。  
共有ボタンで毎回保存するのは面倒なので、操作感としては「X公式アプリで普通にいいねするだけ」にしたい。

そのため、LikeTaggerは次の流れを基本とする。

```text
X公式アプリで普通にいいね
  ↓
LikeTaggerが自分のliked_tweetsを同期
  ↓
新規いいね投稿をローカルDBに保存
  ↓
画像だけ端末内にダウンロード
  ↓
未分類リストに入れる
  ↓
あとからタグ付け・検索
```

---

## 2. 基本方針

### 2.1 アプリの位置づけ

LikeTaggerは「Xクライアント」ではなく、**自分専用のXいいね保存・分類アプリ** とする。

### 2.2 対象ユーザー

- 自分のみ
- 自分のXアカウントのみ
- 他人に提供するSaaSや公開アプリとしては考えない

### 2.3 X側操作

- X公式アプリでいいねする
- LikeTaggerはX側に書き込み操作をしない
- LikeTaggerからX本体に「いいね」「いいね解除」「投稿」などは行わない
- X APIは読み取り用途のみ

### 2.4 保存対象

保存する:

- 投稿ID
- 投稿URL
- 投稿本文
- 投稿者ID
- 投稿者名
- 投稿者ユーザー名
- 投稿日時
- 同期日時
- 保存日時
- 画像
- タグ
- メモ

保存しない:

- 動画本体
- GIF本体
- 他人のいいね一覧
- X側への書き込み操作履歴

動画・GIFについては、必要なら将来的にサムネイルだけ保存する余地を残す。

---

## 3. コスト方針

### 3.1 月額予算

目標予算:

```text
月300円程度
```

### 3.2 X API課金の考え方

X APIの公式Pricingでは、Owned Readsは「自分の開発者アプリが自分のデータを読む」ケースであり、posts、bookmarks、followers、likesなどが対象とされている。価格は `$0.001 / resource`、つまり1000リソースで$1とされている。

LikeTaggerでは自分のアカウントの `GET /2/users/{id}/liked_tweets` のみを使う想定なので、Owned Read対象として扱える前提で設計する。

ただし、X APIの価格・条件は変わる可能性があるため、実装前と運用開始前に必ず公式Pricingを再確認する。

### 3.3 取得件数の目安

概算:

```text
1000件取得 = 約$1
2000件取得 = 約$2
月300円 ≒ 約$2前後として運用
```

安全側の運用値:

```text
月間取得上限: 1800件
警告ライン: 1500件
強制停止ライン: 2000件
```

1日あたりの目安:

```text
1800件 / 30日 = 60件/day
```

したがって、1日あたり50〜60件程度の新規いいね取得を安全圏とする。

---

## 4. 採用技術

### 4.1 アプリ

```text
Android Native App
```

候補技術:

- Kotlin
- Jetpack Compose
- Room
- WorkManager
- OkHttp または Ktor Client
- Coil
- Android Keystore
- Jetpack Security / DataStore + 暗号化

### 4.2 DB

```text
Room + SQLite
```

理由:

- 投稿・タグ・画像メタデータのような構造化データを扱いやすい
- オフライン閲覧に向いている
- Android公式がRoomをSQLite上の抽象化レイヤーとして提供している

### 4.3 画像保存

```text
Android内部ストレージ
```

方針:

- DBには画像本体を入れない
- DBにはローカルパス・リモートURL・サイズなどのメタデータだけ入れる
- 画像本体はアプリ専用内部ストレージに保存する

保存先イメージ:

```text
/files/images/{x_post_id}_{media_key}.jpg
```

### 4.4 バックグラウンド同期

```text
WorkManager
```

理由:

- 1回限り・繰り返しのバックグラウンド処理に使える
- Androidの制約下で比較的安定して動作する
- 同期処理の再試行・制約指定に向く

### 4.5 認証

```text
OAuth 2.0 Authorization Code Flow with PKCE
```

方針:

- 外部ブラウザまたはChrome Custom TabsでXログイン
- アプリにクライアントシークレットを埋め込まない
- 必要最小限のscopeだけ要求する
- access token / refresh tokenは暗号化して保存する

想定scope:

```text
tweet.read
users.read
like.read
offline.access
```

`offline.access` はrefresh tokenが必要な場合に使用する。

---

## 5. X API仕様

### 5.1 使用エンドポイント

```http
GET https://api.x.com/2/users/{id}/liked_tweets
```

用途:

- 自分がいいねした投稿一覧を取得する
- 新規いいね投稿を差分同期する

### 5.2 取得パラメータ案

```text
max_results=50
tweet.fields=id,text,created_at,author_id,attachments
expansions=attachments.media_keys,author_id
media.fields=media_key,type,url,preview_image_url,width,height
user.fields=id,name,username
```

### 5.3 保存対象メディア

```text
media.type == "photo"
  → 保存する

media.type == "video"
  → 保存しない

media.type == "animated_gif"
  → 保存しない
```

将来オプション:

```text
video / animated_gif は preview_image_url のみ保存
```

### 5.4 ページング

`liked_tweets` はページング可能なので、初回同期や追加取得ではpagination tokenを使う。

ただし、費用を抑えるため、無制限にページングしない。

---

## 6. 同期仕様

### 6.1 同期モード

実装する同期:

1. 手動同期
2. 起動時同期
3. 低頻度バックグラウンド同期

### 6.2 手動同期

ユーザーが「同期」ボタンを押したときに実行する。

```text
同期ボタン
  ↓
予算上限チェック
  ↓
liked_tweets取得
  ↓
DB重複確認
  ↓
新規投稿のみ保存
  ↓
photoのみダウンロード
```

### 6.3 起動時同期

アプリ起動時、前回同期から一定時間経っていれば実行する。

推奨値:

```text
前回同期から30分以上経過していれば同期
```

### 6.4 バックグラウンド同期

WorkManagerで低頻度に実行する。

推奨値:

```text
1日2〜4回程度
Wi-Fi接続時優先
バッテリー低下時は実行しない
```

### 6.5 初回同期

初回に過去のいいねを全取得しない。

推奨値:

```text
初回同期: 最新300件
```

理由:

- 初回コストを抑える
- 使い始めに必要十分な件数を確保する
- 過去分はあとから手動追加取得できるようにする

### 6.6 差分同期

基本的には保存済み `x_post_id` との重複チェックで差分判定する。

```text
APIから取得したpost
  ↓
clips.x_post_id に存在するか確認
  ↓
存在する: スキップ
存在しない: 新規保存
```

`newest_seen_post_id` も保持するが、liked_tweetsの順序仕様や取り消しの扱いに依存しすぎないよう、最終的にはDB重複確認を正とする。

### 6.7 X側でいいね解除された場合

方針:

```text
ローカルには残す
```

理由:

- LikeTaggerは同期ミラーではなく保存・分類アプリ
- 一度保存したクリップはユーザーが明示的に消すまで残す
- X側の状態と完全一致させる必要はない

### 6.8 削除

削除はローカルのみ。

```text
LikeTagger内で削除
  ↓
ローカルDBとローカル画像を削除またはゴミ箱へ移動
  ↓
X側のいいね状態は変更しない
```

MVPではゴミ箱ありを推奨する。

---

## 7. 画面仕様

### 7.1 ホーム

表示項目:

```text
未分類件数
最近保存
タグ一覧
検索
同期ボタン
今月の取得数
推定API費用
```

例:

```text
未分類: 23
最近保存: 120
今月の取得数: 742 / 1800
推定API費用: 約$0.742
```

### 7.2 未分類画面

目的:

- 新規保存された投稿を高速にタグ付けする

表示:

- 画像
- 投稿本文
- 投稿者名
- ユーザー名
- 投稿日時
- 保存日時
- タグチップ
- メモ入力
- Xで開くボタン
- ローカル削除ボタン

操作:

```text
タグチップをタップ: タグ追加/解除
+タグ: 新規タグ追加
左右スワイプ: 前/次の投稿
下スワイプ: 後回し
Xで開く: X公式アプリまたはブラウザで開く
```

### 7.3 タグ一覧

表示:

- タグ名
- 色
- 件数
- 並び順

操作:

- タグ作成
- タグ名変更
- 色変更
- 並び替え
- タグ削除

### 7.4 検索画面

検索対象:

- 投稿本文
- 投稿者名
- ユーザー名
- メモ

絞り込み:

- タグ
- 未分類
- 画像あり
- メモあり
- 保存日
- 投稿日時

### 7.5 設定画面

設定項目:

- Xログイン/ログアウト
- 同期頻度
- 初回同期件数
- 月間取得上限
- 画像保存容量表示
- バックアップエクスポート
- バックアップインポート
- API使用量リセット日
- テーマ設定

---

## 8. タグ仕様

### 8.1 基本仕様

- 1投稿に複数タグを付けられる
- タグには色を設定できる
- タグには並び順を持たせる
- 階層タグはMVPでは作らない

例:

```text
ROS2
CAN
数学
あとで読む
UI参考
ロボコン
GitHub
論文
ネタ
```

### 8.2 未分類判定

```text
タグが0個のclip = 未分類
タグが1個以上のclip = 分類済み
```

ただし、将来的に `is_later` のような「後回し」状態を追加する余地を残す。

---

## 9. DB設計案

### 9.1 clips

```sql
CREATE TABLE clips (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    x_post_id TEXT NOT NULL UNIQUE,
    author_id TEXT,
    author_name TEXT,
    author_username TEXT,
    text TEXT,
    post_url TEXT NOT NULL,
    x_created_at TEXT,
    saved_at TEXT NOT NULL,
    synced_at TEXT NOT NULL,
    note TEXT,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    is_archived INTEGER NOT NULL DEFAULT 0,
    is_deleted_on_x INTEGER NOT NULL DEFAULT 0
);
```

### 9.2 assets

```sql
CREATE TABLE assets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    clip_id INTEGER NOT NULL,
    media_key TEXT,
    type TEXT NOT NULL,
    local_path TEXT,
    remote_url TEXT,
    preview_url TEXT,
    width INTEGER,
    height INTEGER,
    size_bytes INTEGER,
    created_at TEXT NOT NULL,
    FOREIGN KEY (clip_id) REFERENCES clips(id) ON DELETE CASCADE
);
```

### 9.3 tags

```sql
CREATE TABLE tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    color TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

### 9.4 clip_tags

```sql
CREATE TABLE clip_tags (
    clip_id INTEGER NOT NULL,
    tag_id INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (clip_id, tag_id),
    FOREIGN KEY (clip_id) REFERENCES clips(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);
```

### 9.5 sync_state

```sql
CREATE TABLE sync_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    x_user_id TEXT,
    newest_seen_post_id TEXT,
    last_sync_at TEXT,
    monthly_fetched_count INTEGER NOT NULL DEFAULT 0,
    monthly_budget_limit INTEGER NOT NULL DEFAULT 1800,
    monthly_warning_limit INTEGER NOT NULL DEFAULT 1500,
    monthly_stop_limit INTEGER NOT NULL DEFAULT 2000,
    usage_month TEXT
);
```

### 9.6 auth_state

実際には暗号化ストレージ側に置くのが望ましいが、設計上の保持データは以下。

```text
x_user_id
access_token
refresh_token
expires_at
scope
```

DBに平文tokenを保存しない。

---

## 10. ストレージ仕様

### 10.1 画像保存

保存形式:

```text
オリジナル画像をそのまま保存
```

MVPでは再圧縮しない。

理由:

- 画質劣化を避ける
- 実装を単純化する
- あとから圧縮オプションを追加可能

### 10.2 容量制御

方針:

```text
自動削除しない
1GB超えたら警告
容量表示を設定画面に出す
```

勝手に画像を消すと保存アプリとして信用できないため、自動削除は行わない。

### 10.3 バックアップ

必須機能。

エクスポート形式:

```text
LikeTagger_backup_YYYYMMDD_HHMMSS.zip
```

中身:

```text
clips.json
tags.json
clip_tags.json
assets.json
images/
  {filename}.jpg
metadata.json
```

インポート:

- ZIPを選択
- JSONを検証
- 既存DBと重複マージ
- 画像を内部ストレージへ復元

---

## 11. セキュリティ

### 11.1 token保存

- access token / refresh tokenは平文保存しない
- Android Keystoreを使う
- Jetpack Securityまたは暗号化DataStoreを検討する

### 11.2 API認証

- OAuth 2.0 PKCEを使う
- native appにclient secretを埋め込まない
- stateとcode_verifierを適切に生成する

### 11.3 API使用制限

アプリ側で必ず予算制限を持つ。

```text
月間取得数をDBに保存
警告ラインを超えたら明示表示
停止ラインを超えたら自動同期停止
手動同期時も確認ダイアログ表示
```

---

## 12. エラー処理

### 12.1 APIエラー

想定:

- 401 Unauthorized
- 403 Forbidden
- 429 Rate Limit
- 5xx Server Error
- Network Error

対応:

```text
401:
  token更新
  失敗したら再ログイン要求

403:
  scope不足 or API権限不足として表示

429:
  次回同期まで待機
  バックグラウンド同期を一時停止

5xx:
  リトライ

Network Error:
  オフライン表示
  次回起動時/次回WorkManagerで再試行
```

### 12.2 画像ダウンロード失敗

方針:

- 投稿データは保存する
- assetに `download_failed` 状態を持たせる余地を残す
- あとから再ダウンロードできるようにする

MVPでは `local_path` がnullなら未保存画像として扱う。

---

## 13. MVPスコープ

最初に作る範囲:

```text
1. X OAuthログイン
2. 自分のliked_tweetsを手動同期
3. 最新300件の初回同期
4. 新規投稿の差分保存
5. photoのみローカル保存
6. 未分類一覧
7. タグ作成
8. 複数タグ付け
9. タグ別一覧
10. 本文検索
11. Xで開く
12. ZIPエクスポート
```

MVPではやらない:

```text
1. X側へのいいね/いいね解除
2. 動画保存
3. GIF保存
4. 複数アカウント対応
5. クラウド同期
6. 公開アプリ化
7. AI自動分類
8. PWA対応
9. Windows対応
```

---

## 14. v2以降の拡張候補

### 14.1 バックグラウンド同期

MVP後に追加。

```text
起動時同期
1日2〜4回のWorkManager同期
Wi-Fi時のみ同期オプション
```

### 14.2 自動タグ候補

ルールベースで十分。

例:

```text
本文に ros2/nav2/slam → ROS2
本文に can/socketcan/fdcan → CAN
URLに github.com → GitHub
URLに arxiv.org → 論文
```

### 14.3 FTS検索

Room/SQLite FTSを使う。

対象:

- 本文
- 投稿者名
- ユーザー名
- メモ

### 14.4 Google Driveバックアップ

手動ZIPエクスポートで運用後、必要なら追加。

### 14.5 画像圧縮

設定で選択可能にする。

```text
オリジナル保存
長辺1920pxに縮小
長辺1280pxに縮小
```

---

## 15. 主要な未決事項

現時点で未決のもの:

```text
アプリ名をLikeTaggerで確定するか
初回同期件数を300で確定するか
月間取得上限を1800で確定するか
バックグラウンド同期をMVPに入れるかv2に回すか
ZIPインポートをMVPに入れるか、エクスポートだけ先にするか
動画/GIFのサムネ保存をするか完全無視するか
```

推奨確定値:

```text
アプリ名: LikeTagger
初回同期: 最新300件
月間上限: 1800件
警告: 1500件
停止: 2000件
バックグラウンド同期: v2
MVPバックアップ: ZIPエクスポートのみ
動画/GIF: MVPでは完全無視
```

---

## 16. 実装順序案

### Phase 1: ローカル機能

```text
Roomスキーマ作成
タグCRUD
クリップ一覧UI
未分類UI
タグ付けUI
検索UI
```

この段階ではダミーデータで動かす。

### Phase 2: Xログイン

```text
OAuth 2.0 PKCE
token保存
自分のuser_id取得
ログアウト
```

### Phase 3: liked_tweets同期

```text
手動同期
max_results=50
初回300件
重複排除
月間取得数カウント
```

### Phase 4: 画像保存

```text
media.type=photoのみ抽出
画像ダウンロード
内部ストレージ保存
asset DB保存
失敗時の再試行
```

### Phase 5: バックアップ

```text
ZIPエクスポート
ZIPインポート
重複マージ
```

### Phase 6: 自動同期・改善

```text
起動時同期
WorkManager同期
FTS検索
自動タグ候補
容量警告
```

---

## 17. 参考情報

確認日: 2026-05-31

- X API Pricing: Owned Readsは自分のデータを読むケースで `$0.001 / resource` とされている。
  - https://docs.x.com/x-api/getting-started/pricing
- X API Get liked Posts: `GET /2/users/{id}/liked_tweets` は指定ユーザーがいいねしたPosts一覧を取得するエンドポイント。
  - https://docs.x.com/x-api/users/get-liked-posts
- X API OAuth 2.0 Authorization Code Flow with PKCE: scope指定・code_challenge・state等を使う認証フロー。
  - https://docs.x.com/fundamentals/authentication/oauth-2-0/authorization-code
- Android Room: SQLite上の抽象化レイヤーとしてローカルDB保存に使える。
  - https://developer.android.com/training/data-storage/room
- Android WorkManager: 1回限り・繰り返しのバックグラウンド処理に使える。
  - https://developer.android.com/develop/background-work/background-tasks/persistent
- Android Jetpack Security: キー、暗号化ファイル、暗号化SharedPreferences等の管理に使える。
  - https://developer.android.com/jetpack/androidx/releases/security

---

## 18. 一行まとめ

LikeTaggerは、X公式アプリの「いいね」を入力UIとして使い、自分のliked_tweetsだけをX APIで差分取得し、画像つきでAndroid端末内に保存して、あとからタグ付け・検索する個人用ローカル分類アプリである。
