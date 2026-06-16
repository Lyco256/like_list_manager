# 対応ソース

`app/src/test/java/com/lyco256/llm/TagHierarchyTest.kt`

## 役割

タグ階層、グループ集計、分類済み絞り込み、タグ/グループ名と移動先のバリデーションを確認する JVM 単体テストです。

## 主要な定義、設定、処理

- `descendantTagsAndGroupCountsIncludeNestedTagsWithoutDuplicateClips`
  - 入れ子グループの子孫タグ取得と、同一投稿に複数タグが付いてもグループ件数が重複しないことを確認します。
- `filtersApplyRequiredAndTogetherAndIncludedAsOr`
  - 必須条件は AND、含まれる条件は OR として分類済み投稿を絞り込むことを確認します。
- `siblingNameCannotDuplicateAcrossGroupAndTag`
  - 同一親配下でタグとグループの名前が重複できないことを確認します。
- `groupCannotMoveIntoDescendant`
  - グループを自分の子孫へ移動できないことを確認します。
- `rootNodeParentIsResolvedWithoutTreatingNullAsMissing`
  - ルート直下のタグ/グループの `parentGroupId = null` が「見つからない」扱いにならないことを確認します。
- `movingWithinSameParentAdjustsDownwardIndexAfterRemovingSource`
  - 同一親内で上下へ並び替えるとき、移動元を除外した後の位置補正が正しいことを確認します。
- `movingAcrossParentsInsertsAtRequestedIndex`
  - 別親配下への移動時は指定indexへ挿入されることを確認します。

## 関連ファイルと関連理由

- `app/src/main/java/com/lyco256/llm/MainActivity.kt`
  - `matchesTagFilters` の分類済み絞り込みロジックを検証します。
- `app/src/main/java/com/lyco256/llm/data/Entities.kt`
  - `TagHierarchy`、`TagNodeRef`、`TagFilterState` などのデータ構造を利用します。
- `app/src/main/java/com/lyco256/llm/data/ClipRepository.kt`
  - `requireSiblingNameAvailable`、`requireValidGroupDestination`、移動順序計算のバリデーションを検証します。

## 変更時の確認事項

- タグ階層、フィルター、グループ移動の仕様を変えた場合は、このテストを更新します。
- `testDebugUnitTest` で JVM 単体テストが通ることを確認します。
