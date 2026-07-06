# `Entities.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/Entities.kt`

## 役割

Roomのテーブル構造と、Repository/UI向けの合成モデルを定義します。

## 主要モデル

- `ClipEntity`: X投稿、投稿者、本文、概要、保存・同期時刻、ローカル削除状態
- `AssetEntity`: 画像/動画サムネイルのURL、ローカルパス、サイズ、取得状態
- `TagGroupEntity`: 親グループ、名称、兄弟内の並び順
- `TagEntity`: タグ名、色、親グループ、兄弟内の並び順
- `ClipTagEntity`: 投稿とタグの多対多関連
- `SyncStateEntity`: 月間取得数、予算・警告・停止ライン、15分rate limit、最終同期、liked posts同期の継続用next token
- `ApiUsageMonthEntity`: 月別のAPI使用量履歴と累計集計元
- `SettingsSnapshot`: 設定画面へ渡す月間/API使用量、保存件数、画像枚数、容量の集約値
- `ClipWithDetails`, `TagWithCount`: UI表示用の合成モデル
- `TagHierarchy`, `TagTreeNode`: グループとタグの混在階層、子孫タグ、重複を除いたグループ件数
- `TagFilterState`, `TagNodeRef`: 「含む／必須／排除」条件とタグ／グループ識別子。グループは含む・排除に使い、必須は実タグだけで使います。

## 関連ファイル

- `LikeListDatabase.kt.md`: EntityをDBへ登録します。
- `Daos.kt.md`: Entityを読み書きします。
- `ClipRepository.kt.md`: API結果をEntityへ変換し、合成モデルを公開します。
- `../MainActivity.kt.md`: 合成モデルを画面表示・編集します。

## 変更時の確認

Entityの列変更はDB schema変更です。`LikeListDatabase` のversionとmigration、DAO query、Repository変換、既存端末データの移行を必ず一緒に設計します。

## いいね数と件数（2026-06-20）

- `ClipEntity` はいいね数、取得日時、恒久失敗日時、失敗理由をnullableで保持します。
- `TagHierarchy.groupCounts` は投稿数ではなく、各グループ直下のタグ数と子グループ数の合計です。子孫要素は含みません。
## 2026-07 追加

- `TagGroupEntity` と `TagEntity` に `colorId` を追加した。
- どちらもデフォルトは `standard`。
- 表示側は `TagColorPalette` から色を引き、未知の `colorId` は `standard` にフォールバックする。
