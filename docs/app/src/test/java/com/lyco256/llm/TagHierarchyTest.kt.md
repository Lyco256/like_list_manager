# 対応ソース

`app/src/test/java/com/lyco256/llm/TagHierarchyTest.kt`

## 役割

タグ階層、グループ集計、分類済み絞り込み、タグ/グループ名と移動先のバリデーションを確認する JVM 単体テストです。

## 主要な定義、設定、処理

- `descendantTagsAndGroupCountsIncludeNestedTagsWithoutDuplicateClips`
  - 入れ子グループの子孫タグ取得と、同一投稿に複数タグが付いてもグループ件数が重複しないことを確認します。
- `filtersApplyRequiredAndTogetherAndIncludedAsOr`
  - 必須条件は AND、含まれる条件は OR として分類済み投稿を絞り込むことを確認します。
- `excludedTagsRemoveClipBeforeRequiredAndIncluded` / `groupExcludeRemovesClipsWithDescendantTags`
  - 排除条件が必須/含む条件より優先され、グループ排除は子孫タグ付き投稿を除外することを確認します。
- `taggedOnlyControlsWhetherUntaggedClipsAreSearched`
  - タグのみON/OFFで未分類投稿が検索対象に入るかどうかを確認します。
- `selectedAuthorsMatchAsOr`
  - 複数ユーザー条件が OR として扱われることを確認します。
- `dateRangeIncludesStartAndEndDate`
  - 投稿日の開始日と終了日がどちらも範囲に含まれることを確認します。
- `invalidRegexSearchReturnsEmptyList`
  - 不正な正規表現でクラッシュせず0件になることを確認します。
- `eachTextSearchTargetIsAppliedIndependentlyAndCaseInsensitively`
  - 本文・概要・表示名・usernameを個別指定でき、大文字小文字を区別しないことを確認します。
- `regexAndCombinedFiltersRequireEveryFilterDimension`
  - 正規表現、期間、投稿者、タグの複合条件がすべて成立した投稿だけを返すことを確認します。
- `filteringNeverMutatesClipsTagsOrSourceOrder`
  - 検索しても投稿、タグ、asset、入力順序が変化しないことを確認します。
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
- `negativeMoveIndexFailsInsteadOfMovingToHead`
  - 不正な負数indexが先頭移動へ丸められず、エラーになることを確認します。
- `maxMoveIndexAppendsToTail`
  - `Int.MAX_VALUE` による末尾移動を維持することを確認します。
- `slotMoveUsesIndexAfterRemovingDraggedNode`
  - placeholder方式のslot indexが、drag中nodeを除外した兄弟リスト上の挿入位置として扱われることを確認します。
- `slotMoveAcrossParentsInsertsAtRequestedSlot`
  - 別親へのslot移動と末尾移動が指定どおりになることを確認します。
- `randomizedSlotMovesAlwaysPreserveEveryNodeExactlyOnce`
  - 固定seedの250パターンで、任意の兄弟数・移動元・移動slotでもnode集合、件数、一意性、挿入位置が壊れない性質を確認します。
- `negativeSlotIndexFails`
  - slot移動でも負数indexをエラーにすることを確認します。
- `groupDropKeepsPreviousVisualPlaceholder`
  - グループ内drop候補に入っても、表示用placeholderが直前の並び替えslotに残り、保存先だけがグループ内になることを確認します。
- `draggedPlaceholderUsesSeparateKeyFromDraggedRow`
  - drag中placeholderが掴んだ行とは別keyの余白itemとして描画され、LazyColumnのkey移動によるスクロール補正を起こしにくいことを確認します。
- `topItemMovesDownOnlyOneSlotPerCrossing`
  - 先頭行を下方向へ動かして隣接行の中心線を跨いでも、1回のcrossingで1slotだけ動き、同じ位置で連続移動しないことを確認します。
- `autoScrollDoesNotRequestUnavailableDirection`
  - 一番上または一番下で、スクロールできない方向へのauto-scroll量が0になることを確認します。

## 関連ファイルと関連理由

- `app/src/main/java/com/lyco256/llm/MainActivity.kt`
  - `matchesTagFilters` と `filterClipsForSearch` の分類済み検索/絞り込みロジックを検証します。
- `app/src/main/java/com/lyco256/llm/data/Entities.kt`
  - `TagHierarchy`、`TagNodeRef`、`TagFilterState` などのデータ構造を利用します。
- `app/src/main/java/com/lyco256/llm/data/ClipRepository.kt`
  - `requireSiblingNameAvailable`、`requireValidGroupDestination`、移動順序計算のバリデーションを検証します。

## 変更時の確認事項

- タグ階層、フィルター、グループ移動の仕様を変えた場合は、このテストを更新します。
- `testDebugUnitTest` で JVM 単体テストが通ることを確認します。
## 2026-07 media grid buckets

- `buildClassifiedMediaGridItemsAddsHeadersForDateAndLikeBuckets` / `buildClassifiedMediaGridItemsUsesWeekAndMonthBucketsForWiderGrids` / `buildClassifiedMediaGridItemsLeavesDefaultSortWithoutHeaders`
  - `sort.baseOrder` と `columnCount` に応じて header item が増減し、日・週・月と 1000/5000/10000 単位の bucket が切り替わることを確認します。
  - `Default` では header を出さず、`日付不明` / `いいね数不明` / `10万以上` もそれぞれ最後にまとまることを確認します。
