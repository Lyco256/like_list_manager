# 36. 実装後docs同期

## 目的

01〜35で確定した実装だけを、現在のsource docsと全体guideへ反映する。新しい仕様やコード変更はこの作業で追加しない。

## 更新対象

実際のdiffを見て、変更したsourceに対応する `docs/<source path>.md` だけを更新する。特に内容が変わっていれば以下を反映する。

- `Entities.kt.md`: `isDeleted` 廃止、Undo entity。
- `Daos.kt.md`: active query廃止、Undo DAO、field-level update/diff API。
- `LikeListDatabase.kt.md`: DB version 9、7→8、8→9 migration。
- `ClipRepository.kt.md`: 共通Undo、tweet削除staging、heavy local work tracking、Undo対象/非対象境界。
- `MainActivity.kt.md`: load state、Undo UI state、top bar progress、filter cycle等。
- `TagHierarchyUiV2.kt.md`: Apply draft、author/like表示、画像高さ、preview、filter Tree、tag管理row、toolbar。
- 新しいsource fileを追加した場合は対応docsを作る。
- `SOURCE_FILES.md`: DB version、Undoアーキテクチャ、主要UIの現状、必要なら新sourceの入口。
- `TEST_REQUIREMENTS_COVERAGE.md`: 今回追加した回帰/統合テストの証跡と現在状態。

## 書き方

- 実ソースを正として、完成した実装と一致する説明だけを書く。
- 古い `isDeleted` / 即時分類済みtag変更 / 青必須・オレンジ排除 / DB version 7等、今回廃止した現状説明を残さない。
- 同じ仕様を複数docsへ長く重複させない。各source docsはそのsourceの責務だけ、全体概要は `SOURCE_FILES.md` に置く。
- 過去経緯の説明を増やさず、今どこを読めばよいかを明確にする。
