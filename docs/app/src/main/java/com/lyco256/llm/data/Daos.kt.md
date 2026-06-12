# `Daos.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/Daos.kt`

## 役割

Roomを通じた投稿、画像、タグ、投稿タグ関連、同期状態のqueryとtransactionを定義します。

## 主要処理

- `ClipDao`: 有効投稿の監視、投稿/画像挿入、概要・削除状態更新、タグ集合置換、同期状態保存
- `countClips`: 初回サンプル投入の判定
- `TagDao`: タグ監視、件数集計、追加・更新・削除、一括タグ付け対象取得
- `replaceClipTags`: 現在値との差分だけを追加・削除するtransaction

## 関連ファイル

- `Entities.kt.md`: query対象のテーブルと戻り値を定義します。
- `LikeListDatabase.kt.md`: DAOを公開します。
- `ClipRepository.kt.md`: DAOを業務処理として組み合わせます。
- `../MainActivity.kt.md`: DAO更新結果をFlow経由で表示します。

## 変更時の確認

SQL変更時はEntity列名、Foreign Key、削除時のcascade、Flowの更新範囲、重複時のConflictStrategyを確認します。
