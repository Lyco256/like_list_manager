# 01. `isDeleted` 廃止とDB v8移行

## 目的

現在ほぼ使われていないsoft-delete列 `clips.isDeleted` を完全に廃止し、既存実機DBの全投稿を失わず新スキーマへ移行する。

## 現状

- `ClipEntity` に `isDeleted: Boolean` がある。
- 一覧・単体取得・メディアグリッド・タグ件数など複数queryが `isDeleted = 0` を条件にしている。
- 現在のツイート削除は `isDeleted=true` を使わず、`clips` 行を直接DELETEしている。
- DBはversion 7。
- `assets` と `clip_tags` は `clips` を外部キー参照している。

## 実装

- DBをversion 8へ上げ、7→8 migrationで `clips.isDeleted` を削除する。
- Android端末のSQLiteバージョン差に依存する `DROP COLUMN` は使わず、確実に動くtable rebuildで移行する。
- migration前に `isDeleted=0/1` を区別して捨てない。`isDeleted=1` だった行も含め、`clips` の全行を通常投稿としてそのまま新tableへ移す。
- `assets`、`clip_tags`、各primary key、`xPostId` unique index、外部キー関係を失わない。親table再作成時のCASCADEで子データを消さない移行手順にする。
- `ClipEntity` から `isDeleted` を削除する。
- DAOの「active」概念を廃止し、`isDeleted` 条件を全queryから除去する。メソッド名も意味が一致する名前へ直し、Repository・テスト側の呼び出しも合わせる。
- `PostStorageManager` のRoom builderへ7→8 migrationを登録する。
- 初回seed判定、タグ件数、メディアグリッドsource、単体clip監視、一覧監視が新しい「全投稿」前提で同じデータを参照するようにする。

## データ保護

- migrationで投稿・asset・clip_tagを1件も意図的に削除しない。
- `isDeleted=true` の旧行が存在しても、勝手にDELETEしない。
- IDを書き直さない。
- migration途中で失敗した場合に旧DB内容を部分的に欠損させる手順にしない。

## テスト

既存migration testを拡張し、最低限次を固定する。

- version 7 fixtureに `isDeleted=false` と `isDeleted=true` の投稿を両方入れ、8へ移行後に両方残る。
- 両投稿に紐づくassetとclip_tagが残り、ID・内容・関連先が変わらない。
- `clips` に `isDeleted` 列が残っていない。
- `xPostId` unique制約と子tableの外部キーが維持される。
- 新DAOでは旧 `isDeleted=true` 行も通常の一覧・件数・タグ件数・メディア対象に含まれる。
- 既存7以前からのmigration chainを壊していない。
