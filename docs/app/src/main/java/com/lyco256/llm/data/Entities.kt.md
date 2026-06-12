# `Entities.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/Entities.kt`

## 役割

Roomのテーブル構造と、Repository/UI向けの合成モデルを定義します。

## 主要モデル

- `ClipEntity`: X投稿、投稿者、本文、概要、保存・同期時刻、ローカル削除状態
- `AssetEntity`: 画像/動画サムネイルのURL、ローカルパス、サイズ、取得状態
- `TagEntity`: タグ名、色、並び順
- `ClipTagEntity`: 投稿とタグの多対多関連
- `SyncStateEntity`: 月間取得数、予算・警告・停止ライン、15分rate limit、最終同期
- `ClipWithDetails`, `TagWithCount`: UI表示用の合成モデル

## 関連ファイル

- `LikeListDatabase.kt.md`: EntityをDBへ登録します。
- `Daos.kt.md`: Entityを読み書きします。
- `ClipRepository.kt.md`: API結果をEntityへ変換し、合成モデルを公開します。
- `../MainActivity.kt.md`: 合成モデルを画面表示・編集します。

## 変更時の確認

Entityの列変更はDB schema変更です。`LikeListDatabase` のversionとmigration、DAO query、Repository変換、既存端末データの移行を必ず一緒に設計します。
